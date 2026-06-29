# Thread-Safe Key-Value Store

A Java implementation of a thread-safe in-memory key-value store for a single-process, multi-threaded environment.

This project is designed for a technical screen style problem: many worker threads share one in-memory store, and any thread may concurrently read, write, or delete keys. The goal is to make each operation safe and atomic while reducing unnecessary lock contention.

## Problem Statement

Design a key-value store with the following operations:

```java
void put(String key, V value);
V get(String key);
void delete(String key);
```

Requirements:

- Single process, multiple threads.
- Keys are strings.
- Values are generic Java objects.
- `put` inserts or overwrites a key.
- `get` returns the current value or `null` if absent.
- `delete` removes a key if it exists.
- Operations must be safe under arbitrary concurrent usage.
- Each operation should appear atomic to callers.
- Lock contention should be minimized where reasonable.

Out of scope for the current version:

- Distributed storage.
- Replication.
- Persistence to disk.
- TTL expiration.
- Eviction policies.
- Transactions across multiple keys.

## High-Level Design

The store uses lock striping.

Instead of protecting the entire store with one global lock, the store is split into multiple buckets. Each bucket owns:

- A normal `HashMap<String, V>`.
- A `ReentrantReadWriteLock`.

A key is routed to exactly one bucket using its hash:

```java
int index = Math.floorMod(key.hashCode(), buckets.length);
```

All operations for the same key always go to the same bucket. Before reading or mutating that bucket's `HashMap`, the code acquires that bucket's lock.

Conceptually:

```text
Store
  bucket 0: HashMap + ReadWriteLock
  bucket 1: HashMap + ReadWriteLock
  bucket 2: HashMap + ReadWriteLock
  ...
```

## Why Buckets?

A single global lock is correct, but it serializes every operation:

```text
Thread A: get("user:1")       running
Thread B: put("movie:9")      blocked
Thread C: delete("session:3") blocked
```

Those keys are unrelated, but they still block each other.

With buckets, unrelated keys can often proceed in parallel:

```text
Thread A: get key in bucket 0     running
Thread B: put key in bucket 3     running
Thread C: delete key in bucket 7  running
```

This reduces lock contention while keeping the implementation simple enough to reason about.

## Synchronization Strategy

The current implementation uses `ReentrantReadWriteLock` per bucket.

- `get` acquires the bucket's read lock.
- `put` acquires the bucket's write lock.
- `delete` acquires the bucket's write lock.

This allows multiple reads in the same bucket to happen concurrently, while writes remain exclusive.

The core rule is:

> The bucket's internal `HashMap` is never accessed without holding that bucket's lock.

That is what makes a normal `HashMap` safe in this design.

## Correctness Invariants

The implementation relies on these invariants:

1. A key always maps to the same bucket for the lifetime of the store.
2. Each bucket's `HashMap` is protected by exactly one lock.
3. Every access to a bucket's `HashMap` happens while holding that bucket's lock.
4. `put` and `delete` use the write lock because they mutate the map.
5. `get` uses the read lock because it only reads the map in the current non-TTL version.
6. Values cannot be `null`, so `get(key) == null` unambiguously means the key is absent.

Each operation has a clear linearization point:

- `put`: the moment `entries.put(key, value)` executes under the write lock.
- `get`: the moment `entries.get(key)` executes under the read lock.
- `delete`: the moment `entries.remove(key)` executes under the write lock.

Because operations on the same key use the same bucket lock, they cannot corrupt the bucket's internal map or observe a partially completed mutation.

## Atomicity

The store guarantees atomicity for individual operations:

```java
store.put("a", 1);
store.get("a");
store.delete("a");
```

Each call appears to happen as one indivisible operation.

However, the store does not make multi-step client logic atomic:

```java
Integer value = store.get("counter");
store.put("counter", value + 1);
```

That read-modify-write sequence can still lose updates if two threads execute it concurrently. Supporting that safely would require a new operation such as:

