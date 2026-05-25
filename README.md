# InfraHack

InfraHack is a hands-on backend infrastructure lab for system design and low-level design that does not stop at diagrams.

Each module should be designed, implemented, benchmarked, stressed under realistic load, and explained with backpressure and observability in mind.

Potential public name: **InfraCodex**. The repo can keep using `infrahack` while the project direction settles.

## What This Project Is

InfraHack is for building production-shaped backend components in a way that is still readable enough to learn from:

- System design with real constraints, not only boxes and arrows
- LLD with APIs, data models, concurrency, failure modes, and tradeoffs
- Runnable implementations that stay small enough to hand-write
- Benchmarks and load tests that expose throughput, latency, p95/p99, GC, CPU, memory, and saturation
- Backpressure strategies such as bounded queues, timeouts, cancellation, rate limits, retry budgets, and load shedding
- Observability through metrics, logs, traces, profiles, and dashboards when useful

The goal is not to build a huge framework. The goal is to build a set of sharp, explainable infrastructure case studies.

## Project Principles

1. Prefer code that can be retyped by hand.
2. Add dependencies only when they remove real accidental complexity.
3. Start with one measurable vertical slice before adding architecture.
4. Treat overload behavior as a first-class design decision.
5. Capture a baseline before optimizing.
6. Document commands, results, and tradeoffs close to the module.

## Suggested Module Shape

Use this structure when it helps:

```text
module-name/
  README.md
  docs/
    design.md
    benchmark.md
    observability.md
  src-or-app-code
  tests
  load-tests
```

Keep smaller modules smaller. Do not create empty folders just to satisfy a template.

## Module README Checklist

Each module should eventually answer:

- What system design problem does this module model?
- What are the correctness constraints?
- What is the workload shape?
- What is the LLD?
- What is bounded?
- What happens under overload?
- How do I run it locally?
- How do I test it?
- How do I benchmark or load test it?
- Which metrics, logs, traces, or profiles should I inspect?
- What tradeoffs are intentionally left out?

## Benchmarking and Load Testing

Use the smallest tool that gives the signal you need.

For Java services:

- **JMeter** for complex scenario-driven load tests
- **k6** for scriptable HTTP load tests
- **JFR** for JVM runtime profiling
- **Prometheus** for service metrics
- **Grafana** for dashboards when the module needs visual inspection

For Go services:

- `go test -bench` for in-process benchmarks
- `-benchmem` for allocation visibility
- `pprof` for CPU and memory profiling
- `go tool trace` for scheduler and runtime traces
- `runtime/metrics` or Prometheus client libraries for service metrics
- `vegeta`, `hey`, `wrk`, or `k6` for HTTP load
- `ghz` for gRPC load

Useful Go commands:

```bash
go test ./...
go test -run '^$' -bench . -benchmem ./...
go test -run '^$' -bench . -cpuprofile cpu.out -memprofile mem.out ./...
go tool pprof cpu.out
go test -race ./...
```

## Observability

A module does not need a full observability platform on day one. It does need clear signals.

Start with:

- Counters for accepted, completed, failed, retried, and rejected work
- Histograms for request latency and queue wait time
- Gauges for queue depth, active workers, and in-flight requests
- Structured logs for overload, timeout, cancellation, and retry events
- Profiles or traces when performance behavior is unclear

Then add Prometheus, Grafana, JFR, pprof, or distributed tracing only when the module needs that level of inspection.

## Backpressure

Every concurrent module should be able to explain its overload behavior:

- What resource is bounded?
- How large is the bound and why?
- What happens when the bound is reached?
- Can callers cancel waiting work?
- Are retries capped and jittered?
- Which metric warns that saturation is approaching?
- Which log or trace explains rejected work?

## AI Assistant Guidance

This repo includes project-local skills for Codex and Claude:

```text
.codex/skills/infrahack/SKILL.md
.claude/skills/infrahack/SKILL.md
```

When using an AI assistant in this repo, ask for small, reviewable changes. The assistant should favor clear code, minimal dependencies, measurable behavior, and documentation that helps you understand the design rather than hiding it behind generated scaffolding.
