# Password Reset Workflow (Spring Boot)

A Spring Boot implementation of a debugging take-home project for the standard password reset flow:

```text
request -> verify -> reset
```

The business problem is intentionally narrow: users need a safe way to reset a password after proving they have access to an out-of-band verification code. The engineering goal is broader: practice disciplined debugging, deterministic time-based tests, API validation, observability, and optional Supabase-backed user persistence.

## What This Service Does

1. **Request** (`POST /password-reset/request`) - generate a one-time 6-digit code for an existing user and store it with a UTC `Instant` generation time. Delivery is simulated by a log line during local development.
2. **Verify** (`POST /password-reset/verify`) - accept the submitted code only if it matches and is no more than **30 seconds** old. Missing request, wrong code, and expired code are distinct errors.
3. **Reset** (`POST /password-reset/reset`) - verify the code again, hash the new password, persist it to the user's profile, and invalidate the code.

`POST /auth/login` exists as end-to-end evidence that reset really changed persisted user state.

## Why The Code Is Logged

The verification code is a temporary credential. A real password reset system must not return it directly in the `/request` response, because that would let any caller request and immediately use the code.

Production delivery would be out-of-band: email, SMS, or another trusted channel. This exercise logs the code only so local manual testing can simulate the user's inbox:

```text
reset code stored for ada@example.com (delivery simulated: code=015168)
```

## Known Broken-Version Defects

The exercise's starting point is expected to fail for these reasons:

1. Configuration references `RESET_CODE_TTL_SECONDS` without a default, so the Spring test context cannot load.
2. `requestReset` stores `code=null` instead of calling the `CodeGenerator`.
3. `verify` checks code equality but does not reject codes older than 30 seconds.
4. `resetPassword` computes a new password hash but does not call `UserRepository.updatePassword`.

Suggested repair order:

1. Fix the config default so tests can start.
2. Fix request-code generation.
3. Fix password persistence.
4. Fix expiry enforcement.
5. Rerun all tests and manually smoke-test the API.

## Run It

Use Java 25 as the README and `pom.xml` expect:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
```

Run tests:

```bash
mvn test
```

Build the jar:

```bash
mvn clean package
```

Start the service:

```bash
mvn spring-boot:run
```

The app starts on `http://localhost:8080` unless `SERVER_PORT` is set.

If you are intentionally working from the broken config state, this command shows the first failure class:

```bash
mvn test
```

After setting or defaulting the TTL, the suite can proceed to business-logic failures:

```bash
RESET_CODE_TTL_SECONDS=30 mvn test
```

## Manual API Walkthrough

Demo account:

```text
ada@example.com / correct-horse-battery
```

Request a reset code:

```bash
curl -i -X POST http://localhost:8080/password-reset/request \
  -H 'Content-Type: application/json' \
  -d '{"email":"ada@example.com"}'
```

Copy the 6-digit code from the app log, then verify it:

```bash
curl -i -X POST http://localhost:8080/password-reset/verify \
  -H 'Content-Type: application/json' \
  -d '{"email":"ada@example.com","code":"123456"}'
```

Reset the password:

```bash
curl -i -X POST http://localhost:8080/password-reset/reset \
  -H 'Content-Type: application/json' \
  -d '{"email":"ada@example.com","code":"123456","newPassword":"brand-new-password"}'
```

Prove the new password was persisted:

```bash
curl -i -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"ada@example.com","password":"brand-new-password"}'
```

The old password should now fail:

```bash
curl -i -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"ada@example.com","password":"correct-horse-battery"}'
```

Health and metrics:

```bash
curl -i http://localhost:8080/health
curl -i http://localhost:8080/health/ready
curl -i http://localhost:8080/metrics
```

## API Contract

| Method + path | Success | Failures |
| --- | --- | --- |
| `POST /password-reset/request` `{email}` | `202 Accepted` | unknown user `404`, blank email `400` |
| `POST /password-reset/verify` `{email, code}` | `200 OK` | no active request `404`, wrong code `400`, expired `410` |
| `POST /password-reset/reset` `{email, code, newPassword}` | `200 OK` | same as verify, weak password `400` |
| `POST /auth/login` `{email, password}` | `200 OK` | bad credentials `401` |
| `GET /health`, `/health/ready`, `/metrics` | `200 OK` (`503` if not ready) | |