```java
V compute(String key, Function<V, V> updater);
boolean compareAndSet(String key, V expected, V next);
```

Those methods would need to perform the full read-modify-write sequence while holding the bucket's write lock.

## Why Not Just Use ConcurrentHashMap?

In production Java code, `ConcurrentHashMap` is usually the right default for a concurrent in-memory map.

This project intentionally avoids it because the interview prompt asks how to build the synchronization manually in a language or environment without a built-in concurrent map.

The important lesson is:

- `HashMap` alone is not thread-safe.
- `HashMap` protected by disciplined locking can be thread-safe.
- Lock striping is a common way to reduce contention compared with one global lock.

## Tradeoffs

### Global Lock

Benefits:

- Simplest implementation.
- Easiest correctness proof.
- Good enough for low concurrency or small workloads.

Shortcomings:

- All reads and writes block each other.
- Poor scalability under concurrent access.
- Unrelated keys cannot be processed in parallel.

### Per-Key Lock

Benefits:

- Very fine-grained locking.
- Different keys can proceed independently.

Shortcomings:

- Requires a separate lock registry.
- The lock registry itself needs synchronization.
- Lock cleanup is tricky after delete.
- Can increase memory usage if many keys are created.
- Multi-key operations can introduce deadlock risk if lock ordering is not handled carefully.

### Lock Striping / Buckets

Benefits:

- Much less contention than a global lock.
- Much simpler than per-key locks.
- Bounded number of locks.
- Same-key operations are serialized because the same key always maps to the same bucket.
- Different-bucket operations can run concurrently.

Shortcomings:

- Keys in the same bucket still block each other.
- Hot keys can still become bottlenecks.
- Bucket count is a tuning parameter.
- Hash distribution affects performance.
- Resizing the number of buckets at runtime would require careful coordination.

### Read-Write Lock vs ReentrantLock

A plain `ReentrantLock` is an exclusive lock. Only one thread can enter the protected section at a time, even if all threads only want to read.

A `ReentrantReadWriteLock` allows multiple readers to enter concurrently, while writes remain exclusive.

This helps when the workload is read-heavy:

```text
many get operations
fewer put/delete operations
```

Tradeoff:

- Read-write locks are more complex than plain locks.
- They may not help much for write-heavy workloads.
- Writer starvation or fairness policy can become a concern in some systems.

For this interview problem, per-bucket read-write locks are a reasonable balance between correctness, performance, and explainability.

## Current API Behavior

```java
put(key, value)
```

- Rejects `null` keys.
- Rejects `null` values.
- Inserts a new key or overwrites an existing key.
- Uses the bucket write lock.

```java
get(key)
```

- Rejects `null` keys.
- Returns the current value if present.
- Returns `null` if absent.
- Uses the bucket read lock.

```java
delete(key)
```

- Rejects `null` keys.
- Removes the key if it exists.
- Does nothing if the key is absent.
- Uses the bucket write lock.

```java
size()
```

- Iterates over all buckets and sums their sizes.
- Acquires each bucket's read lock while reading that bucket.
- The returned size is a point-in-time style observation across buckets, not a transactional global snapshot if other threads are mutating the store concurrently.

## Testing Strategy

The test suite should cover both ordinary map behavior and concurrent access.

### Unit Tests

Basic tests validate:

- Put then get returns the value.
- Put overwrites an existing value.
- Missing keys return `null`.
- Delete removes a key.
- Deleting a missing key is safe.
- Null keys are rejected.
- Null values are rejected.

### Concurrent Stress Tests

Concurrency tests use many threads and a `CountDownLatch` so the threads start at roughly the same time.

Important scenarios:

- Many threads write different keys.
- Many threads write the same key.
- Many threads mix put, get, and delete operations.

The goal is to validate that:

- The store does not throw unexpected exceptions.
- The internal `HashMap` is not corrupted.
- Same-key writes leave one valid final value.
- Different-key writes are not lost.

