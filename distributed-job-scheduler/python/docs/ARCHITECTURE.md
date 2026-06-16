# 分布式作业调度器 — 架构详解

> 目标读者：未来 60 分钟内要把这套设计从头讲清楚的你自己。
> 每一节都对应真实代码文件，括号里给出文件:行号，方便回查。
> 它的姊妹项目 `task-scheduler` 解决 **WHEN**；本项目在它之上解决 **WHERE**。

---

## 0. 一句话定义

> 一个把作业**调度并执行到一个机器队列(fleet)上**的分布式调度器：给定每个 node 的 **CPU/MEM 容量**和每个 job 的**资源需求**，做**资源感知的放置(resource-aware placement)**——决定*什么在哪台机器上跑*，并提供负载均衡、租户隔离的雏形、**节点级**容错，以及用 **Postgres advisory lock** 做的调度器 leader 选举。**全程 Postgres 当唯一事实源——无 Redis、无 ZooKeeper/etcd、无 Docker。**

---

## 1. 问题陈述与范围

**问题**：设计一个能把大量作业调度并执行到一个**大型机器集群**上的系统，聚焦作业分发、负载均衡、容错，以及分布式环境下的**作业优先级与资源分配**。

> **范围边界（和 `task-scheduler` 的分界）**：task-scheduler 解决 **WHEN**——一个时间触发器 + 持久化队列，worker 是无差别消费者。本项目在它之上加了**唯一**一根脊椎：**`nodes` 容量表 + 资源感知放置**。这才是"resource allocation in a distributed environment"。

**做了什么（当前功能）**：

- jobs 携带**资源需求** `req_cpu` / `req_mem_mb`（`sql/001_reset_schema.sql:42`）
- `nodes` 表：机器队列，每台带固定容量（`sql/001_reset_schema.sql:62`）
- **资源感知 claim**：node 只抢*装得下自己空闲容量*的 run（`core/service.py:377`）
- **派生式空闲容量**：`free = total − Σreq(running)`，不存、不漂移（`core/service.py:359`）
- **节点级容错**：node 心跳超时 → 标 dead → 其 run 自动重调度（`core/service.py:261`）
- **调度器 HA**：`pg_try_advisory_lock` 选 leader，崩了自动接管（`core/scheduler.py:42`）
- 继承 task-scheduler 的全部：materialize/tick、lease+heartbeat+fencing、退避重试、dead-letter

**有意不做（out of scope，面试要主动说）**：

| 不做 | 为什么 |
|---|---|
| 运行时资源**隔离**（cgroups/容器限额） | 我们做调度层的资源**核算 + 放置**，不做内核级**隔离**——那是 executor/runtime 的职责。这是 "resource allocation" 两种含义的关键区分 |
| 加权 fair-share / 抢占 / 中心化 bin-packing | `tenant_id` 已快照，分组键就位；加权排序 + push 式放置是下一层（见 roadmap）。当前：pull 式、严格优先级 |
| Redis 二级派发层 | 只在实测 PG claim QPS 成瓶颈后才值得（见 §12 与 roadmap）|
| CRON / 精确一次 / RBAC | 与 task-scheduler 同样的取舍，正交于核心 |

---

## 2. 三个平面（架构总览）

```
            Client / User
                 │ submit job / query status
                 ▼
            API Server   (无状态, 可多副本)        core/main.py
                 │
                 ▼
   ┌─────────────────────────────────────────────┐
   │                 PostgreSQL                   │  ← 唯一事实源
   │   jobs │ job_runs │ nodes                    │
   └─────────────────────────────────────────────┘
        ▲           ▲                  ▲
 advisory│   tick /  │   placement      │ heartbeat / claim / report
 lock(HA)│  reap_*   │   (WHERE)        │
   ┌─────┴───────────┴────┐      ┌──────┴───────────────────────┐
   │   Scheduler Service   │      │   Node Agents (机器队列)      │
   │   (仅 leader 在跑)     │      │   node-a  node-b  ...         │
   │  · tick: due → run    │      │  · 注册并周期上报 free 容量    │
   │  · placement 由 claim  │      │  · 抢/接装得下的 run          │
   │  · reap_nodes: 收割死节点      │  · 执行 + 续租 + 上报结果      │
   └───────────────────────┘      └──────────────────────────────┘
```

**三个平面，读写特征/扩展方式/鉴权各不同**：

