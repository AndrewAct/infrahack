# Event Dispatcher

This module is a multi-language InfraHack case study for an asynchronous event dispatcher.

Problem statement:

> Design and implement an in-memory event dispatcher where publishers emit typed domain events and subscribers can register or deregister dynamically. A single event should fan out to every interested subscriber. Delivery should be asynchronous, and a slow or failing subscriber should not block the publisher or other subscribers.

## Implementations

- [Java](./java): current phase, JDK-only, hand-writeable local implementation.

Future implementations can live beside it:

```text
event-dispatcher/
  README.md
  java/
  go/
  python/
  load-tests/
```

The language folders should solve the same core problem, but they do not need identical code structure. Each implementation should use the idioms and concurrency primitives of that language.

## Current Phase

The Java implementation is the first vertical slice. It focuses on local correctness and explainability before load testing:

- dynamic registration and deregistration
- asynchronous fan-out
- slow/failing subscriber isolation
- bounded subscriber queues
- simple metrics and error hooks
