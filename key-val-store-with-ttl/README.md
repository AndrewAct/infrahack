# Temporal Key-Value Store with TTL

[English](#english) | [中文](#中文)

## English

### Problem

Build an in-memory temporal database with the model:

```text
DB[key][field] = integer value
```

Every operation carries a non-decreasing integer timestamp. Individual writes may have a TTL, deletions must remain queryable as historical tombstones, and callers can read the state visible at an earlier timestamp.

The project is an exercise in the storage pattern behind temporal tables, MVCC, event-sourced state, and snapshot systems: keep ordered versions, resolve the latest eligible version efficiently, and never confuse overwrite semantics with fallback semantics.

### Required behavior

- Basic `set` and `get` operations.
- Conditional `compare_and_set` and `compare_and_delete` mutations.
- Lexicographically ordered `scan` and `scan_with_prefix` queries.
- Per-write TTL with visibility over the half-open interval `[T, T + TTL)`.
- `get_when` historical reads in approximately `O(log V)` for `V` versions of a field.
- Tombstone deletion and recreation without destroying history.
- No resurrection of an older value after a newer TTL version expires.

The core visibility rule is:

```text
Find the latest version with version.timestamp <= query_time.
Only that version determines the answer.
```

If that version is deleted or expired, the field is missing; the lookup must not search backward.

### Two times in a historical read

`get_when(timestamp, key, field, at_timestamp)` separates the query operation's logical time from the historical time being inspected. `timestamp` means “this query is issued now,” while `at_timestamp` means “show me the state that was visible then.” For example:

```python
get_when(100, "user1", "score", at_timestamp=6)
```

means “at logical time 100, inspect `user1.score` as it existed at logical time 6.” Version selection and TTL expiration are evaluated at `at_timestamp=6`; otherwise the present would incorrectly change historical answers. The Python implementation keeps `timestamp` for the uniform operation API and the `at_timestamp <= timestamp` contract, but only `at_timestamp` affects the returned value.

### Repository layout

```text
key-val-store-with-ttl/
├── README.md
└── python/
    └── key-val-store-with-ttl/
        ├── main.py
        ├── test_main.py
        ├── benchmark.py
        ├── pyproject.toml
        └── README.md
```

The current implementation is Python. Future language versions should solve the same semantics using language-idiomatic data structures and tooling rather than translating the Python code line by line.

### Python quick start

```bash
cd python/key-val-store-with-ttl
uv sync
uv run pytest -q
uv run ruff check .
uv run ruff format --check .
uv run ty check .
uv run python main.py
uv run python benchmark.py --operations 200000
```

See the [Python README](python/key-val-store-with-ttl/README.md) for the API, data model, correctness invariants, exact complexity, test coverage, benchmark baseline, and known limits.

### Current scope

Implemented:

- Levels 1–4 of the temporal key-value store;
- 16 focused correctness tests;
- lint, formatting, and static type quality gates;
- a repeatable 200,000-write plus 200,000-historical-read benchmark.

Intentionally out of scope:

- persistence and replication;
- cross-process or thread-safe mutation;
- active expiration workers and automatic history GC;
- schemas and non-integer value types.

---

## 中文

### 问题定义

构建一个内存时间型数据库，数据模型为：

```text
DB[key][field] = 整数值
```

每个操作携带一个非递减整数时间戳。单次写入可以设置 TTL；删除必须以历史 tombstone 保留；调用方能够查询更早时间点可见的状态。

这个项目练习的是时间表、MVCC、事件溯源状态和快照系统背后的共同存储模式：保留有序版本、高效解析最后一个符合条件的版本，并严格区分覆盖语义与回退语义。

### 功能要求

- 基础 `set` 和 `get`；
- 条件操作 `compare_and_set` 和 `compare_and_delete`；
- 按字段名字典序返回的 `scan` 和 `scan_with_prefix`；
- 单次写入 TTL，在半开区间 `[T, T + TTL)` 内可见；
- `get_when` 历史读取，对一个字段的 `V` 个版本达到约 `O(log V)`；
- 使用 tombstone 支持删除和重建，不破坏历史；
- 新 TTL 版本过期后绝不复活更早的旧值。

核心可见性规则是：

```text
找到最后一个 version.timestamp <= query_time 的版本。
只允许这个版本决定查询结果。
```

如果该版本已删除或已过期，则字段不存在；查询不能继续向前搜索。

### 历史读取中的两个时间

`get_when(timestamp, key, field, at_timestamp)` 将查询操作本身的逻辑时间与被查看的历史时间分开。`timestamp` 表示“现在发起这次查询”，`at_timestamp` 表示“查看当时可见的状态”。例如：

```python
get_when(100, "user1", "score", at_timestamp=6)
```

它表示“在逻辑时间 100，查看逻辑时间 6 时的 `user1.score`”。版本选择和 TTL 过期都必须根据 `at_timestamp=6` 判断，否则当前时间会错误地改变历史答案。Python 实现为了统一操作 API 和表达 `at_timestamp <= timestamp` 约定而保留 `timestamp`，但只有 `at_timestamp` 会影响返回值。

### 仓库结构

```text
key-val-store-with-ttl/
├── README.md
└── python/
    └── key-val-store-with-ttl/
        ├── main.py
        ├── test_main.py
        ├── benchmark.py
        ├── pyproject.toml
        └── README.md
```

当前实现语言为 Python。未来其他语言版本应使用该语言惯用的数据结构和工具实现相同语义，而不是逐行翻译 Python 代码。

### Python 快速开始

```bash
cd python/key-val-store-with-ttl
uv sync
uv run pytest -q
uv run ruff check .
uv run ruff format --check .
uv run ty check .
uv run python main.py
uv run python benchmark.py --operations 200000
```

完整 API、数据模型、正确性不变量、精确复杂度、测试覆盖、benchmark 基线和已知限制见 [Python README](python/key-val-store-with-ttl/README.md)。

### 当前范围

已完成：

- 时间型 key-value store Level 1–4；
- 16 个聚焦正确性测试；
- lint、格式和静态类型质量门；
- 可重复运行的 20 万次写入 + 20 万次历史读取 benchmark。

有意不实现：

- 持久化与复制；
- 跨进程或线程安全写入；
- 主动过期线程与自动历史 GC；
- schema 和非整数 value 类型。