- **控制面**（`JobService` `core/service.py:34`）：低频写、强一致，CRUD job 定义 + 资源需求。
- **时间 + 容错面**（`SchedulerService` `core/service.py:137`）：高频小扫描、幂等，且**只在 leader 上跑**——tick(物化)、reap_runs(收割丢失的 run)、reap_nodes(收割死节点)。
- **数据 + 放置面**（`NodeService` `core/service.py:300`）：高并发抢占、要防重、**带资源约束**——node 注册/心跳、资源感知 claim、上报结果。

---

## 3. 技术栈选择与 tradeoff

| 技术 | 职责 | 为什么选它 | 否决的备选 | Tradeoff |
|---|---|---|---|---|
| **PostgreSQL** | 唯一事实源 + 队列 + **集群资源状态** | 一个系统搞定队列、放置、容量核算、leader 选举；事务保证容量不超卖 | Redis(派发) + ZK/etcd(协调) | 吞吐上限低于专用系统，但换来强一致、零 dual-write、可查询 |
| **派生式容量** | `free = total − Σreq(running)` | 不存 free 列 → **不可能漂移**；run 离开 running 容量自动归还，零 bookkeeping | 存 `cpu_free` 列、增减维护 | 每次 claim 多一次按 node 的聚合，但有 `idx_runs_node` 部分索引，廉价 |
| **pull 式资源感知 claim** | node 抢装得下的 run | 天生背压(node 满了就抢不到)、无中心瓶颈、本地可测 | push 式中心放置(Borg 式) | 拿不到全局最优 bin-packing/抢占——是下一层(roadmap) |
| **`pg_try_advisory_lock` 选 leader** | 调度器 HA | session 锁随连接存活，**崩了自动释放** → standby 接管；零额外系统 | ZooKeeper / etcd / Raft | 绑在单库上(库挂全挂)，但本就是单库事实源 |
| **node_agent 直连 DB** | worker 进程 | 多进程 + `kill` 信号 = 真·节点故障注入，本地零 Docker 起集群 | 走 HTTP 数据面 | 放弃一层 API 抽象，换来最简单的分布式测试台 |
| psycopg3 / FastAPI / Pydantic v2 | 同 task-scheduler | 显式 SQL、类型化、双层校验 | ORM / Flask | 同 task-scheduler |

**一句话核心决策**：**用一张 `nodes` 表 + 派生容量，把"DB 当队列"升级成"DB 当集群资源状态机"。**

---

## 4. 数据模型 — 三张表

### 4.1 为什么是三张表

- `jobs` = **定义 / 意图 + 资源需求**，可变（`sql/001_reset_schema.sql:35`）。
- `job_runs` = **每一次具体执行实例**，事实 / 历史（`sql/001_reset_schema.sql:77`）。
- `nodes` = **机器队列**，每台带固定容量——**这张表是本项目区别于 task-scheduler 的唯一脊椎**（`sql/001_reset_schema.sql:62`）。

> 面试金句：**"task-scheduler 只有'定义'和'执行实例'两张表；我加的第三张 `nodes` 把'调度'从'何时跑'升级成'在容量受限的机器上谁来跑'。少了它，这就只是个开了多副本的 task-scheduler。"**

### 4.2 `nodes` 表 + **派生容量**（最重要的设计洞见）

```sql
create table public.nodes (
    id text primary key,                  -- agent 自选 id (如 host:pid)
    cpu_total integer, mem_total_mb integer,    -- 固定容量
    status node_status,                   -- active / draining / dead
    last_heartbeat_at timestamptz, ...    -- 心跳；沉默即视为 dead
);
```

**关键决策：空闲容量不存，而是派生**（`core/service.py:359`）：

```sql
free_cpu = n.cpu_total - coalesce(sum(req_cpu) over this node's running runs, 0)
```

**为什么派生而不存 `cpu_free` 列**：

- **不可能漂移**：free 永远等于 `total − Σreq(running)`，按定义成立。存列就要在 claim/complete/fail/reap **四处**手动增减，任何一处漏掉就永久错账。
- **自愈**：run 一旦离开 `running`（成功/失败/dead/被收割），它就不再进聚合 → **容量瞬间自动归还**，零代码。
- 代价：每次 claim 多一次按 node 的 `sum`，但走 `idx_runs_node`（部分索引，只索引 running 行，`sql/001_reset_schema.sql:110`），一个 node 顶多几十~几百条 running，廉价。

> 面试追问预演："并发 claim 会不会把容量超卖？"
> 答："不会。claim 先 `SELECT ... FROM nodes WHERE id=%s FOR UPDATE`（`core/service.py:387`）锁住**这台 node 的行**，序列化它自己的容量视图；不同 node 锁不同行，互不阻塞；同一条 run 被两台 node 抢由 `SKIP LOCKED` 兜底。容量约束 + 行锁 = 不超卖。"

