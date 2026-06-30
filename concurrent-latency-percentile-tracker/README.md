# Concurrent Latency Percentile Tracker

> 这题第一眼看不懂很正常。它不是一道单纯的 Java 语法题，而是把 metrics 系统、时间窗口、percentile、并发写入放在一起考。不要慌，我们把它拆成几个小问题，它就会变得清楚。

## 题目要你设计什么

我们要设计一个线程安全的 `LatencyTracker`，支持两个操作：

```java
addSample(timestampMs, latencyMs)
getPercentile(startTimestampMs, endTimestampMs, p)
```

现实场景可以这样理解：

Netflix 服务每次处理请求都会产生一条 latency sample：

```text
时间戳: 10:00:01.123
耗时:   87 ms
```

系统会不断收到这样的数据。然后有人问：

```text
过去 5 分钟内，p99 latency 是多少？
10:00:00 到 10:01:00 之间，p95 latency 是多少？
```

我们需要快速回答。

## Percentile 是什么意思

如果窗口内 latency 排序后是：

```text
10, 20, 30, 40, 50
```

那么：

```text
p50 = 第 ceil(5 * 50 / 100) = 第 3 个 = 30
p90 = 第 ceil(5 * 90 / 100) = 第 5 个 = 50
p100 = 第 5 个 = 50
```

我们用的是 nearest-rank percentile：

```java
rank = ceil(totalSamples * percentile / 100.0)
```

## 最直接但不够好的做法

可以把所有 sample 都存在一个 list 里：

```java
class Sample {
    long timestampMs;
    int latencyMs;
}
```

查询时扫描所有 sample，挑出时间窗口内的数据，排序后拿 percentile。

问题是：

```text
addSample: O(1)
getPercentile: O(N log N)
```

如果 sample 很多，查询会很慢。

## 更适合面试的设计：按时间分桶 + histogram

核心想法：

```text
先按时间分桶，再在每个桶里统计 latency 出现次数。
```

比如 `bucketSizeMs = 1000`，表示每 1 秒一个 bucket。

```text
timestamp 10_123 -> bucket 10_000
timestamp 10_999 -> bucket 10_000
timestamp 11_000 -> bucket 11_000
```

每个 bucket 内部不是保存所有原始 sample，而是保存 histogram：

```text
bucket 10_000:
  20ms -> 3 次
  50ms -> 7 次
  90ms -> 1 次
```

查询 `[start, end]` 时：

1. 找到覆盖这个时间窗口的 buckets
2. 把这些 buckets 的 histogram 合并成一个总 histogram
3. 按 latency 从小到大累计 count
4. 找到第 `rank` 个 sample 对应的 latency

## 数据结构

```java
private final ConcurrentHashMap<Long, Bucket> buckets;
```

含义：

```text
bucketStartMs -> 这个时间桶里的 latency 统计
```

每个 `Bucket` 里：

```java
private final ConcurrentHashMap<Integer, LongAdder> countsByLatency;
```

含义：

```text
latencyMs -> 这个 latency 在当前 bucket 里出现了多少次
```

为什么 value 是 `LongAdder`？

因为多个 writer 线程可能同时记录同一个 latency。`LongAdder` 比 `AtomicLong` 更适合高并发计数，热点 key 上竞争更低。

## addSample 做了什么

伪代码：

```java
public void addSample(long timestampMs, int latencyMs) {
    long bucketStart = bucketStart(timestampMs);
    advanceLatestBucket(bucketStart);

    if (bucketStart is too old) {
        return;
    }

    Bucket bucket = buckets.computeIfAbsent(bucketStart, Bucket::new);
    bucket.add(latencyMs);

    occasionally cleanup old buckets;
}
```

现实意义：

```text
把一条 sample 放进对应的时间桶，然后把这个 latency 的计数 +1。
```

例如：

```text
addSample(10_123, 50)
```

如果 `bucketSizeMs = 1000`，它会进入 bucket `10_000`，然后：

```text
50ms count += 1
```

## getPercentile 做了什么

伪代码：

```java
public OptionalInt getPercentile(long start, long end, double p) {
    NavigableMap<Integer, Long> mergedHistogram = new TreeMap<>();
    long total = 0;

    for (Bucket bucket : buckets.values()) {
        if (bucket is inside query window) {
            total += bucket.snapshotInto(mergedHistogram);
        }
    }

    long rank = ceil(total * p / 100.0);

    long seen = 0;
    for (latency in sorted order) {
        seen += countOfThisLatency;
        if (seen >= rank) {
            return latency;
        }
    }
}
```