Errors return:

```json
{"error":{"code":"...","message":"..."}}
```

## Architecture

```text
PasswordResetWorkflowApplication
  Spring Boot entry point; applies server.port from AppConfig.

config/
  AppConfig reads real env, then .env, then defaults.
  DataSourceFactory builds HikariCP for optional Postgres.
  ApplicationBeans wires repositories, service, clock, metrics, and filters.

model/
  User(email, passwordHash)
  PasswordResetRequest(email, code, generatedAt)

repository/
  UserRepository has in-memory and Postgres implementations.
  PasswordResetRequestRepository is in-process even when users are in Postgres.

service/
  PasswordResetService owns request, verify, reset, and authenticate.
  Time comes from injected Clock so tests can pin and advance time.

web/
  PasswordResetController, AuthController, health/metrics controllers,
  ApiExceptionHandler, and ObservabilityFilter.

util/
  CodeGenerator uses SecureRandom for 6-digit codes.
  PasswordHasher uses salted SHA-256 for exercise scope.
```

## Supabase Postgres (Optional)

By default, users are stored in memory and the demo account is seeded on startup. To persist user profiles in Supabase Postgres:

1. Run `db/schema.sql` in the Supabase SQL editor.
2. Run `db/seed.sql` for the demo user.
3. Copy `.env.example` to `.env` and fill in the transaction pooler values.
4. Keep `prepareThreshold=0` in `DB_URL`; the Supabase transaction pooler cannot host server-side named prepared statements.

Only user profiles move to Postgres. Reset codes intentionally stay in process because they are short-lived credentials. In a multi-instance production system, Redis with TTL and atomic consume semantics would be a better fit.

## Observability And Traceability

The service includes small, dependency-light observability features:

- `ObservabilityFilter` assigns or accepts an `X-Request-Id`, echoes it in the response, records duration, and logs one request summary line.
- `GET /metrics` exposes Prometheus-style text counters for HTTP requests and password-reset outcomes.
- Business logs identify the step outcome: request success, invalid code, expired code, reset success, and related failures.
- `GET /health` and `GET /health/ready` separate basic liveness from dependency readiness.

These features are intentionally outside the original OA's minimal fix surface. They exist to make debugging and interviews more traceable.

## Tests

`mvn test` runs two suites:

- `PasswordResetServiceTest` tests the service in isolation. It uses a pinned `MutableClock`, so the 30-second boundary is tested deterministically without `sleep`.
- `PasswordResetHttpApiTest` tests the HTTP contract on a random embedded Tomcat port. The full-flow test proves persistence by logging in with the new password after reset.

Useful narrow runs:

```bash
mvn test -Dtest=PasswordResetServiceTest#requestReset_generatesAndStoresSixDigitCodeWithTimestamp
mvn test -Dtest=PasswordResetServiceTest#verify_after30Seconds_throwsExpiredCode
mvn test -Dtest=PasswordResetServiceTest#resetPassword_persistsNewPasswordToProfile
mvn test -Dtest=PasswordResetHttpApiTest#fullFlow_requestVerifyReset_thenLoginWithNewPassword
```

## Interview Debugging Notes

When a test fails, describe the layer before proposing a fix:

- `ApplicationContext failure threshold exceeded` usually means one shared startup/config failure. Find the first `Caused by`.
- `code=null` in the log means the request path reached the service and stored a request, but the service did not generate a code.
- `Expected 410, Actual 200` on expired-code tests means the service accepted an expired request; the controller's success response is only reached because no exception was thrown.
- `login with the new password` returning `401` means reset returned success without changing the stored password hash, or login is reading a different user store than reset writes to.

A strong loop is: reproduce, isolate the layer, apply the smallest fix, run the narrow test, then run the full suite.

## Production Notes

These are intentionally out of scope for the take-home fix but useful to discuss:

- Use bcrypt, scrypt, or argon2 instead of salted SHA-256 for password hashing.
- Hash reset codes at rest and compare hashes.
- Rate-limit request and verify attempts per account and source.
- Return `202` for unknown reset-request emails in production to avoid account enumeration.
- Store reset codes in Redis or a database with TTL when running more than one instance.
- Use an atomic consume-and-update workflow to prevent replay races.
- Use one time authority for distributed expiry checks, such as database time, or design with clock skew tolerance.