### 4.3 `jobs` 表新增的两列（`sql/001_reset_schema.sql:42`）

| 列 | 为什么 |
|---|---|
| `req_cpu` / `req_mem_mb` | **资源需求**。claim 的 fit 过滤就靠它；快照进 run 后执行输入不可变 |
| `tenant_id`（default `'default'`）| **fair-share 分组键**。当前只快照、不加权排序；加权层是 roadmap §1 |

### 4.4 `job_runs` 表相对 task_runs 的增量（`sql/001_reset_schema.sql:77`）

| 列 | 为什么 |
|---|---|
| `req_cpu` / `req_mem_mb` | **从 job 快照**的资源需求 → claim 只查一张表就能做 fit 判断 |
| `tenant_id` | 从 job 快照，用于 claim 时按租户分组（fair-share 雏形）|
| `assigned_node_id` → `nodes(id)` `on delete set null` | **这条 run 在哪台机器上跑**。它既是历史，也是派生容量聚合的 group key |
| `lease_token` / `lease_expires_at` | fencing token + 租约，同 task-scheduler |

`unique (job_id, scheduled_for)`（`sql/001_reset_schema.sql:100`）+ `on conflict do nothing` 让 tick 幂等，根基不变。

### 4.5 索引设计

| 索引 | 定义 | 服务谁 | 要点 |
|---|---|---|---|
| `idx_jobs_due` | `(next_run_at) WHERE active` | tick 的 due 扫描 | 部分索引，代价 ∝ 真正 due 的行数（`:59`）|
| `idx_runs_claimable` | `(priority desc, available_at) WHERE pending` | claim 排序 | 列序 = `ORDER BY`，索引直接出有序结果；**资源 fit 是额外 WHERE 过滤**（`:105`）|
| `idx_runs_node` | `(assigned_node_id) WHERE running` | **派生容量聚合** + 节点死亡重调度 | 本项目特有；让 `Σreq per node` 廉价（`:110`）|
| `idx_runs_lease` | `(lease_expires_at) WHERE running` | reap_runs 扫过期租约 | 同 task-scheduler（`:108`）|
| `idx_nodes_live` | `(last_heartbeat_at) WHERE active` | reap_nodes 找沉默节点 | 范围扫，找心跳落后的 active node（`:73`）|

---

## 5. 控制流 / 数据流（一条 job 走完）

```
1. PUT /nodes/{id}                  → node 注册容量, status=active        (service.py:310)
2. POST /jobs                       → jobs 插一行(含 req_cpu/mem), next_run_at=now (service.py:40)
3. [leader, 每 1s] tick()           → due job → job_runs(pending), 快照 req_*/tenant (service.py:147)
4. node_agent 周期 claim()          → 锁 node 行, 算 free=total−Σreq(running),
                                       贪心抢"装得下"的最高优先 run, 逐条扣本地 free,
                                       SKIP LOCKED 防重, 打 lease_token        (service.py:377)
5. [执行中] heartbeat_run()         → 凭 token 续租; node_agent 同时 node 心跳   (service.py:441)
6a. complete()                      → 凭 token: succeeded; run 离开 running → 容量自动归还 (service.py:460)
6b. fail()                          → 凭 token: 可重试→pending+退避; 否则→dead   (service.py:487)
7. [node 挂, 不心跳] reap_nodes()    → 心跳超时 → node=dead + 强制过期其所有 run 的租约 (service.py:261)
                       reap_runs()  → 过期租约 → 重投 / dead-letter            (service.py:205)
```

**两层锁 + 一层租约（面试高频）**：

- **claim 里 node 行的 `FOR UPDATE`**：序列化**同一台 node** 的容量视图，防超卖。极短。
- **claim 里 run 的 `FOR UPDATE SKIP LOCKED`**：让并发 node 拿不相交批次，不重不阻塞。极短。
- **`lease_expires_at` 租约**：应用层逻辑锁，覆盖整个执行时长，靠心跳续期，过期由 reaper 收回。

---

## 6. 核心机制深挖：资源感知放置（claim）

放置就是一段**贪心装箱**（`core/service.py:377`）：

```
锁住本 node 行 (FOR UPDATE)
free_cpu, free_mem = total − Σreq(本 node running runs)        ← 派生
while 已抢 < max_runs:
    run = 最高优先的 pending run, 满足 req_cpu ≤ free_cpu AND req_mem ≤ free_mem
          ORDER BY priority desc, available_at  FOR UPDATE SKIP LOCKED LIMIT 1
    if 没有: break
    抢它 (pending→running, assigned_node_id, lease_token)
    free_cpu −= run.req_cpu;  free_mem −= run.req_mem      ← 本地递减, 不重新聚合
```