`mergedHistogram` 的含义非常重要：

```text
它不是一个 bucket 的 count。
它是整个查询窗口里，所有 bucket 合并后的 latency count。
```

例如窗口覆盖 3 个 bucket：

```text
bucket A:
  100ms -> 2 次
  200ms -> 1 次

bucket B:
  100ms -> 3 次
  300ms -> 1 次

bucket C:
  200ms -> 4 次
```

合并后：

```text
100ms -> 5 次
200ms -> 5 次
300ms -> 1 次
```

然后用这个总 histogram 算 percentile。

## snapshotInto 为什么这样写

```java
private long snapshotInto(NavigableMap<Integer, Long> destination) {
    long bucketTotal = 0;
    for (Map.Entry<Integer, LongAdder> entry : countsByLatency.entrySet()) {
        long count = entry.getValue().sum();
        if (count > 0) {
            destination.merge(entry.getKey(), count, Long::sum);
            bucketTotal += count;
        }
    }
    return bucketTotal;
}
```

这一行：

```java
long count = entry.getValue().sum();
```

含义是：

```text
entry.getValue() 是 LongAdder，不是普通 long。
LongAdder 是并发计数器。
.sum() 是把当前累计值读出来。
```

这一行：

```java
destination.merge(entry.getKey(), count, Long::sum);
```

含义是：

```text
把当前 bucket 的某个 latency count 合并到整个查询窗口的总 histogram 里。
```

等价于：

```java
int latency = entry.getKey();
if (destination.containsKey(latency)) {
    destination.put(latency, destination.get(latency) + count);
} else {
    destination.put(latency, count);
}
```

注意类型：

```java
// Bucket 内部：需要并发计数
ConcurrentHashMap<Integer, LongAdder> countsByLatency

// 查询时临时合并：只有当前 reader 线程使用，不需要 LongAdder
NavigableMap<Integer, Long> mergedHistogram
```

所以 `snapshotInto` 的参数应该是：

```java
NavigableMap<Integer, Long> destination
```

不是：

```java
NavigableMap<Integer, LongAdder> destination
```

## advanceLatestBucket 是什么

```java
private void advanceLatestBucket(long bucketStart) {
    long current;
    while (bucketStart > (current = latestBucketStart.get())) {
        if (latestBucketStart.compareAndSet(current, bucketStart)) {
            return;
        }
    }
}
```

它的现实意义是：

```text
线程安全地记录目前见过的最大 bucketStart。
```

也就是一个并发安全版本的：

```java
latestBucketStart = Math.max(latestBucketStart, bucketStart);
```

为什么需要它？

为了清理过旧数据。

如果我们只保留最近 10 分钟的数据，需要知道目前最新时间桶是多少，然后删掉太旧的 bucket。

为什么用 CAS？

因为多个 writer 可能同时写入：

```text
当前 latest = 10_000
线程 A 想更新到 12_000
线程 B 想更新到 13_000
```

CAS 可以保证最后保留下来的是最大值，不会被旧值覆盖。

## cleanup 为什么不是每次都做

```java
private static final long CLEANUP_INTERVAL_WRITES = 1024;
```

意思是：

```text
每写入 1024 条 sample，顺手清理一次旧 bucket。
```

为什么不是每次写入都清理？

因为清理要扫描 buckets，如果每次 add 都清理，写入成本会变高。

这是 amortized cleanup：

```text
把清理成本摊到很多次写入上。
```

如果系统写入量很低，也可以用后台 scheduled cleanup。

## 并发语义

这个设计支持：

```text
多个线程同时 addSample
多个线程同时 getPercentile
addSample 和 getPercentile 同时发生
```

使用的并发工具：

```text
ConcurrentHashMap: 并发读写 buckets 和 latency counts
LongAdder: 高并发计数
AtomicLong + CAS: 维护 latestBucketStart
```

但是要主动说明一个重要点：

```text
getPercentile 是 weakly consistent snapshot。
```

意思是：

```text
查询过程中并发写入的 sample，可能被这次查询看到，也可能看不到。
```

对于 metrics 系统这通常可以接受。

如果面试官要求严格线性一致性，需要换设计：

```text
方案 1: ReadWriteLock 包住 add 和 query
优点: 查询结果更严格
缺点: 写入和查询会互相阻塞

方案 2: copy-on-write snapshot
优点: reader 很稳定
缺点: 写入/内存成本更高

方案 3: append-only log + immutable segment
优点: 更接近生产级时序系统
缺点: 实现复杂度更高
```

