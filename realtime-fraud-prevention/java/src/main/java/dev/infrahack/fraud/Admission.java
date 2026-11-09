package dev.infrahack.fraud;

import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;
import org.apache.flink.metrics.Counter;

/** One serial state owner per tenant/account/card. Late admission always precedes ledger mutation. */
public class Admission extends KeyedProcessFunction<String, Payment, Payment> {
    public static final long RETENTION = 120_000L;
    public static final OutputTag<String> REJECTED = new OutputTag<>("admission-rejections") {};
    private transient MapState<String, Seen> attempts;
    private transient MapState<String, Seen> events;
    private transient Counter late;
    private transient Counter duplicates;
    private transient Counter conflicts;
    private transient Counter accepted;

    public static class Seen {
        public String fingerprint;
        public long time;
        public Seen() {}
        Seen(Payment p) { fingerprint = p.fingerprint; time = p.occurredAt; }
    }

    @Override public void open(OpenContext context) throws Exception {
        attempts = getRuntimeContext().getMapState(new MapStateDescriptor<>("attempts", String.class, Seen.class));
        events = getRuntimeContext().getMapState(new MapStateDescriptor<>("events", String.class, Seen.class));
        late = getRuntimeContext().getMetricGroup().counter("late");
        duplicates = getRuntimeContext().getMetricGroup().counter("duplicates");
        conflicts = getRuntimeContext().getMetricGroup().counter("conflicts");
        accepted = getRuntimeContext().getMetricGroup().counter("accepted");
    }

    @Override public void processElement(Payment p, Context ctx, Collector<Payment> out) throws Exception {
        if (p.occurredAt <= ctx.timerService().currentWatermark()) {
            reject("late", p, ctx);
            return;
        }
        Seen attempt = attempts.get(p.attemptId);
        Seen event = events.get(p.eventId);
        Seen prior = attempt != null ? attempt : event;
        if ((attempt != null && !attempt.fingerprint.equals(p.fingerprint))
            || (event != null && !event.fingerprint.equals(p.fingerprint))) {
            reject("conflict", p, ctx);
            // Conservative exclusion: pending/future snapshots stay incomplete until both facts age out.
            p.conflict = true;
            p.conflictUntil = Math.max(p.occurredAt, Math.max(attempt == null ? Long.MIN_VALUE : attempt.time,
                event == null ? Long.MIN_VALUE : event.time)) + 600_000L;
            out.collect(p);
            return;
        }
        if (prior != null) {
            events.put(p.eventId, new Seen(p));
            reject("duplicate", p, ctx);
            return;
        }
        attempts.put(p.attemptId, new Seen(p));
        events.put(p.eventId, new Seen(p));
        ctx.timerService().registerEventTimeTimer(p.occurredAt + RETENTION);
        accepted.inc();
        out.collect(p);
    }

    private void reject(String reason, Payment p, Context ctx) {
        ctx.output(REJECTED, reason + ":" + p.eventId);
        switch (reason) {
            case "late" -> late.inc();
            case "duplicate" -> duplicates.inc();
            case "conflict" -> conflicts.inc();
            default -> throw new IllegalArgumentException(reason);
        }
    }

    @Override public void onTimer(long timestamp, OnTimerContext ctx, Collector<Payment> out) throws Exception {
        // Readable baseline. A timestamp-bucketed ledger is a later capacity optimization.
        var expiredAttempts = new java.util.ArrayList<String>();
        for (var e : attempts.entries()) if (e.getValue().time + RETENTION <= timestamp) expiredAttempts.add(e.getKey());
        for (String id : expiredAttempts) attempts.remove(id);
        var expiredEvents = new java.util.ArrayList<String>();
        for (var e : events.entries()) if (e.getValue().time + RETENTION <= timestamp) expiredEvents.add(e.getKey());
        for (String id : expiredEvents) events.remove(id);
    }
}
