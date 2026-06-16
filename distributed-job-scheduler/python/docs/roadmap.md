# 路线图（Roadmap）

> 本文记录**已完成的里程碑**和**有意推迟的改进**。每个改进项给出动机、做法、tradeoff、为什么现在不做。
> 改进项排序大致按"性价比 / 面试常被追问的程度"。架构细节见 [`ARCHITECTURE.md`](ARCHITECTURE.md)。

---

## 当前里程碑（已完成 · 2026-06-15）

一个**正确、可解释、本地可跑**的最小分布式作业调度器。相对姊妹项目 `task-scheduler`，
本里程碑交付的**增量**就是把"WHEN"升级成"WHERE"——资源感知放置：

| 能力 | 状态 | 代码证据 |
|---|---|---|
| jobs 携带资源需求 `req_cpu` / `req_mem_mb` | ✅ | `sql/001_reset_schema.sql:42` |
| `nodes` 机器队列表（固定容量 + 心跳） | ✅ | `sql/001_reset_schema.sql:62` |
| **派生式空闲容量** `free = total − Σreq(running)`（不存、不漂移、自愈） | ✅ | `core/service.py:359` |
| **资源感知 claim**（贪心装箱 + 容量不超卖 + SKIP LOCKED 防重） | ✅ | `core/service.py:377` |
| node 注册 / 心跳 | ✅ | `core/service.py:310,331` |
| **节点级容错**：心跳超时 → node=dead → 其 run 自动重调度 | ✅ | `core/service.py:261` |
| **调度器 HA**：`pg_try_advisory_lock` 选 leader，崩了自动接管 | ✅ | `core/scheduler.py:42` |
| 继承：tick/materialize、lease+heartbeat+fencing、退避重试、dead-letter | ✅ | `core/service.py:147,441,487` |
| 控制面/数据面 HTTP API + Swagger | ✅ | `core/router.py` |
| node_agent（注册/心跳/资源感知抢/并发执行/上报） | ✅ | `node_agent.py` |
| 本地集群 demo（多进程 + kill 信号注入故障，零 Docker） | ✅ | `README.md` §3-4 |
| 集成测试：容量受限 claim、不重复 claim、容量归还、节点死亡重调度、fencing 拒绝 | ✅ | `test/test_database.py` |

**这个里程碑能证明的不变量**（面试可直接引用）：一个 (job,slot) 只生一条 run；一条 run 只被一台 node 持有；一台 node 绝不超卖容量；过期 worker 无法脏写；节点死亡 = 租约过期（归约为同一条 bounded-retry 路径）。

**已知尚不能做的事**（诚实边界）：无加权公平/抢占；无中心化全局 bin-packing；无运行时资源隔离；Postgres 是 SPOF；catch-up 无上限。下面逐条说怎么补。

---

## 1. 加权 fair-share + 抢占（本项目最该补的"WHERE"深化）

**动机**：当前 claim 是严格 `priority desc`（`service.py:412`），`tenant_id` 只快照、不参与排序。一个租户狂发高优先 job 会**饿死**别的租户；高优先 job 来了也**踢不掉**正在跑的低优先 job。

**做法**：

- **加权公平**：claim 排序引入 `effective_priority = priority + aging(now − scheduled_for)` 防饿死；按 `tenant_id` 做 **DRF（Dominant Resource Fairness）**——多维(CPU+MEM)下让各租户拿到与配额成比例的资源。
- **抢占**：高优先 pending job 装不下时，选低优先的 running run 强制过期其租约（复用 reaper 路径），腾出容量。

**Tradeoff**：aging/DRF 让排序不能纯走索引（表达式排序），可能要物化 `effective_priority` 列定期刷新；抢占要保证被抢的 run 能干净回滚（at-least-once 下它会重投）。

**为什么现在不做**：pull + 严格优先级是**最小可讲清**的放置切片；公平是正交的排序层，建在已有 claim 之上即可，不动数据模型。

---

## 2. 监控指标（Prometheus）

**动机**：当前只有 leader 日志（`scheduler.py:75`）+ `/health` + `GET /nodes` 的实时 free。生产要量化饱和度和**放置效率**。

**做法**：暴露 `/metrics`——队列深度、**集群利用率 `Σreq(running)/Σcapacity`（本项目特有）**、claim 延迟 p95/p99、node 死亡率、dead-letter 率。

**为什么优先**：InfraHack 原则"先加监控再调优"。没有利用率指标，无法证明 §3/§8 的扩展确实必要。

---

## 3. Push 式中心放置 + 全局 bin-packing

**动机**：pull 式 claim 是**局部贪心**——node 各抢各的，拿不到全局最优摆放（碎片、热点）。

**做法**：scheduler（leader）持有集群视图，集中算 run→node 的最优摆放再下发：

- **bin-packing**（塞最满的机器，提利用率）或 **spread**（摊最空的，降热点）二选一/可配。
- 海量 node 时用 **power-of-two-choices**（随机选两台挑较优）避免全局扫描。

**Tradeoff**：中心放置引入 scheduler 状态 + 下发延迟，是潜在瓶颈；pull 式无中心瓶颈。**只有当利用率指标（§2）显示碎片严重时才上**。

**为什么现在不做**：pull 式更简单、天生背压、本地最好测；push 式是"利用率成为真问题"后的演进。

---

## 4. Node 优雅下线（draining）

**动机**：`NodeStatus.DRAINING` 已预留（`core/enums.py`）但未接线。滚动升级/缩容时需要"停止接新活、排空在途"。

