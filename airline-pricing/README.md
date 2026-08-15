# Airline Ticket Price Calculator

Zoox 风格的 OOP live-coding 题：解析航司、里程和舱位，通过可替换的航司定价策略计算票价。

这不是航班预订系统，也不涉及库存、座位分配、支付或行程搜索。题目的重点是：

- 用多态隔离不同航司的定价规则；
- 用简单 Factory 选择策略；
- 新增航司时不修改已有航司的算法；
- 逐行处理大输入，不把数百万条记录全部装入内存；
- 清楚说明输入校验、金额精度和扩展边界。

## Problem

每行输入格式为：

```text
<Airline> <Distance> <SeatClass>
```

例如：

```text
United 150.0 Premium
Delta 60.0 Business
Southwest 1000.0 Economy
LuigiAir 50.0 Business
```

### Operating cost

| Seat class | Operating cost |
| --- | ---: |
| `Economy` | `$0` |
| `Premium` | `$25` |
| `Business` | `$50 + $0.25 × miles` |

### Airline pricing policy

| Airline | Ticket price |
| --- | ---: |
| `Delta` | `$0.50 × miles + operating cost` |
| `United` | `$0.75 × miles + operating cost`; Premium 再加 `$0.10 × miles` |
| `Southwest` | `$1.00 × miles` |
| `LuigiAir` | `max($100, 2 × operating cost)` |

最终金额使用 `ROUND_HALF_UP` 保留两位小数。

样例输出：

```text
152.50
95.00
1000.00
125.00
```

## Interview scope

目标是在 **30–45 分钟**内写出一个完整、可运行、可解释的单文件版本。

### 要实现

- `SeatClass`：限制合法舱位；
- `TicketRequest`：不可变数据对象，并负责解析单行输入；
- `OperatingCostCalculator`：只计算舱位 operating cost；
- `PricingStrategy`：航司策略抽象接口；
- 四个具体航司策略；
- `StrategyFactory`：用 `dict` 完成 airline → strategy 映射；
- `TicketPricingService`：编排解析、operating cost 和最终定价；
- `calculate_all()`：返回 iterator，支持流式输入；
- 一个 `main()` 和少量 `assert`，证明样例及关键边界可运行。

### 暂不实现

- decorator registry 或动态插件发现；
- Singleton、依赖注入框架或配置中心；
- 网络 API、数据库、缓存和并发 worker；
- 汇率、税费、折扣、退款与历史价格版本；
- 为每个简单类拆一个文件。

这些能力可以作为 follow-up 讨论，但不应挤占 live-coding 的核心时间。

## Design

```text
input line
    │
    ▼
TicketRequest.parse()
    │
    ▼
TicketPricingService
    ├── OperatingCostCalculator
    └── StrategyFactory ──► PricingStrategy
                             ├── DeltaPricingStrategy
                             ├── UnitedPricingStrategy
                             ├── SouthwestPricingStrategy
                             └── LuigiAirPricingStrategy
```

职责边界：

- `TicketRequest` 只表达并验证输入，不包含价格规则。
- `OperatingCostCalculator` 只知道舱位，不知道航司。
- 每个 `PricingStrategy` 只知道一个航司的规则。
- `StrategyFactory` 只负责选择策略，不计算价格。
- `TicketPricingService` 只编排调用和统一 rounding。

核心不变量：

- `miles` 必须大于等于 `0`；
- 未知航司、未知舱位和格式错误必须明确失败；
- 所有内部金额运算使用 `Decimal`，只在服务边界统一保留两位；
- 输入一条、输出一条，不保存历史请求；
- 策略对象无状态，可以安全复用。

## Why this design

显式 `dict` Factory 是现场版本最合适的平衡：它已经消除了 service 中的大段 `if/elif`，新增航司只需增加一个策略类和一条映射，同时代码仍能在白板或共享编辑器中快速写完。

decorator Registry 的确能让 Factory 对具体类完全无感，但也引入 import-time registration、模块必须提前加载和重复注册等额外话题。除非面试官明确要求插件式扩展，否则这里先不使用。

`Decimal` 由字符串构造，例如 `Decimal("0.25")`，避免二进制浮点误差。若题目明确允许 `float`，可以先写 `float` 完成主流程，再在总结中说明生产金额应使用 `Decimal` 或整数 cents。

## Suggested 40-minute plan

| Time | Work |
| --- | --- |
| 0–5 min | 复述规则，确认未知输入和 rounding 行为 |
| 5–10 min | 写 `SeatClass`、`TicketRequest.parse()` |
| 10–15 min | 写 `OperatingCostCalculator` |
| 15–27 min | 写 Strategy ABC 和四个策略 |
| 27–32 min | 写 `StrategyFactory` 和 service |
| 32–37 min | 写 generator、样例 `main()` |
| 37–42 min | 跑样例和异常/边界 asserts |
| 42–45 min | 讲复杂度、扩展点和生产差异 |

如果时间紧，优先保证正确的调用链和四条价格规则；不要为了 Registry 或目录拆分牺牲可运行性。

## Validation cases

最低限度应覆盖：

| Case | Expected |
| --- | --- |
| `United 150 Premium` | `152.50` |
| `Delta 60 Business` | `95.00` |
| `Southwest 1000 Economy` | `1000.00` |
| `LuigiAir 50 Business` | `125.00` |
| `LuigiAir 10 Economy` | 最低价 `100.00` |
| `United 0 Economy` | `0.00` |
| unknown airline | `ValueError` |
| unknown seat class | `ValueError` |
| negative miles | `ValueError` |
| missing/extra field | `ValueError` |

## Complexity and scale

设输入行数为 `n`，航司策略数为 `k`：

- 每行解析、查表和计算均为 `O(1)`；
- 总时间复杂度为 `O(n)`；
- generator 流式消费时，除固定服务对象外额外空间为 `O(1)`；
- Factory 映射占用 `O(k)` 空间。

百万行输入的主要风险通常不是公式计算，而是 I/O、日志量和错误处理策略。生产版需要明确 malformed line 是 fail-fast、跳过并计数，还是写入 dead-letter file；面试版采用 fail-fast，行为最清晰。

## Run and quality gates

Python 项目位于 `python/`，使用 `uv`，不需要第三方运行时依赖：

```bash
cd airline-pricing/python
uv run python main.py
uv run ruff check .
uv run ruff format --check .
uv run ty check .
```

完成实现后的通过标准：

- 样例输出与题目完全一致；
- validation cases 全部通过；
- Ruff lint、format check 和 ty type check 通过；
- `calculate_all()` 接收任意 `Iterable[str]` 并惰性产出结果。

## Follow-up discussion

面试官可能继续问：

1. 如何在不重启服务的情况下增加或更新航司规则？
2. 规则来自配置或数据库时，怎样做 schema validation 和版本控制？
3. 一批输入中只有一行坏数据时，是整批失败还是部分成功？
4. 如何保证同一请求在规则更新前后可复算、可审计？
5. 如果策略需要调用外部税费服务，timeout、retry 和并发上限放在哪里？
6. 为什么这里不用 Singleton？策略无状态时复用实例即可，Singleton 不提供额外业务保证。
7. 为什么不把 operating cost 放进各航司策略？独立计算能避免重复，并让舱位规则与航司规则分别演进。

## Current status

本 README 定义了目标设计与验收标准。`python/` 下现有文件仍是学习中的 starter code；实现将由学习者按上述顺序手写，而不是由文档状态暗示为已经完成。