## 精确性 tradeoff

`bucketSizeMs` 决定时间分桶粒度。

```text
bucketSizeMs = 1
```

每 1ms 一个 bucket。对于毫秒 timestamp，窗口查询可以精确。

```text
bucketSizeMs = 1000
```

每 1 秒一个 bucket。bucket 数更少，查询更快，但窗口边界可能近似。

例如查询：

```text
[10_123, 15_456]
```

如果按 1 秒分桶，可能会包含：

```text
10_000 bucket 的全部数据
15_000 bucket 的全部数据
```

也就是边界上会多算一些 sample。

面试中可以说：

```text
如果要求精确，我会使用 bucketSizeMs = 1，或者在边界 bucket 内保留原始 timestamp 再过滤。
如果是监控系统，我会选择 1s 或 10s bucket，接受边界近似，换取更低成本。
```

## 复杂度

设：

```text
B = 查询窗口覆盖的 bucket 数量
D = 查询窗口内不同 latency 值数量
```

复杂度：

```text
addSample: 接近 O(1)
getPercentile: O(B * 每桶不同 latency 数 + D)
```

因为 `TreeMap` 会按 latency 排序，merge 时有 `log D` 成本，也可以写成：

```text
getPercentile: O(totalDistinctEntries * log D + D)
```

空间复杂度：

```text
O(retainedBuckets * distinctLatenciesPerBucket)
```

## 一份可讲的 Java 实现

```java
import java.util.Map;
import java.util.NavigableMap;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class LatencyTracker {
    private static final long CLEANUP_INTERVAL_WRITES = 1024;

    private final long bucketSizeMs;
    private final long retentionMs;
    private final ConcurrentHashMap<Long, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong latestBucketStart = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong writeCount = new AtomicLong();

    public LatencyTracker(long bucketSizeMs, long retentionMs) {
        if (bucketSizeMs <= 0) {
            throw new IllegalArgumentException("bucketSizeMs must be positive");
        }
        if (retentionMs <= 0) {
            throw new IllegalArgumentException("retentionMs must be positive");
        }
        this.bucketSizeMs = bucketSizeMs;
        this.retentionMs = retentionMs;
    }

    public void addSample(long timestampMs, int latencyMs) {
        if (latencyMs < 0) {
            throw new IllegalArgumentException("latencyMs must be non-negative");
        }

        long bucketStart = bucketStart(timestampMs);
        advanceLatestBucket(bucketStart);

        long latest = latestBucketStart.get();
        if (latest != Long.MIN_VALUE && bucketStart < latest - retentionMs) {
            return;
        }

        buckets.computeIfAbsent(bucketStart, Bucket::new).add(latencyMs);

        if (writeCount.incrementAndGet() % CLEANUP_INTERVAL_WRITES == 0) {
            cleanupOldBuckets();
        }
    }

    public OptionalInt getPercentile(long startTimestampMs, long endTimestampMs, double percentile) {
        if (startTimestampMs > endTimestampMs) {
            throw new IllegalArgumentException("startTimestampMs must be <= endTimestampMs");
        }
        if (percentile <= 0.0 || percentile > 100.0) {
            throw new IllegalArgumentException("percentile must be in (0, 100]");
        }

        long firstBucket = bucketStart(startTimestampMs);
        long lastBucket = bucketStart(endTimestampMs);
        NavigableMap<Integer, Long> mergedHistogram = new TreeMap<>();
        long total = 0;

        for (Bucket bucket : buckets.values()) {
            if (bucket.startMs >= firstBucket && bucket.startMs <= lastBucket) {
                total += bucket.snapshotInto(mergedHistogram);
            }
        }

        if (total == 0) {
            return OptionalInt.empty();
        }

        long rank = (long) Math.ceil(total * percentile / 100.0);
        long seen = 0;
        for (Map.Entry<Integer, Long> entry : mergedHistogram.entrySet()) {
            seen += entry.getValue();
            if (seen >= rank) {
                return OptionalInt.of(entry.getKey());
            }
        }

        throw new IllegalStateException("Histogram total did not match aggregated counts");
    }

    private long bucketStart(long timestampMs) {
        return Math.floorDiv(timestampMs, bucketSizeMs) * bucketSizeMs;
    }

    private void advanceLatestBucket(long bucketStart) {
        long current;
        while (bucketStart > (current = latestBucketStart.get())) {
            if (latestBucketStart.compareAndSet(current, bucketStart)) {
                return;
            }
        }
    }

    private void cleanupOldBuckets() {
        long latest = latestBucketStart.get();
        if (latest == Long.MIN_VALUE) {
            return;
        }

        long minRetainedBucket = latest - retentionMs;
        buckets.keySet().removeIf(bucketStart -> bucketStart < minRetainedBucket);
    }

    private static final class Bucket {
        private final long startMs;
        private final ConcurrentHashMap<Integer, LongAdder> countsByLatency = new ConcurrentHashMap<>();

        private Bucket(long startMs) {
            this.startMs = startMs;
        }

        private void add(int latencyMs) {
            countsByLatency.computeIfAbsent(latencyMs, ignored -> new LongAdder()).increment();
        }

        private long snapshotInto(NavigableMap<Integer, Long> destination) {
            long bucketTotal = 0;
            for (Map.Entry<Integer, LongAdder> entry : countsByLatency.entrySet()) {
                long count = entry.getValue().sum();
                if (count > 0) {
                    destination.merge(entry.getKey(), count, Long::sum);
                    bucketTotal += count;
                }
            }
            return bucketTotal;
        }
    }
}
```

