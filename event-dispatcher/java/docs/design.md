# Design Notes

## Problem

Build a small asynchronous event dispatcher. Publishers submit typed events. Subscribers register interest in an event type. When an event is published, every matching subscriber receives it independently.

## Correctness Constraints

- Publishing an event fans out to all subscribers registered for its type at publish time.
- Deregistered subscribers do not receive future events.
- A subscriber exception does not stop other subscribers.
- A slow subscriber does not block other subscribers.
- Subscriber queues are bounded so overload is explicit.

This in-memory dispatcher does not guarantee durable delivery. If the process exits before queued work finishes, events can be lost.

## Concurrency Model

The registry is:

```text
ConcurrentHashMap<EventType, CopyOnWriteArraySet<SubscriberRegistration>>
```

This favors frequent publishing and infrequent subscription changes. If registration churn becomes high, a nested `ConcurrentHashMap<EventType, ConcurrentHashMap<String, SubscriberRegistration>>` would be a better fit.

Each subscriber owns its executor:

```text
subscriber -> ThreadPoolExecutor(workers, bounded queue, AbortPolicy)
```

This gives strong isolation at the cost of more executor objects.

## Backpressure

Each subscriber queue is bounded. When a subscriber is saturated, its executor rejects new tasks. `AsyncEventBus.publish` records the rejection and returns it in `DispatchResult`.

Current overload behavior:

```text
queue full -> reject delivery for that subscriber -> record metric -> call error handler
```

For payment-like domains, silent dropping is usually a bad default. Later phases can add retry and dead-letter handling.

## Observability Signals

Phase 1 exposes an in-memory metrics recorder:

- published
- noSubscriber
- submitted
- succeeded
- failed
- rejected

Later phases should add queue depth, active workers, queue wait time, processing latency percentiles, and structured logs.
