# Real-time fraud prevention

[English](#english) · [中文](#中文)

## English

This module studies how a payment sequence retains its meaning under out-of-order and duplicate delivery. All payments are synthetic; cases describe a pattern, not a fraud verdict.

The implemented Java slice is **nested payment JSON → Kafka → validation → event-time admission/business deduplication → ordered ten-minute feature snapshots → Flink CEP → immutable Kafka cases → bounded Java indexer → OpenSearch**. The key is tenant/account/card namespace/card. An authorization qualifies when its immediately preceding three outcomes are declines and the four-event span is strictly below 120 seconds. Four declines followed by an authorization use the last three declines.

Use [Java instructions](java/README.md) for the JSONL runner, Kafka integration check and evidence. The [design](docs/design.md) explains state ownership; [Kafka/recovery/indexing results](docs/kafka-results.md) cover the completed integration. The focused failure test restores pending CEP state, exposes one committed case with exactly-once versus two with at-least-once, and checks a bounded raw replay. Create-only indexing preserves retries; changed-content conflicts now go to a Kafka quarantine topic before offsets advance, allowing later valid cases to continue. [Quarantine results](docs/quarantine-results.md) cover publication failure and replay safety. The two-article experiment is complete: [retrieval results](docs/retrieval-results.md) record the frozen 10K/100K × 8/64/256/768 matrix, actual graph-path verification and independent cosine oracle; [load results](docs/load-results.md) record the completed 92,750-case ladder, the failed 2 GiB application trial and the successful 4 GiB repeat. Neither validates production fraud quality or sustained capacity.

The initial acceptance is correctness, not an SLO or load benchmark: named boundary cases plus a seeded randomized corpus must agree with an independent ordered-scan oracle on witness IDs, feature values and rejection identities. Separate tests use Flink's real managed state and serialized operator snapshots to verify cleanup and restoration. Performance work belongs to the later retrieval article.

## 中文

本模块研究支付序列在乱序和重复投递时如何保持原有语义。所有支付都是合成数据；case 表示命中一种行为模式，不代表确认欺诈。

已实现的 Java 链路是：**嵌套支付 JSON → Kafka → 校验 → 事件时间准入／业务去重 → 有序十分钟特征快照 → Flink CEP → 不可变 Kafka case → 有界 Java 索引器 → OpenSearch**。状态按租户／账号／卡命名空间／卡分区。成功授权前紧邻的三次结果必须都是拒绝，四个事件的跨度严格小于 120 秒。四次拒绝后成功时，取最后三次拒绝作为证据。

[Java 说明](java/README.md) 包含 JSONL 作业、Kafka 集成检查与原始证据。[设计说明](docs/design.md) 记录状态归属；[Kafka／恢复／索引结果](docs/kafka-results.md) 记录已完成的集成。聚焦的故障实验恢复尚未完成的 CEP 模式，exactly-once 下读取到一个已提交 case，at-least-once 下读取到两个，并核对有限原始流重放。create-only 索引保留重试语义；内容不同的同 ID case 现在先写入 Kafka 隔离 topic，确认成功后才推进 offset，让后面的正常 case 继续处理。[隔离结果](docs/quarantine-results.md) 覆盖写入失败和重投安全。两篇文章所需实验已完成：[检索结果](docs/retrieval-results.md) 记录冻结的 10K/100K × 8/64/256/768 矩阵、真实图路径与独立余弦 oracle；[负载结果](docs/load-results.md) 记录 92,750 个 case 全部完成的阶梯、2 GiB 应用试运行 OOM 和成功的 4 GiB 重跑。这不验证生产欺诈效果或持续容量。

第一阶段验收关注正确性，不设性能 SLO，也不做压测：具名边界用例与固定种子的随机数据，必须在 witness ID、特征数值、拒绝记录身份上与独立有序扫描 oracle 一致。另用真实 Flink managed state 和序列化算子快照验证清理与恢复。性能实验留在后续检索文章中。