## 面试时可以这样开场

可以先说假设：

```text
I will assume we only need to retain a bounded time range, for example the last N minutes or hours. I will also clarify whether approximate answers are acceptable. If exact millisecond windows are required, I can use 1ms buckets or keep raw samples in boundary buckets. For a metrics system, bucket-level approximation is usually acceptable.
```

然后讲设计：

```text
I would partition samples into time buckets. Each bucket stores a histogram of latencyMs to count. Writers update the appropriate bucket using ConcurrentHashMap and LongAdder. Readers aggregate histograms from buckets that overlap the query window, then scan the merged histogram in latency order to find the nearest-rank percentile.
```

再讲并发：

```text
ConcurrentHashMap allows concurrent access to buckets and latency counters. LongAdder reduces contention for high-frequency latency values. The reader gets a weakly consistent snapshot, which is acceptable for monitoring. If strict consistency is required, I would add a read-write lock or snapshot-based design.
```

最后讲 tradeoff：

```text
Smaller buckets give more accurate windows but use more memory and increase query cost. Larger buckets reduce memory and query cost but approximate the query boundary. Retention cleanup prevents unbounded memory growth.
```

## 你现在真正需要理解的 5 个点

不用一次吃完整题。先抓住这 5 个点：

```text
1. addSample 是把一次请求耗时放进某个时间桶。
2. 每个桶里不是 list，而是 latency -> count。
3. getPercentile 会合并窗口内所有桶的 count。
4. percentile 是按 latency 从小到大累计 count，找到第 rank 个。
5. ConcurrentHashMap + LongAdder 是为了支持多个线程同时写。
```

这 5 个点懂了，整题就已经站起来了。

## 常见追问

### 如果 latency 范围很大怎么办？

当前设计按精确 latencyMs 建 histogram。如果 latency 值非常分散，`distinct latency` 会很多。

可以优化为：

```text
固定宽度 latency bucket，例如 0-9ms, 10-19ms
HDR Histogram
DDSketch / t-digest 近似 percentile
```

### 如果要求严格精确窗口怎么办？

可以：

```text
bucketSizeMs = 1
```

或者：

```text
大 bucket + 边界 bucket 保留原始 samples，查询时精确过滤边界。
```

### 如果要求严格线程一致性怎么办？

可以用：

```text
ReadWriteLock
StampedLock
immutable snapshot
```

但要说明代价：更低并发、更高内存或更复杂实现。

### 如果 out-of-order timestamp 来了怎么办？

当前设计允许一定范围内的 out-of-order，只要 sample 还在 retention 内就可以进入旧 bucket。

如果太旧：

```text
bucketStart < latestBucketStart - retentionMs
```

就丢弃。

### 如果数据永远增长怎么办？

不会，因为有 retention cleanup。

```text
只保留 latestBucketStart 往前 retentionMs 范围内的 buckets。
```

## 复习口诀

```text
时间先分桶，桶里做统计。
查询合并桶，累计找排名。
写入用 LongAdder，桶表用 ConcurrentHashMap。
精确看 bucketSize，并发看一致性要求。
```

不放弃是对的。这题难不是因为你不行，而是因为它本来就把好几个系统设计概念叠在一起。拆开之后，每一块都能学会。