- **资源分配** = `req ≤ free` 这个 WHERE 过滤 + 抢一条扣一条。
- **负载均衡自然发生**：满的 node 算出来 free 不够，活就流向空的 node——pull 模型让 node 自己当流量阀。
- **为什么逐条贪心而不是一条 SQL 批量取**：批量取要解决"累积装箱"（10 条各 req=1，free=4 只能取 4），用 window 函数能写但难懂；逐条贪心**显然正确、可解释**，符合"能讲清楚"的原则。

> 面试金句：**"放置 = 在派生出的空闲容量里，按优先级贪心装箱。容量是派生的所以不会错账，装箱是逐条的所以不会超卖，SKIP LOCKED 让多 node 并发不重。"**

---

## 7. 调度器 HA：advisory lock 选 leader（为什么不用 ZK/etcd）

`BackgroundScheduler` 每个副本都跑，但**只有持锁者真正 tick/reap**（`core/scheduler.py:42`）：

- 每副本在一条**专用 session 连接**上 `select pg_try_advisory_lock(LEADER_LOCK_KEY)`（`core/scheduler.py:51`，key 在 `core/database.py:19`）。
- **锁随连接存活**：leader 崩了 → 连接断 → 锁**自动释放** → standby 下一拍抢到 → 接管。这就是 failover，零额外系统。
- standby 抢不到锁就空转，不做任何事。
- tick/reap **幂等**，所以即便短暂双 leader（极端时序）也无害。

> ⚠️ 必须 **session 模式**连接（直连/Session Pooler），**transaction pooler 会让 advisory lock 失效**（`core/database.py:75` 的 `connect_session` 专门开 autocommit session 连接）。

> 面试金句：**"我用一行 `pg_try_advisory_lock` 换掉了整个 ZooKeeper。锁的生命周期 = 连接的生命周期，所以'leader 死亡检测'和'锁释放'是同一件事，免费的 failover。"**

---

## 8. 正确性不变量（Correctness Invariants）

1. **一个 (job, slot) 最多一条 run** — `unique(job_id, scheduled_for)` + `ON CONFLICT DO NOTHING`，tick 幂等。
2. **一条 run 同一时刻最多被一个 node 持有** — claim 的 `FOR UPDATE SKIP LOCKED` 原子 pending→running。
3. **一个 node 绝不超卖容量** — claim 锁 node 行 + `req ≤ free` 贪心扣减；free 派生自 running 集合，不漂移。
4. **过期租约的 worker 不能再改 run** — complete/fail/heartbeat 的 WHERE 都带 `lease_token=%s and lease_expires_at>now()`（fencing）。
5. **at-least-once**：每个到期的槽至少执行一次；node 或 worker 挂了由 reaper 重投。任务应幂等。
6. **重试有界**：`attempt < max_attempts` 才重试，否则 dead-letter。
7. **节点死亡 = 租约过期**：reap_nodes 把节点故障**归约**成已有的 run 级租约过期，复用同一条 bounded-retry 路径。

---

## 9. 失败模式（Failure Modes）

| 场景 | 系统行为 | 由什么保证 |
|---|---|---|
| 重复 tick / 多副本 tick | 同槽不重复生 run | `unique(job_id, scheduled_for)` + advisory lock 限单 leader |
| 两台 node 同时 claim 同一 run | 各拿不相交批次 | `FOR UPDATE SKIP LOCKED` |
| node 容量被并发 claim 超卖 | 不会 | 锁 node 行 + 派生 free + 贪心扣减 |
| worker 进程崩（node 还活） | 租约过期 → 重投/dead | `idx_runs_lease` + `reap_runs` |
| **整台 node 挂** | 心跳超时 → node=dead → 其 run 全部重调度到别处 | `reap_nodes` 强制过期租约 → `reap_runs` 收尾（`service.py:261`）|
| worker 假死后"复活"提交 | 旧 token 被拒，不污染新 run | fencing `lease_token` |
| leader 崩 | standby 抢到 advisory lock 接管 | `pg_try_advisory_lock` 随连接释放 |
| 任务持续失败 | 退避重试到上限 → dead-letter | `attempt/max_attempts` + `compute_backoff`（`service.py:28`）|
| 调度器落后（积压）| interval job 下次 tick 继续 catch-up | `next_run_at += interval`（**catch-up 上限未做，见 roadmap**）|
| **Postgres 挂** | 全停 | **唯一 SPOF**——生产要 HA Postgres + 副本（见 roadmap）|