**做法**：`POST /nodes/{id}/drain` → status=draining；claim 跳过 draining 节点（`status='active'` 过滤已在，draining 自动被排除）；等其 running 清零后 node 可安全退出。

**Tradeoff**：要处理"drain 超时仍有 run"——超时则按节点死亡走 reap_nodes。小工作量，闭合生命周期。

---

## 5. Catch-up 风暴控制（继承自 task-scheduler）

**动机**：interval job 停机一天后重启，`next_run_at` 落后数百槽，tick 一口气 materialize 几百条 run（`ARCHITECTURE.md §9` 已标隐患）。

**做法**：job 加 `catchup` 策略列——`all`（当前）/ `skip`（只跳到最近未来槽）/ `max_catchup` 上限。

**Tradeoff**：skip 会"漏跑"历史槽，需用户显式选择，默认值谨慎。

---

## 6. 幂等键（让有副作用的任务真正 at-most-once 副作用）

**动机**：at-least-once 下，发邮件/扣款类任务重复执行会出事（`ARCHITECTURE.md §8`）。

**做法**：run 携带稳定幂等键（如 `job_id:scheduled_for`），任务执行副作用前先 `insert ... on conflict do nothing` 一张幂等表。

**Tradeoff**：幂等表是任务方责任，调度器只负责把键稳定传下去。

---

## 7. CRON 调度

**动机**：`one_time + interval` 覆盖不了"每周一 9 点"。

**做法**：新增 `schedule_kind='cron'` + `cron_expr`，tick 用 `croniter` 算下一个 `next_run_at`；CHECK 约束扩展。

**Tradeoff / 难点**：时区与 DST 必须显式定义语义、存 tzid。这是一整块独立复杂度，会冲淡核心放置语义的讲解。

---

## 8. 水平扩展：分片调度 + 表分区

**动机**：单 leader + 单库扫描到顶时。

**做法**：

- **分片 tick/placement**：按 `hash(job_id) % N` 多调度器各管一段；advisory lock 可改为"每分片一把锁"做多活 leader。
- **job_runs 时间分区**：`PARTITION BY RANGE (scheduled_for)`，老分区 DROP 归档。
- **读副本**：`list_jobs`/`list_runs`/`/nodes`/`/metrics` 走 replica。

**Tradeoff**：分区键选 `scheduled_for` 后，查某 job 全历史会跨分区。

---

## 9. Redis 二级派发层

**动机**：极高并发下 PG 的 claim 可能成为热行竞争点 / 写放大点。

**做法**：把就绪 run 放进 Redis（ZSET/list），node 从 Redis 出队；Postgres 退居**持久事实源**，Redis 崩了能从 PG 重建。

**Tradeoff**：引入 Redis↔PG 一致性（dual-write 的影子）。**只在指标（§2）证明 PG claim 成为瓶颈后才做**——经验阈值 ~1万–5万 写 txn/s。这是"复杂度由真实规模逼出"的典型反例守门。

---

## 10. GPU / 多维资源 + 真正的 DRF

**动机**：当前只核算 CPU + MEM。ML/批处理场景要 GPU、磁盘、网络带宽等多维资源。

**做法**：`req_*` 扩成 JSONB 资源向量；派生容量聚合按维度展开；放置用 DRF 做多维公平。

**Tradeoff**：多维装箱是 NP-hard，用启发式（first-fit-decreasing）；fit 过滤的 SQL 变复杂。

---

## 11. 运行时资源**隔离**（cgroups / 容器）

**动机**：当前我们做资源**核算 + 放置**，但**不强制**——一个 job 声明 req_cpu=1 却吃满 8 核，我们拦不住（`ARCHITECTURE.md §1` 已标 out-of-scope）。

**做法**：node_agent 把 handler 放进 cgroup / 容器，按 `req_*` 设硬限额（CPU quota、mem limit、OOM kill）。

**Tradeoff**：这是 executor/runtime 的一整块（Borg/K8s 用 cgroups，本质是另一个子系统）。**核算与隔离要分清**：调度器负责前者，runtime 负责后者。

---

## 12. Node 自动伸缩 + 分析平台（CDC + dbt）

**Node 自动伸缩**：用集群利用率 / 队列深度（§2）作 HPA 信号自动增减 node。背压三连：资源有界(node 容量)→到界(pending 堆积)→预警(利用率 + queue depth)。

**分析平台（CDC + dbt）**：当对调度数据的**分析**撑破 Postgres（长留存、计费/chargeback、BI）时——用 **CDC（Debezium 读 WAL）**把 `jobs/job_runs/nodes` 抽进数仓**而不压主库**，**dbt** 在数仓建分析模型（`fct_job_runs`、`agg_node_utilization_hourly`、`tenant_usage_daily`）。

**为什么能廉价上**：因为 Postgres 是唯一事实源、每个状态转移都进 WAL，CDC 能零 dual-write 忠实重建整条生命周期——这是"DB 当事实源"的红利。**但这是 OLAP 层，不是调度器组件**；且 dbt 是被本调度器调度的一个 job，方向相反。仅当分析需求真实出现才建。

---

## 优先级建议（如果只做三件）

1. **§1 加权 fair-share + 抢占** — 这是本项目"resource allocation"故事的深化，面试最常被追问"怎么保证公平/不饿死"。
2. **§2 监控指标（含集群利用率）** — 没有它，§3/§8/§9 的所有扩展都是盲调。
3. **§4 Node draining** — 小工作量闭合节点生命周期，让"滚动升级/缩容"这个运维故事完整。

（§3 push 式中心放置是性价比很高的第四件：它把"WHERE"从局部贪心升级成全局最优，是和 Borg/K8s 对话的入口。）