### Additional Validation Ideas

For deeper validation, consider:

- Running stress tests many times in a loop.
- Increasing thread count and operation count.
- Testing with a very small bucket count to force contention.
- Testing with a larger bucket count to validate parallelism.
- Using Java concurrency testing tools such as jcstress for memory-model-level tests.
- Benchmarking with JMH if performance claims need evidence.

## Interview Walkthrough

A concise interview answer could sound like this:

> I would start with a `HashMap` because the API is exactly key-value lookup, and string keys are naturally hashable. A plain `HashMap` is not thread-safe, so I would never access it without synchronization. The simplest correct version is one global lock around the whole map, but that creates unnecessary contention because unrelated keys block each other.
>
> To reduce contention, I would use lock striping. I split the store into a fixed number of buckets. Each bucket owns a normal `HashMap` and a `ReentrantReadWriteLock`. A key is routed to one bucket using `hash(key) % bucketCount`. All operations for that key always use the same bucket lock.
>
> For `put` and `delete`, I take the bucket's write lock because they mutate the map. For `get`, I take the bucket's read lock because it only reads. That means multiple reads in the same bucket can proceed together, while writes are still exclusive. Operations on different buckets can proceed in parallel.
>
> The atomicity guarantee is for single operations. A call to `put`, `get`, or `delete` appears indivisible because the actual map access happens while holding the correct bucket lock. If the caller needs atomic read-modify-write behavior, such as incrementing a counter, I would add a separate `compute` or `compareAndSet` method and execute that whole sequence under the bucket write lock.

## Production Considerations

If this store were used in production, I would consider adding:

- Capacity limits to avoid unbounded memory growth.
- TTL support for expiring entries.
- Optional eviction policy such as LRU or LFU.
- Metrics for operation count, latency, hit rate, miss rate, size, and lock contention.
- Logging for validation failures or unexpected internal errors.
- Better observability around hot keys or hot buckets.
- Configurable bucket count based on workload and CPU count.
- A background cleanup thread if TTL is implemented.
- A shutdown hook if background threads exist.
- Persistence or snapshotting if data must survive process restart.
- Additional atomic APIs such as `compute`, `putIfAbsent`, or `compareAndSet`.

## TTL Extension

TTL is not implemented in the current version.

To add TTL, the main structural change would be replacing:

```java
Map<String, V>
```

with:

```java
Map<String, CacheEntry<V>>
```

where `CacheEntry` contains:

- The stored value.
- An absolute expiration timestamp.

On `get`, the store would check whether the entry has expired. If expired, it would remove the entry and return `null`.

Important TTL design note:

- A TTL-aware `get` may need the write lock, because it might delete an expired entry.
- Alternatively, `get` can use a two-phase approach: read lock first, then upgrade by releasing the read lock and acquiring the write lock if cleanup is needed.

Lazy expiration is enough for correctness, but a production system may also use a background sweeper to reclaim memory from expired keys that are never read again.

## Complexity

For a fixed number of buckets:

- `put`: average `O(1)` time, `O(1)` additional space.
- `get`: average `O(1)` time, `O(1)` additional space.
- `delete`: average `O(1)` time, `O(1)` additional space.
- `size`: `O(number of buckets)` plus constant-time size reads per bucket.

Concurrency scales with the number of buckets and the key distribution. In the best case, operations are spread across buckets and can proceed in parallel. In the worst case, many hot keys map to the same bucket and serialize on that bucket's lock.

## Summary

This design is intentionally small but interview-ready:

- `HashMap` provides efficient key-value storage.
- Per-bucket locks protect ordinary non-thread-safe maps.
- Lock striping reduces contention compared with a global lock.
- Read-write locks improve read-heavy workloads.
- Tests cover both functional behavior and concurrent stress.
- The design leaves clear extension points for TTL, eviction, metrics, and atomic read-modify-write operations.
