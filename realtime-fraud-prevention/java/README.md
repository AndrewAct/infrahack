# Payment retry CEP — Java 21 / Flink 2.3.0

[English](#english) · [中文](#中文)

## English

Run from this directory. Requires Java 21, a POSIX shell and `rg`; the Maven wrapper pins Maven 3.9.9. The first run downloads dependencies. Tests start local MiniClusters on temporary ports; no Kafka or OpenSearch service is needed.

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
sh run.sh
```

On another machine, set `JAVA_HOME` to its JDK 21 installation. `run.sh` runs the correctness suite with a 2 GiB test-JVM heap limit, packages the module, then executes the bounded JSONL job. It saves `results/cep-<UTC>/` with source SHA-256 hashes, input traces, expected/actual cases, rejection identities, JUnit reports, managed-state observations and logs. See [measured results](../docs/cep-results.md). These are correctness runs, not throughput or process-memory measurements.

For a focused edit, run `./mvnw -Dtest=ManagedStateTest test` or `./mvnw -Dtest=CepIntegrationTest test`. The first tests the actual stateful operators, timers and snapshot restoration; the second runs 26 event-time traces against an independent scan and a processing-time order control.

Run a small input file directly after `sh run.sh` has built the classpath:

```sh
java -Xmx2g -cp "target/classes:$(cat target/classpath.txt)" \
  dev.infrahack.fraud.PaymentCepJob \
  results/<run-id>/ordered-input.jsonl 2026-10-26T14:50:00Z my-case-run 45000
```

Arguments are input JSONL, declared history coverage start, run namespace and optional watermark disorder tolerance in milliseconds. The runner loads this bounded fixture into memory and prints `case>`, `rejected>` and unexpected CEP-late outputs. The generated test traces can contain `_watermark` controls; only use control-free files such as `ordered-input.jsonl` with this runner. Periodic source watermarks depend on scheduling; deterministic test controls are what establish admission-boundary behavior.

Read the production classes in this order:

1. `PaymentJson` / `Payment`: validate the nested source, define the full key and canonical immutable payload.
2. `Admission`: reject timestamps at or behind the watermark before mutating state; deduplicate attempts and event IDs; clean the ledger at event time + 120 seconds.
3. `FeatureHistory` / `Features`: sort finalized timestamps by sequence/event ID; take an authorization's snapshot before later equal-time positions; retain a separate ten-minute history.
4. `PaymentCep`: `times(3).consecutive().next(success)` with a strict 120-second horizon; serialize witness, features, versions and the illustrative eight-dimensional vector.
5. `PaymentCepJob`: source/watermark policy and the bounded local runner.

The eight features are attempts, declines, failure fraction, distinct devices, distinct IPs, attempted amount, median adjacent gap and matched-sequence duration. All use the single-card key and terminal-S cutoff. Complete-history, currency and telemetry checks determine vector eligibility. Counts and values remain inspectable in case JSON; only complete snapshots include the scaled L2 baseline vector.

### Kafka and OpenSearch integration

```sh
sh kafka-check.sh
# Also verify indexing against an already available OpenSearch instance:
OPENSEARCH_ENDPOINT=http://localhost:9200 sh kafka-check.sh
```

The check starts and stops its own isolated Homebrew Kafka broker on 19092/19093, rejects occupied ports and verifies cluster ownership. It retains `results/kafka-cep-<UTC>/`. It runs the existing six test methods plus one opt-in integration method: checkpoint pending FFF; fail after the case reaches Kafka; verify restored witnesses/features, exactly-once versus at-least-once output, and a bounded raw replay. With OpenSearch supplied, it creates/deletes its own test index and checks retry, conflict, partial-bulk offset safety and search visibility. [Measured results](../docs/kafka-results.md). The test is skipped in ordinary `sh run.sh` runs.

Run the small job directly after building the classpath (create the input/output topics first):

```sh
CP="target/classes:$(cat target/classpath.txt)"
java -Xmx2g -cp "$CP" dev.infrahack.fraud.KafkaPaymentCepJob \
  localhost:9092 payment-events payment-cases live-v1 2026-10-26T14:50:00Z
# Append 'bounded' to consume only the source offsets present at job initialization.
java -cp "$CP" dev.infrahack.fraud.CaseIndexer \
  localhost:9092 payment-cases indexer-v1 http://localhost:9200 fraud-cases-payment-retry-single-card-v2 100
```

Create the index using `src/test/resources/case-index-mapping.json` before running the indexer. This runner uses parallelism 1, a 45-second disorder tolerance, 1-second checkpoints and the tested Kafka connector RC1. Without new event-time progress, quiet input can hold cases pending. The indexer handles at most the supplied source-record count and stops after 30 seconds without progress. Requests contain at most 100 items / 1 MiB, run synchronously, and use a 10-second HTTP timeout and three retry rounds. It commits only completed batches; permanent indexing failures or unconfirmed quarantine writes stop the run without advancing that batch. Kafka can redeliver already created items safely. This remains a small local correctness study, with no broker/node-loss or capacity claim.

Create a separate Kafka topic `payment-cases-conflicts` for the example above. The default quarantine destination is `<case-topic>-conflicts`; append a seventh argument to `CaseIndexer` to override it. A content mismatch preserves the OpenSearch document and synchronously publishes both versions, target index and source topic/partition/offset/key. Only an acknowledged publication counts as handled. The producer uses `acks=all`, idempotence, a 5-second metadata/buffer wait and a 10-second delivery deadline. Error logs and separate conflict/quarantined counters expose outcomes. [Contract and measured checks](../docs/quarantine-results.md).

Quarantine delivery is at-least-once: a failure after acknowledgment but before offset commit can repeat an event. Its Kafka key and `conflict_id` are a deterministic hash of target index plus the retained source topic/partition/offset; downstream inspection/reprocessing can deduplicate by that ID. Full comparison still includes `knowledge_time`; there is no metadata-only exception or automatic adjudication. Existing documents remain searchable and are not thereby proven correct. Quarantine publication failure retains the source batch for retry. The direct `project(values)` fixture helper has no source context and still rejects mismatches; the Kafka-consuming runner provides quarantine handling.

`knowledge_time` is persisted in the committed case. Rebuilding the index from that log preserves it. Raw recomputation uses a new namespace and observation time; no cross-replay first-observation registry is provided. Kafka keys encode tenant/account; state keys retain the full account/card tuple. A new deployment needs an isolated transaction namespace; recovery of the same job retains its namespace.

Accepted/late/duplicate/conflict counters are available on `Admission`; rejections also have a side stream, printed by the runner. State tests record actual retained entries and serialized snapshot bytes. Dedup timers scan the small ledger and feature snapshots scan the retained history: this is a readable correctness baseline, not a capacity-tuned implementation. A stalled watermark permits state growth. Conflicts conservatively exclude pending/future snapshots until the conflicting observations age out of the ten-minute horizon; already emitted cases are not retracted. Transactional recovery is measured through the committed Kafka case boundary; OpenSearch convergence uses create-only writes and content comparison.

### Historical retrieval and closing load benchmark

The article benchmark generates 200 standardized synthetic fields from eight latent templates with independent noise. `CaseEncoder` is a seeded tanh projection shared by corpus and held-out queries; it is not a trained fraud model. Its normalized cosine vectors use a separate physical index per dimension, distinct from the streaming eight-feature contract. The index stores the retrieval vector/eligibility metadata; raw vector binaries, example inputs and seeds allow reconstruction. No online multi-key feature join is implemented.

In one terminal start `sh article-node.sh`, using the existing matching OpenSearch/k-NN installation. This isolated node uses ports 19200/19300, loopback debug port 15005, a 2 GiB heap, four active processors, and separate data under `target/article-node/`. It refuses occupied ports. Stop the foreground process with Ctrl-C when done. Override `OPENSEARCH_DISTRIBUTION` and `OPENSEARCH_EXECUTABLE` on another installation; the recorded server ran JDK 25.

In another terminal, from this directory:

```sh
sh retrieval-check.sh
# Pass the resulting directory, not the literal example below:
sh load-check.sh results/retrieval-<UTC>
uv run summarize-results.py retrieval results/retrieval-<UTC>
uv run summarize-results.py load results/pipeline-load-<UTC>
```

Retrieval checks 10K/100K × D=8/64/256/768, fixed m=16/ef_construction=100, K=10, breadth 100 plus 32/200 at D=256. It checks independent cosine top-10, six exclusion canaries, and actual Lucene branches for every held-out query before detaching the debugger and warming. Twelve queries × three repetitions per mode yield 36 samples. Corpus vectors are retained as big-endian float32 files in ascending c0..cN order; query JSON, expected IDs, actual IDs, branch observations, raw latencies and source hashes are retained. Refresh after force-merge opens the merged search view; the runner asserts one search segment. The tiny five-candidate filter control must execute exact search. `sh retrieval-check.sh 100000 768` reruns one point; `summarize-results.py retrieval PRIMARY --replacement CORRECTION` combines a corrected point without overwriting raw observations.

The closing load script owns an isolated broker on 19092/19093 and stops it on exit. It rebuilds frozen 100K/D256 history from the static run, records a warm query baseline, then queries it about twice per second while the real raw-Kafka → existing Flink CEP → transactional case-Kafka → existing indexer → OpenSearch path runs. The live stream uses the implemented 8D features; historical synthetic vectors are an independent contention workload. It warms at 100 events/s for 50 seconds and offers 100/1000/5000 for 60 seconds each, then advances event time and drains. All 92,750 expected cases must match independently specified witness IDs, eight feature values and transformed vectors, and become searchable. Source/checkpoint and indexer offsets, per-case committed observations, search polls, historical lookup timings, generator schedule, RSS/CPU snapshots and heap observations are retained. Generator, observer, Flink MiniCluster and indexer share a 4 GiB JVM; CPU/RSS cannot be attributed to Flink alone. Kafka gets 1 GiB; OpenSearch remains separate. The load uses per-run filesystem checkpoint storage: the default memory-backed 5 MiB per-state limit failed before useful output, and a 13 MiB real-operator snapshot/restore regression verifies the selected configuration. A 2 GiB application trial hit OOM at the 5000 offered-rate stage; it is retained as capacity evidence, not counted as a completed run. The default load heap is now 4 GiB (`LOAD_HEAP` can override it), with immediate exit on OOM. Both temporary indexes are removed on exit. This brief single ladder establishes observed behavior, not a sustained production capacity or availability guarantee.

See [retrieval and load evidence](../docs/retrieval-results.md). Plot publication figures with `uv run render-article-figures.py RESULTS OUTPUT_DIRECTORY`; matplotlib is pinned in its PEP 723 metadata. Raw results remain local until deliberately prepared for publication; no commit or push is implied.

## 中文

在本目录执行。需要 Java 21、POSIX shell 和 `rg`；Maven wrapper 固定使用 Maven 3.9.9。首次运行会下载依赖。测试通过临时端口启动本地 MiniCluster，不需要 Kafka 或 OpenSearch 服务。

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
sh run.sh
```

在其他机器上，将 `JAVA_HOME` 改为本机 JDK 21 路径。`run.sh` 以 2 GiB 测试 JVM 堆上限执行正确性测试、打包，然后运行有限 JSONL 作业。`results/cep-<UTC>/` 保存源码 SHA-256、输入轨迹、预期／实际 case、被拒绝记录的身份、JUnit 报告、managed-state 观测和日志。参见[实测结果](../docs/cep-results.md)。这些运行不测吞吐量或进程内存。

修改后可先运行 `./mvnw -Dtest=ManagedStateTest test` 或 `./mvnw -Dtest=CepIntegrationTest test`。前者验证实际有状态算子、定时器和快照恢复；后者把 26 组事件时间轨迹与独立扫描结果比较，并运行处理时间顺序对照。

`sh run.sh` 构建 classpath 后，可直接运行小型输入文件：

```sh
java -Xmx2g -cp "target/classes:$(cat target/classpath.txt)" \
  dev.infrahack.fraud.PaymentCepJob \
  results/<run-id>/ordered-input.jsonl 2026-10-26T14:50:00Z my-case-run 45000
```

参数依次是输入 JSONL、声明的历史覆盖起点、运行命名空间，以及可选的 watermark 乱序容忍毫秒数。作业将有限样本读入内存，打印 `case>`、`rejected>` 和意外进入 CEP 的迟到输出。部分测试轨迹带 `_watermark` 控制记录；这个作业只读取 `ordered-input.jsonl` 等不含控制记录的文件。周期性 watermark 受调度影响；准入边界的确定性证据来自测试中的显式控制。

建议按以下顺序阅读实现：

1. `PaymentJson`／`Payment`：校验嵌套输入，定义完整状态键和不可变业务载荷的规范形式。
2. `Admission`：先拒绝时间不大于 watermark 的记录，再对 attempt 和 event ID 去重；在事件时间加 120 秒时清理账本。
3. `FeatureHistory`／`Features`：将已确定的时间戳按 sequence／event ID 排序；成功快照排除相同时间戳下后续位置的事件；单独维护十分钟历史。
4. `PaymentCep`：用 `times(3).consecutive().next(success)` 和严格 120 秒跨度匹配，输出 witness、特征、版本和示例八维向量。
5. `PaymentCepJob`：输入／watermark 策略与本地有限作业入口。

八项特征分别是尝试次数、拒绝次数、失败比例、不同设备数、不同 IP 数、尝试金额、相邻尝试间隔中位数、匹配序列跨度。全部使用单卡状态键和终止成功 S 的截止位置。历史覆盖、币种与遥测完整性决定向量资格。case JSON 保留可读统计值；只有完整快照才附带缩放后的 L2 基线向量。

### Kafka 与 OpenSearch 集成

```sh
sh kafka-check.sh
# 使用已运行的 OpenSearch，同时检查索引：
OPENSEARCH_ENDPOINT=http://localhost:9200 sh kafka-check.sh
```

检查使用现有 Homebrew Kafka，在 19092／19093 启停独立 broker；先拒绝被占用的端口，再核对 cluster ID。证据保存在 `results/kafka-cep-<UTC>/`。它执行现有六个测试方法，并启用一个集成测试：对尚未完成的 FFF 模式做 checkpoint，在 case 到达 Kafka 后注入故障，验证恢复后的 witness／特征、exactly-once 与 at-least-once 的输出，以及有限原始流重放。提供 OpenSearch 地址时，测试创建并删除自己的索引，检查重试、冲突、部分 bulk 失败时的 offset 安全与搜索可见性。参见[实测结果](../docs/kafka-results.md)。普通 `sh run.sh` 会跳过该集成测试。

构建 classpath 后，可以直接执行小作业；先创建输入／输出 topic：

```sh
CP="target/classes:$(cat target/classpath.txt)"
java -Xmx2g -cp "$CP" dev.infrahack.fraud.KafkaPaymentCepJob \
  localhost:9092 payment-events payment-cases live-v1 2026-10-26T14:50:00Z
# 追加 bounded，只消费作业初始化时已有的 source offset 范围。
java -cp "$CP" dev.infrahack.fraud.CaseIndexer \
  localhost:9092 payment-cases indexer-v1 http://localhost:9200 fraud-cases-payment-retry-single-card-v2 100
```

运行索引器前，用 `src/test/resources/case-index-mapping.json` 创建索引。作业采用并行度 1、45 秒乱序容忍、1 秒 checkpoint 和已验证的 Kafka connector RC1。输入安静且事件时间不前进时，case 可能保持待定。索引器最多处理给定的源记录条数，连续 30 秒无进展时停止。同步 bulk 请求最多包含 100 条／1 MiB，HTTP 超时 10 秒，最多三轮重试。只有完整处理的批次才提交 offset；索引永久失败或隔离写入未获确认时停止，不推进该批次。Kafka 再次投递已创建的条目时，可以安全核对。这仍是本地正确性研究，不证明 broker／节点故障恢复或容量。

上面的示例还需要单独创建 Kafka topic `payment-cases-conflicts`。默认隔离目的地是 `<case-topic>-conflicts`，可在 `CaseIndexer` 命令末尾追加第七个参数覆盖。内容不同时，保留 OpenSearch 原文档，同步发布两个版本、目标索引及源 topic／partition／offset／key；收到写入确认后才认为该条已处理。producer 使用 `acks=all`、幂等发送、5 秒元数据／缓冲等待和 10 秒投递期限。error 日志以及独立的 conflict／quarantined 计数暴露处理结果。参见[契约与实测检查](../docs/quarantine-results.md)。

隔离投递是 at-least-once：确认写入后、提交 offset 前失败，可能重复发送。Kafka key 与 `conflict_id` 是目标索引及保留的源 topic／partition／offset 的确定性哈希，后续查看／重处理可按该 ID 去重。完整比较仍包括 `knowledge_time`，不新增仅元数据差异的例外，也不自动裁决版本。原文档仍可被搜索，这不代表它已被证明正确。隔离发布失败时保留源批次供重试。直接调用的 `project(values)` 样本辅助方法没有源位置，仍拒绝内容不同的写入；消费 Kafka 的 runner 提供隔离处理。

`knowledge_time` 随已提交 case 持久化。从 case log 重建索引会保留原值；重新计算原始事件时使用新 namespace 和新观测时间，不另建跨重放首次观测注册表。Kafka key 编码租户／账号，状态 key 保留完整账号／卡元组。独立部署需要隔离事务 namespace，同一作业恢复时保持 namespace。

`Admission` 提供 accepted／late／duplicate／conflict 计数器，拒绝结果另有 side stream，由 runner 打印。状态测试记录实际保留条目数与序列化快照字节数。去重定时器扫描小账本，特征快照扫描历史；这是可读的正确性基线，尚未按容量优化。watermark 停滞时状态会增长。发生冲突时，保守地将尚未输出及后续快照标为不完整，直到相关观测退出十分钟范围；不会撤回已经输出的 case。事务恢复的实测边界是已提交的 Kafka case；OpenSearch 通过 create-only 写入和内容比较收敛。

### 历史检索与结尾负载实验

实验从八个潜在模板和独立噪声生成 200 项标准化合成数值属性。`CaseEncoder` 是语料和查询共用的固定种子 tanh 投影，不是训练后的欺诈模型。输出归一化余弦向量，每个维度使用独立物理索引，与流处理的八项可解释特征不同。索引只存检索向量和准入元数据；原始 float32 向量、输入样例和种子支持复现，没有实现在线跨 key 特征关联。

一个终端运行 `sh article-node.sh`，复用已安装且版本匹配的 OpenSearch/k-NN。隔离实例使用 19200/19300、仅 loopback 的调试端口 15005、2 GiB 堆、四个 active processors，以及 `target/article-node/` 独立数据。端口被占用会拒绝启动，完成后 Ctrl-C 停止。其他安装可覆盖 `OPENSEARCH_DISTRIBUTION` 和 `OPENSEARCH_EXECUTABLE`；本次服务端使用 JDK 25。

另一个终端在本目录执行英文部分的同一组命令：`sh retrieval-check.sh`，再把实际的 `results/retrieval-<UTC>` 传给 `sh load-check.sh`。使用 `uv run summarize-results.py retrieval RESULTS` 和 `uv run summarize-results.py load RESULTS` 汇总对应原始数据。

静态矩阵是 10K/100K × 8/64/256/768 维，m=16、ef_construction=100、K=10，宽度 100；256 维另测 32/200。先核对独立余弦 top-10、六类排除反例和每个查询实际进入的 Lucene 分支，再移除调试器、预热并计时。12 个查询各重复三轮，每模式 36 样本。按 c0..cN 顺序保存 big-endian float32、查询 JSON、预期与实际 ID、分支证据、原始延迟和源码哈希。force-merge 后显式 refresh，并断言搜索视图只有一个 segment。仅五个候选的对照必须走精确搜索。`sh retrieval-check.sh 100000 768` 可重跑一个点；`summarize-results.py retrieval PRIMARY --replacement CORRECTION` 合并修正结果，不覆盖原始记录。

负载脚本独立启停 19092/19093 的 Kafka。先用静态向量重建冻结的 100K/256D 历史库并采集预热查询基线，再以约每秒两次历史查询，与真实 raw Kafka → 原有 Flink CEP → 事务 case Kafka → 原有 indexer → OpenSearch 链路共享资源。在线数据仍然使用已实现的 8D 特征；历史合成向量只是独立的争用负载。先以 100 events/s 预热 50 秒，再分别以 100/1000/5000 各运行 60 秒，最后推进事件时间并排空。92,750 个预期 case 必须逐一符合独立定义的 witness、八项特征与转换向量，并可搜索。保留源 checkpoint/indexer offset、逐 case 已提交观测、搜索轮询、历史查询、发生器调度、RSS/CPU 与堆观测。发生器、observer、Flink MiniCluster 和 indexer 共用 4 GiB JVM，不能将它的资源全部归于 Flink；Kafka 1 GiB，OpenSearch 独立。负载使用每轮独立的文件 checkpoint：默认内存存储的单份状态 5 MiB 上限曾使作业在输出前失败，13 MiB 的真实算子快照／恢复回归验证了所选配置。2 GiB 应用试运行在 5000 档发生 OOM，作为容量证据保留，不计入完成的负载结果。默认负载堆改为 4 GiB，可由 `LOAD_HEAP` 覆盖，OOM 时立即退出。退出时删除两个测试索引。这是一轮短阶梯，不代表持续生产容量或可用性保证。

[检索与负载证据](../docs/retrieval-results.md) 记录实测结论。`uv run render-article-figures.py RESULTS OUTPUT_DIRECTORY` 生成发表图片，PEP 723 固定 matplotlib 版本。原始结果在本地保留，尚未自动发表，不执行 commit 或 push。