---

## 10. 可观测性（Observability）

**现状**：leader 在有动作时打 `leader cycle materialized=.. requeued=.. dead=.. dead_nodes=.. expired_leases=..`（`core/scheduler.py:75`）；`/health` 探活；`GET /nodes` 实时返回每台 node 的派生 `cpu_free/mem_free_mb`（`core/service.py:353`）——这是观察放置的窗口。

**该补的核心指标（roadmap）**：

| 指标 | 类型 | 为什么关键 |
|---|---|---|
| 队列深度 `count(*) where status='pending'` | gauge | 饱和信号；集群是否跟得上 |
| **集群利用率** `Σreq(running) / Σcapacity` | gauge | **本项目特有**：放置效率/碎片 |
| claim 延迟（scheduled_for → started_at） | histogram | 端到端调度延迟 p95/p99 |
| node 死亡率（reap_nodes/s） | counter | 集群健康度 |
| dead-letter 速率 | counter | 任务质量 / 告警源 |

---

## 11. 为什么不用 Redis / Celery / MQ（30 秒版）

- **不用 Redis**：排序、选 due、不重复 claim、容量核算，Postgres 部分索引 + `SKIP LOCKED` + 派生聚合全包；Redis 只解决 dispatch 热路径吞吐，要等实测 PG claim 成瓶颈才值得，代价是 dual-write。
- **不用 Celery**：Celery 和我们重叠 ~90%，但**做不了资源感知放置**（它的 worker 无差别、routing 是静态队列），还把 broker/dual-write 加回来、把 lease/fencing/reaper/leader 藏进黑盒。Celery 是 task-scheduler 同类的**替代品**，不是本项目的叠加层。
- **不用 ZK/etcd**：`pg_try_advisory_lock` 一行换掉（§7）。

> **扩展顺序原则**：先榨干单库（索引、批次、副本），再水平分（分片、分区），最后才引入新系统。每一步要有指标证明上一步到顶。

---

## 12. 术语表（Jargon）

| 术语 | 一句话定义 | 在哪 |
|---|---|---|
| **resource-aware placement** | 按 node 空闲容量 + job 资源需求决定"谁在哪跑"的放置 | `service.py:377` |
| **派生容量(derived capacity)** | `free = total − Σreq(running)`，不存列、不漂移、自愈 | `service.py:359` |
| **node / fleet** | 带固定 CPU/MEM 容量的机器；机器队列 | `nodes` 表 |
| **node 心跳** | node 周期证明自己活着；沉默超时即 dead | `service.py:331` |
| **reap_nodes** | 收割死节点：标 dead + 强制过期其 run 的租约，归约为 run 级重投 | `service.py:261` |
| **leader 选举 / advisory lock** | `pg_try_advisory_lock` 选唯一 leader，锁随连接释放=免费 failover | `scheduler.py:42` |
| **lease / fencing token** | 带超时的执行所有权 + 防陈旧 worker 脏写的单调 token | `service.py:441` |
| **materialize / tick** | 把周期 job 定义展开成具体 run；调度器的一拍 | `service.py:147` |
| **快照(snapshot)** | run 生成时冻结 job 的 req_*/payload/tenant，执行输入不可变 | `service.py:147` |
| **at-least-once** | 每槽至少执行一次；配合幂等任务≈exactly-once | §8 |

---

## 13. 60 分钟答题脚本（自检清单）

1. **澄清 (5min)**：WHEN 还是 WHERE？要不要资源放置？规模？→ 定范围（§1），强调这是 WHERE。
2. **数据模型 (12min)**：三张表，重点讲 `nodes` + **派生容量**为什么不存列（§4.2）。
3. **放置 (12min)**：资源感知 claim = 锁 node + 派生 free + 贪心装箱 + SKIP LOCKED；负载均衡如何自然发生（§6）。
4. **HA (8min)**：advisory lock 选 leader，为什么不用 ZK/etcd（§7）。
5. **容错 (10min)**：三层——run 级租约、**node 级心跳→重调度**、scheduler 级 leader 接管；node 死亡如何归约为租约过期（§8/§9）。
6. **Scale (8min)**：扩展阶梯——副本→分片/分区→Redis 派发层；集群利用率指标（§10/§11/roadmap）。
7. **取舍 (5min)**：为什么不用 Redis/Celery/MQ；运行时隔离是 out-of-scope（§11/§1）。

每段准备一句金句 + 一个代码证据点，被追问就往 §8 不变量、§9 失败表、术语表里钻。
