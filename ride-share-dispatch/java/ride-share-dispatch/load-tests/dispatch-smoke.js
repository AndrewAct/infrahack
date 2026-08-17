// k6 smoke/load test for ride-share-dispatch.
//
// Models the two traffic shapes described in docs/DESIGN.md "Traffic estimation":
//   1. Location pings -- high frequency, one agent phoning home every few seconds.
//   2. Dispatch requests -- much lower frequency, one requester creating a trip and
//      then driving it through offer -> assignment -> completion.
//
// Run against a real docker-compose stack (NOT Testcontainers -- those are only used by
// `mvn test`). From this directory:
//
//   docker compose -f ../docker-compose.yml up -d --wait
//   (cd .. && mvn -DskipTests package && java -jar target/ride-share-dispatch.jar &)
//   k6 run dispatch-smoke.js
//
// BASE_URL can be overridden: k6 run -e BASE_URL=http://localhost:8080 dispatch-smoke.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// San Francisco bounding box -- matches the fixture coordinates used in the integration
// tests, so a human diffing test output against load-test output is comparing like data.
const LAT_MIN = 37.74, LAT_MAX = 37.79;
const LNG_MIN = -122.44, LNG_MAX = -122.39;

const AGENT_COUNT = Number(__ENV.AGENT_COUNT || 20);

// Custom metrics beyond k6's built-in http_req_duration, so the DESIGN.md matching-path
// claims ("first success wins, losers try next candidate") are actually measurable here.
const matchSuccessRate = new Rate('dispatch_match_success_rate');
const idempotentReplayOk = new Rate('idempotent_replay_ok');
const tripCompletedCount = new Counter('trips_completed_total');
const matchLatency = new Trend('dispatch_match_latency_ms');

export const options = {
    scenarios: {
        // High-frequency hot path: every VU is one agent pinging its location.
        location_pings: {
            executor: 'constant-vus',
            exec: 'locationPing',
            vus: Number(__ENV.LOCATION_VUS || 20),
            duration: __ENV.DURATION || '2m',
        },
        // Low-frequency cold-start path: each iteration is a brand-new requester running
        // the full trip lifecycle end to end.
        dispatch_requests: {
            executor: 'constant-arrival-rate',
            exec: 'dispatchTrip',
            rate: Number(__ENV.DISPATCH_RATE || 5),
            timeUnit: '1s',
            duration: __ENV.DURATION || '2m',
            preAllocatedVUs: 20,
            maxVUs: 100,
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.01'],
        http_req_duration: ['p(95)<500'],
        dispatch_match_success_rate: ['rate>0.90'],
    },
};

function randomPoint() {
    return {
        lat: LAT_MIN + Math.random() * (LAT_MAX - LAT_MIN),
        lng: LNG_MIN + Math.random() * (LNG_MAX - LNG_MIN),
    };
}

function randomIntBetween(min, max) {
    return Math.floor(Math.random() * (max - min + 1)) + min;
}

// setup() runs once, in a single VU, before any scenario starts. Registering agents here
// (instead of inside a scenario) means their durable Postgres rows exist exactly once no
// matter how many VUs later phone in their locations, and the returned agentIds are
// shared, read-only, across every VU via k6's setup-data mechanism.
export function setup() {
    const agentIds = [];
    for (let i = 0; i < AGENT_COUNT; i++) {
        const res = http.post(
            `${BASE_URL}/agents`,
            JSON.stringify({ displayName: `LoadTestAgent-${i}`, serviceType: 'STANDARD' }),
            { headers: { 'Content-Type': 'application/json' } },
        );
        check(res, { 'agent registered': (r) => r.status === 201 });
        const agentId = res.json('agentId');

        http.post(`${BASE_URL}/agents/${agentId}/availability`,
            JSON.stringify({ available: true }),
            { headers: { 'Content-Type': 'application/json' } });

        const start = randomPoint();
        http.post(`${BASE_URL}/agents/${agentId}/location`,
            JSON.stringify({ latitude: start.lat, longitude: start.lng, sequenceNumber: 1 }),
            { headers: { 'Content-Type': 'application/json' } });

        agentIds.push(agentId);
    }
    return { agentIds };
}

// One VU = one agent's device, sending a strictly increasing sequence number forever.
// __VU is stable for the lifetime of a VU, so using it to pick an agent and seed the
// sequence counter keeps updates from one simulated device monotonic, matching the
// invariant AgentOperationalStateStore enforces server-side.
export function locationPing(data) {
    const agentId = data.agentIds[__VU % data.agentIds.length];
    // setup() used sequence 1. Start scenario updates at 2 so the first real ping is new.
    const seq = __ITER + 2;
    const point = randomPoint();

    const res = http.post(`${BASE_URL}/agents/${agentId}/location`,
        JSON.stringify({ latitude: point.lat, longitude: point.lng, sequenceNumber: seq }),
        { headers: { 'Content-Type': 'application/json' } });

    check(res, { 'location accepted': (r) => r.status === 200 && r.json('result') === 'ACCEPTED' });
    sleep(randomIntBetween(1, 3));
}

// One iteration = one requester's full trip: create -> (maybe) offer -> accept -> start
// -> complete, plus an idempotent replay of the create call to keep that guarantee
// exercised under load, not just in the unit test suite.
export function dispatchTrip() {
    const requesterId = crypto.randomUUID();
    const idempotencyKey = crypto.randomUUID();
    const origin = randomPoint();
    const dest = randomPoint();

    const createBody = JSON.stringify({
        requesterId, serviceType: 'STANDARD',
        originLat: origin.lat, originLng: origin.lng,
        destLat: dest.lat, destLng: dest.lng,
    });
    const headers = { headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey } };

    const createStart = Date.now();
    const createRes = http.post(`${BASE_URL}/dispatch-requests`, createBody, headers);
    matchLatency.add(Date.now() - createStart);

    check(createRes, { 'dispatch request created': (r) => r.status === 201 });
    const offer = createRes.json('offer');
    matchSuccessRate.add(offer !== null && offer !== undefined);

    // Prove the idempotency guarantee under real concurrent load: the exact same
    // (requesterId, Idempotency-Key, body) must replay the same request, not create a
    // second one -- this is invariant #1 from docs/DESIGN.md, checked here over HTTP
    // rather than only in DispatchRequestIdempotencyTest.
    const replayRes = http.post(`${BASE_URL}/dispatch-requests`, createBody, headers);
    idempotentReplayOk.add(
        replayRes.status === 200 && replayRes.json('requestId') === createRes.json('requestId'));

    if (!offer) {
        return; // No agent was available/matchable -- expected under high dispatch_rate / low AGENT_COUNT.
    }

    const acceptRes = http.post(`${BASE_URL}/offers/${offer.offerId}/accept`, null,
        { headers: { 'Content-Type': 'application/json' } });
    if (acceptRes.status !== 200) {
        return; // Offer expired or lost the reservation race before we could accept it.
    }
    const assignmentId = acceptRes.json('assignmentId');

    const startRes = http.post(`${BASE_URL}/assignments/${assignmentId}/start`, null,
        { headers: { 'Content-Type': 'application/json' } });
    check(startRes, { 'assignment started': (r) => r.status === 200 });

    const completeRes = http.post(`${BASE_URL}/assignments/${assignmentId}/complete`, null,
        { headers: { 'Content-Type': 'application/json' } });
    if (check(completeRes, { 'assignment completed': (r) => r.status === 200 })) {
        tripCompletedCount.add(1);
    }
}
