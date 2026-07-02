# Password Reset Workflow

A debugging-style take-home project for the classic password reset flow:

```text
request -> verify -> reset
```

The exercise is intentionally small in business scope but useful for practicing production-style diagnosis: reproduce failures, separate configuration problems from logic bugs, make targeted fixes, and verify the whole flow through tests and manual API calls.

## Assignment Spec

The intended behavior is:

1. **Request** - when an existing user asks to reset their password, generate a one-time verification code and store it with the generation time.
2. **Verify** - accept a submitted code only if it matches the stored code and was generated no more than **30 seconds** ago.
3. **Reset** - after verification succeeds, persist the new password to the user's profile. Returning a success response is not enough.

The broken starting point is designed to expose four failures:

1. The reset code is not generated during the request step.
2. The verify step accepts expired codes.
3. The reset step computes a new password hash but does not persist it.
4. The test context initially fails to load because configuration references an undefined variable.

## Debugging Strategy

Treat the failures in two classes.

First, fix the test-load/configuration failure. If `mvn test` reports many `ApplicationContext failure threshold exceeded` errors, do not debug each test method. Scroll to the first `Caused by` in Maven output or inspect `target/surefire-reports/*.txt`. The expected root cause in the broken version is an unresolved `RESET_CODE_TTL_SECONDS` placeholder.

Second, rerun tests after the context can start. The remaining failures should map to the three business rules: code generation, expiry enforcement, and password persistence.

A good interview-style debugging loop is:

1. Reproduce with the documented command.
2. Identify whether the failure is startup/config, service logic, repository persistence, or HTTP mapping.
3. Make the smallest fix in the layer that owns the broken behavior.
4. Rerun the narrow failing test.
5. Rerun the full suite.
6. Manually smoke-test request -> verify -> reset -> login.

## Java Implementation

The Java implementation lives in:

```text
java/passwordresetworkflow/
```

It is a Spring Boot + Maven service with:

- In-memory users by default.
- Optional Supabase Postgres for user profile persistence.
- In-process reset-code storage, because codes live for only 30 seconds.
- Request ids, structured-ish logs, health checks, and Prometheus-style metrics.
- Service-level and HTTP-level tests.

Those observability and Supabase pieces are intentional extensions around the debugging exercise. They should support traceability without changing the core assignment: the fix should still be small and focused.

See [java/passwordresetworkflow/README.md](java/passwordresetworkflow/README.md) for setup, API examples, architecture notes, and production tradeoffs.
