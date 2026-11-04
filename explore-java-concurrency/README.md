# Explore Java Concurrency

[English](#english) | [中文](#中文)

## English

This InfraHack module holds the runnable evidence for a three-part article series about one in-memory kitchen. The series investigates meal pickup races, conflicting state changes, and overload. The article drafts and their numbered references live in `Medium/ExploreJavaConcurrency/`; this repository holds the scenario, design, source code, tests, and experiment outputs.

### Current phase

All three initial experiment slices are implemented on JDK 25: synchronized claim/expiry, versioned CAS with stale-task rejection, and copy-on-write snapshots plus bounded handoff load. Thirteen focused tests pass. The synthetic load run includes warmup, three measured repeats, and retained raw CSV. Build and run commands are in [java/README.md](java/README.md); [design.md](java/docs/design.md) records the boundaries and [experiments.md](java/docs/experiments.md) links each result.

### Correctness and load

A meal claim must be unique and before expiry. The part 2 CAS covers one immutable meal snapshot; it does not atomically update real container membership or capacity. The load experiment bounds accepted work at 20 and concurrent handoffs at 5. Container deterioration, request-idempotency retention, restart recovery, and physical handoff are follow-up work. These are three small evidence-producing experiments around one scenario, not a complete kitchen service.

The load result reports fresh completions, waiting time, expiry, rejection, sampled queue depth, and in-flight work. A result belongs to a documented workload and JDK build, not to Java in general. The CAS tests report a forced conflict outcome; they do not rank CAS performance against locks.

## 中文

这个 InfraHack 模块为三篇中央厨房并发文章保存可运行的证据。系列调查取餐竞态、状态更新冲突和负载过高。文章草稿及其编号引用放在 `Medium/ExploreJavaConcurrency/`；本仓库保存场景、设计、源码、测试与实验结果。

### 当前阶段

三篇的首批实验都已在 JDK 25 实现：同步领取与过期、带版本的 CAS 与旧任务拒绝、CopyOnWrite 快照与有界交接负载。十三个针对性测试通过。合成负载运行包含预热、三次测量及保留的原始 CSV。[java/README.md](java/README.md) 记录构建与运行命令，[design.md](java/docs/design.md) 记录边界，[experiments.md](java/docs/experiments.md) 链接各篇结果。

### 正确性与负载

领取必须唯一并早于过期时刻。第二篇的 CAS 只覆盖一个不可变餐品快照，尚未原子更新真实容器成员和容量。负载实验把已接纳工作限制为 20，把并发交接限制为 5。变质模型、幂等结果保留、重启恢复和真实交接留待后续。这是同一场景下三个小型证据实验，尚非完整厨房服务。

负载结果记录新鲜完成量、等待时间、过期、拒绝、抽样队列深度和进行中的工作。每个结果只对应注明的负载与 JDK 版本，不能推广为 Java 的普遍性能结论。CAS 测试记录一次明确冲突的结果，尚未比较 CAS 与锁的性能。
