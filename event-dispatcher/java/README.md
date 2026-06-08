# Async Event Dispatcher: Java

This folder implements a small in-memory asynchronous event dispatcher in Java.

It is inspired by a common event-driven system design problem, but framed as an original InfraHack module:

> Design and implement an in-memory event dispatcher where publishers emit typed domain events and subscribers can register or deregister dynamically. A single event should fan out to every interested subscriber. Delivery should be asynchronous, and a slow or failing subscriber should not block the publisher or other subscribers.

## Current Phase

This is phase 1: a hand-writeable local version.

Goals:

- Keep the production code small enough to retype and explain.
- Use only the JDK.
- Support dynamic registration and deregistration.
- Fan out one event to multiple subscribers.
- Run each subscriber asynchronously.
- Isolate slow and failing subscribers.
- Use bounded per-subscriber queues so overload is visible.
- Expose simple metrics hooks for later benchmark and load-test work.

Intentionally out of scope for this phase:

- Durable delivery
- Retry and dead-letter queues
- Ordered delivery
- Kafka/RabbitMQ/SQS integration
- Prometheus/Grafana
- Load testing

## Design

```text
Publisher
  |
  v
AsyncEventBus.publish(event)
  |
  v
Concurrent registry: EventType -> subscribers
  |
  v
Fan-out
  |
  +--> Subscriber A executor + bounded queue
  |
  +--> Subscriber B executor + bounded queue
```

The key choice is per-subscriber bounded executors. This keeps the first version easy to reason about:

- A slow subscriber fills only its own queue.
- A failed subscriber is caught and reported through `ErrorHandler`.
- A saturated subscriber rejects new work through `RejectedExecutionException`.
- Other subscribers can continue processing.

The tradeoff is that many subscribers can mean many thread pools. Later phases can compare this with a shared executor model.

## Run Locally

From `event-dispatcher/java`:

```bash
javac -d out $(find src/main/java -name '*.java')
java -cp out io.infrahack.eventdispatcher.Demo
```

To simulate a Kafka-style hot partition where one partition receives 10x more events
than the others:

```bash
java -cp out io.infrahack.eventdispatcher.PartitionSkewDemo
```

If you use an IDE, import `event-dispatcher/java/pom.xml` as the project. The source roots should be:

```text
src/main/java
src/test/java
```

Expected shape of the output:

```text
first publish result: DispatchResult[matchedSubscribers=2, submitted=2, rejected=0]
notification handled ...
fraud-check handled ...
second publish result: DispatchResult[matchedSubscribers=1, submitted=1, rejected=0]
fraud-check handled ...
metrics: Snapshot[published=2, noSubscriber=0, submitted=3, succeeded=3, failed=0, rejected=0]
```

## Run Tests

```bash
javac -d out $(find src/main/java src/test/java -name '*.java')
java -cp out io.infrahack.eventdispatcher.EventDispatcherTests
```

You can also use Maven to compile both main and test sources:

```bash
mvn test
```

The test runner covers:

- fan-out to all subscribers
- deregistration
- failure isolation
- slow subscriber isolation
- bounded queue rejection

## Main Types

- `DomainEvent`: immutable event data.
- `EventType`: event category.
- `Subscriber`: handler interface.
- `SubscriberRegistration`: subscriber id, handler, and executor.
- `AsyncEventBus`: registry, fan-out, async dispatch, and shutdown.
- `BoundedExecutors`: small factory for bounded subscriber executors.
- `MetricsRecorder`: hook for observability.
- `ErrorHandler`: hook for failure handling.

## Future Work

Next phases can add:

- retry budget and dead-letter queue
- partitioned ordering by `partitionKey`
- latency histograms and queue-depth metrics
- JMH benchmark or service wrapper plus k6 load test
- comparison between per-subscriber and shared-executor dispatch
