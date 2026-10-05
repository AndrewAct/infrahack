# Temporal Key-Value Store with TTL — Python

[English](#english) | [中文](#中文)

## English

### Overview

`TemporalKVStore` is a small, dependency-free in-memory database with the model:

```text
DB[key][field] = integer value
```

It supports multiple fields per record, conditional mutations, lexicographically ordered scans, per-write TTL, tombstone deletion, and historical reads. The implementation is intentionally compact enough to understand and hand-write while preserving the core invariants of a temporal storage component.

### API

```python
set(timestamp, key, field, value)
get(timestamp, key, field)
compare_and_set(timestamp, key, field, expected_value, new_value)
compare_and_delete(timestamp, key, field, expected_value)
scan(timestamp, key)
scan_with_prefix(timestamp, key, prefix)
set_with_ttl(timestamp, key, field, value, ttl)
compare_and_set_with_ttl(timestamp, key, field, expected_value, new_value, ttl)
get_when(timestamp, key, field, at_timestamp)
```

Operation timestamps are assumed to be non-decreasing. When successful operations share a timestamp, append order breaks the tie, so the last successful operation at that timestamp is visible.

### The two timestamps in `get_when`

`get_when` contains two logical times with different roles:

```python
get_when(timestamp, key, field, at_timestamp)
```

| Parameter | Meaning |
| --- | --- |
| `timestamp` | The logical time when this query operation is issued: the caller's “now” |
| `at_timestamp` | The historical logical time whose state the caller wants to inspect |

For example, suppose the write history is:

```python
store.set(1, "user1", "score", 10)
store.set_with_ttl(5, "user1", "score", 20, ttl=3)
```

The resulting timeline is:

```text
[1,5) -> 10
[5,8) -> 20
[8,∞) -> missing
```

A historical query made at logical time `100` can inspect any earlier point:

```python
store.get_when(100, "user1", "score", at_timestamp=4)  # 10
store.get_when(100, "user1", "score", at_timestamp=6)  # 20
store.get_when(100, "user1", "score", at_timestamp=8)  # None
```

TTL visibility must be evaluated against `at_timestamp`, not `timestamp`. Although the value `20` is expired at the caller's time `100`, it was visible at historical time `6`. Using `timestamp=100` for the TTL check would incorrectly let the present overwrite the historical answer.

In this implementation, `timestamp` does not affect the returned value:

```python
def get_when(self, timestamp, key, field, at_timestamp):
    del timestamp
    return self._resolve(key, field, at_timestamp)
```

It remains in the API because every operation has a logical operation timestamp and the contract assumes `at_timestamp <= timestamp`. A larger system could also use it for validation, auditing, authorization, or retention rules. Here, only `at_timestamp` determines historical visibility.

### Data model

```text
history: dict[key, dict[field, list[_Version]]]
```

```python
@dataclass(slots=True, frozen=True)
class _Version:
    timestamp: int
    value: int | None
    expires_at: int | None
```

Each `(key, field)` owns one append-only version list ordered by timestamp. `value=None` is an unambiguous tombstone because public writes only accept integers. A permanent write has `expires_at=None`; a TTL write made at `T` stores the absolute boundary `expires_at=T+ttl`.

The public API accepts a duration because that is natural for callers. The stored version keeps the absolute expiration boundary because reads need to answer only:

```text
query_time >= expires_at ? expired : visible
```

### Visibility invariant

To resolve a value at time `T`:

1. Use `bisect_right` to find the last version whose `timestamp <= T`.
2. Inspect only that version.
3. Return missing if it is a tombstone.
4. Return missing if `expires_at is not None and T >= expires_at`.
5. Otherwise return its integer value.

An expired or deleted latest version never falls back to an older overwritten value:

```text
t=1: set f=10
t=5: set_with_ttl f=20 ttl=3

[1,5) -> 10
[5,8) -> 20
[8,∞) -> missing, not 10
```

### Design decisions

- **One source of truth:** the store keeps history only, rather than duplicating `current` and `history`. This removes a write-path consistency invariant at the cost of an `O(log V)` current read.
- **Immutable versions:** frozen, slotted dataclasses make historical records easy to reason about and reduce per-object overhead.
- **Lazy expiration:** reads interpret TTL boundaries; there is no background expiry worker.
- **Historical tombstones:** deletion appends a version instead of destroying earlier state.
- **No failed-mutation records:** failed CAS/CAD operations return without appending history.

### Complexity

Let `V` be the number of versions for one field, `F` the number of fields under one key, and `Vᵢ` the versions for field `i`.

| Operation | Time complexity | Notes |
| --- | --- | --- |
| `set`, `set_with_ttl` | amortized `O(1)` | Append one version |
| `get`, `get_when` | `O(log V)` | Binary search one version list |
| CAS, TTL CAS, CAD | `O(log V)` | Resolve, then conditionally append |
| `scan` | `O(F log F + Σ log Vᵢ)` | Sort fields and resolve each field |
| `scan_with_prefix` | `O(F log F + Σ log Vᵢ)` worst case | Current implementation sorts all fields before filtering |

Memory is `O(H)`, where `H` is the number of successful writes and deletions retained in history.

### Validation

The 16 focused tests cover:

- overwrite and historical lookup;
- missing reads and failed conditional mutations;
- TTL half-open boundaries;
- no resurrection after TTL overwrite;
- delete and recreate;
- failed CAS and failed TTL CAS creating no history;
- CAS/CAD/TTL CAS after expiration;
- replacement of TTL and non-TTL versions;
- scans excluding expired and deleted values;
- scan ordering and prefix filtering;
- same-timestamp tie-breaking;
- mixed multi-version timelines.

Run all quality gates:

```bash
uv sync
uv run python main.py
uv run pytest -q
uv run ruff check .
uv run ruff format --check .
uv run ty check .
```

### Benchmark

`benchmark.py` creates the deepest useful version chain: all writes target one hot field, followed by one historical read at every timestamp. This exercises append throughput, binary-search lookup, and retained-history memory.

```bash
uv run python benchmark.py --operations 200000
```

A local reference run on August 20, 2026 produced:

```text
operations: 200,000
writes: 0.090s (2,213,531 ops/s)
historical reads: 0.176s (1,135,870 ops/s)
peak traced memory: 18.3 MiB
```

These numbers are a regression baseline from one machine, not a service SLO. Compare results on the same machine and Python version.

### Intentional limits and next steps

- History grows without bound because arbitrary historical reads remain supported. Safe GC requires an explicit oldest-readable timestamp or retention policy.
- The store is not thread-safe. A concurrent version would need atomic resolve-and-append semantics, likely with per-field or per-key locking.
- Prefix scans inspect all fields. A sorted field index or ordered map would improve sparse-prefix workloads.
- Input contracts such as non-decreasing timestamps and valid TTL values are assumed rather than validated.
- Persistence, replication, schema evolution, and cross-process coordination are out of scope.

---

## 中文

### 概览

`TemporalKVStore` 是一个无运行时依赖的小型内存数据库，其数据模型为：

```text
DB[key][field] = 整数值
```

它支持一条记录包含多个字段、条件更新、按字典序扫描、单次写入 TTL、墓碑删除和历史时间点读取。实现刻意保持紧凑，既方便理解和手写，又保留了时间型存储组件最重要的正确性不变量。

### API

```python
set(timestamp, key, field, value)
get(timestamp, key, field)
compare_and_set(timestamp, key, field, expected_value, new_value)
compare_and_delete(timestamp, key, field, expected_value)
scan(timestamp, key)
scan_with_prefix(timestamp, key, prefix)
set_with_ttl(timestamp, key, field, value, ttl)
compare_and_set_with_ttl(timestamp, key, field, expected_value, new_value, ttl)
get_when(timestamp, key, field, at_timestamp)
```

系统假设操作时间戳非递减。如果多个成功操作拥有相同时间戳，则以追加顺序打破平局：该时间戳下最后成功的操作可见。

### `get_when` 中的两个时间戳

`get_when` 包含两个职责不同的逻辑时间：

```python
get_when(timestamp, key, field, at_timestamp)
```

| 参数 | 含义 |
| --- | --- |
| `timestamp` | 本次查询操作发起时的逻辑时间，也就是调用方的“现在” |
| `at_timestamp` | 调用方真正想查看状态的历史逻辑时间 |

例如，假设写入历史为：

```python
store.set(1, "user1", "score", 10)
store.set_with_ttl(5, "user1", "score", 20, ttl=3)
```

对应时间线为：

```text
[1,5) -> 10
[5,8) -> 20
[8,∞) -> 不存在
```

在逻辑时间 `100` 发起的历史查询可以查看任意更早的时间点：

```python
store.get_when(100, "user1", "score", at_timestamp=4)  # 10
store.get_when(100, "user1", "score", at_timestamp=6)  # 20
store.get_when(100, "user1", "score", at_timestamp=8)  # None
```

TTL 可见性必须根据 `at_timestamp` 判断，而不是根据 `timestamp` 判断。虽然值 `20` 在调用方的时间 `100` 已经过期，但它在历史时间 `6` 仍然可见。如果使用 `timestamp=100` 判断 TTL，“现在”就会错误地污染历史答案。

在当前实现中，`timestamp` 不影响返回值：

```python
def get_when(self, timestamp, key, field, at_timestamp):
    del timestamp
    return self._resolve(key, field, at_timestamp)
```

它仍然保留在 API 中，因为每个操作都有自己的逻辑操作时间，而且接口约定 `at_timestamp <= timestamp`。更完整的系统还可以使用它执行输入校验、审计、鉴权或历史保留策略。在本实现中，只有 `at_timestamp` 决定历史可见性。

### 数据模型

```text
history: dict[key, dict[field, list[_Version]]]
```

```python
@dataclass(slots=True, frozen=True)
class _Version:
    timestamp: int
    value: int | None
    expires_at: int | None
```

每个 `(key, field)` 拥有一条按时间戳排序、只追加的版本链。因为公开写入 API 只接受整数，所以 `value=None` 可以无歧义地表示 tombstone。永久写入使用 `expires_at=None`；在 `T` 时刻发生的 TTL 写入保存绝对边界 `expires_at=T+ttl`。

公开 API 接收 TTL 时长，因为这对调用方最自然；内部版本保存绝对过期边界，因为读取路径只需要回答：

```text
query_time >= expires_at ? 已过期 : 可见
```

### 可见性不变量

解析时间 `T` 的值时：

1. 使用 `bisect_right` 找到最后一个满足 `timestamp <= T` 的版本；
2. 只检查这个版本；
3. 如果它是 tombstone，则返回不存在；
4. 如果 `expires_at is not None and T >= expires_at`，则返回不存在；
5. 否则返回它的整数值。

最新版本即使已过期或被删除，也绝不会向前回退并暴露被覆盖的旧值：

```text
t=1: set f=10
t=5: set_with_ttl f=20 ttl=3

[1,5) -> 10
[5,8) -> 20
[8,∞) -> 不存在，而不是 10
```

### 设计取舍

- **单一事实来源：** 只保存 history，不同时维护 `current` 和 `history`。代价是当前读取也需要 `O(log V)`，收益是写路径没有两份状态同步的不变量。
- **不可变版本：** frozen + slots dataclass 让历史记录更容易推理，并减少单对象开销。
- **惰性过期：** 读取时解释 TTL 边界，不启动后台过期线程。
- **历史墓碑：** 删除时追加版本，不破坏更早的状态。
- **失败操作不留记录：** 失败的 CAS/CAD 直接返回，不向历史追加无意义版本。

### 复杂度

设一个字段有 `V` 个版本，一个 key 下有 `F` 个字段，第 `i` 个字段有 `Vᵢ` 个版本。

| 操作 | 时间复杂度 | 说明 |
| --- | --- | --- |
| `set`、`set_with_ttl` | 摊还 `O(1)` | 追加一个版本 |
| `get`、`get_when` | `O(log V)` | 在单个版本链上二分查找 |
| CAS、TTL CAS、CAD | `O(log V)` | 先解析，再条件追加 |
| `scan` | `O(F log F + Σ log Vᵢ)` | 排序字段并逐字段解析 |
| `scan_with_prefix` | 最坏 `O(F log F + Σ log Vᵢ)` | 当前实现先排序全部字段，再过滤前缀 |

空间复杂度为 `O(H)`，其中 `H` 是历史中保留的成功写入和删除总数。

### 验证

16 个聚焦测试覆盖：

- 覆盖写和历史查询；
- 缺失值和失败的条件操作；
- TTL 半开区间边界；
- TTL 覆盖后不复活旧值；
- 删除后重建；
- 失败 CAS 和失败 TTL CAS 不创建历史；
- 过期后的 CAS/CAD/TTL CAS；
- TTL 与非 TTL 版本相互覆盖；
- scan 排除过期和删除值；
- scan 排序和字段名前缀过滤；
- 相同时间戳的顺序规则；
- 多种操作混合的长历史时间线。

运行全部质量门：

```bash
uv sync
uv run python main.py
uv run pytest -q
uv run ruff check .
uv run ruff format --check .
uv run ty check .
```

### 基准测试

`benchmark.py` 构造最深的有效版本链：所有写入都指向同一个热点字段，然后对每个历史时间点执行一次读取。它同时覆盖追加吞吐、二分查询和历史保留内存。

```bash
uv run python benchmark.py --operations 200000
```

2026 年 8 月 20 日的一次本机参考结果：

```text
operations: 200,000
writes: 0.090s (2,213,531 ops/s)
historical reads: 0.176s (1,135,870 ops/s)
peak traced memory: 18.3 MiB
```

这些数字只是单台机器上的回归基线，不是服务 SLO。性能比较应使用相同机器和 Python 版本。

### 有意保留的限制与下一步

- 为支持任意历史读取，history 会无限增长。安全 GC 需要明确最早可读时间戳或保留策略。
- 当前实现不是线程安全的。并发版本必须保证“解析 + 追加”的原子性，可能采用每字段或每 key 锁。
- 前缀扫描仍会检查全部字段；稀疏前缀场景可使用有序字段索引或有序映射优化。
- 非递减时间戳、合法 TTL 等输入约束由调用方保证，目前不主动校验。
- 持久化、复制、schema 演进和跨进程协调不在本项目范围内。

## Current status / 当前状态

Levels 1–4 are implemented. Tests, lint, formatting, type checking, the worked example, and the 200,000-operation benchmark all pass.

Level 1–4 已完成。测试、lint、格式检查、类型检查、示例程序以及 20 万操作 benchmark 均通过。
