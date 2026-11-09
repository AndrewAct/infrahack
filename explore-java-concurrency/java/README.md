# Java kitchen experiments

[English](#english) | [中文](#中文)

## English

Target JDK: **25**. Keep the source analysis pinned to the JDK revision used by the experiments. The local shell's `java` command reports 25.0.2, while Maven currently selects a separate Java 27 installation unless `JAVA_HOME` is set. The verified local command is:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home mvn test
```

Run this from `explore-java-concurrency/java/`. On another machine, set `JAVA_HOME` to its JDK 25 installation. Check `mvn -version` with the same `JAVA_HOME` before reporting results.

The module is one small Maven project. The `kitchen` package implements part 1 claim/expiry, `part02` publishes immutable meal snapshots with CAS, and `part03` tests independent meal readiness/claims and runs a separate synthetic bounded handoff workload. Tests mirror each slice under `src/test/java/`. These are focused experiments around the same scenario rather than one complete kitchen service.

All 26 tests pass in the November 8 JDK 25 validation, including 10 readiness cases. Read [design.md](docs/design.md) for scope and [experiments.md](docs/experiments.md) for result links. After `mvn test` compiles the classes, run the load experiment with:

```bash
/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home/bin/java -cp target/classes io.infrahack.explorejavaconcurrency.part03.LunchRushExperiment
```

The program prints 12 CSV rows after four warmup runs: 2, 8, and 16 platform workers plus virtual threads, each measured three times. It uses a 20-request admission limit, 5 fair handoff permits, a 120 ms freshness window, and synthetic blocking handoffs of 12–24 ms. [part03-results.md](docs/part03-results.md) records the machine, definitions, raw files, and limitations. No universal performance ranking is implied.

`ReadyMeal` gives each fixed meal/rider assignment a private `CompletableFuture<Void>`. Wait for that meal before acquiring a handoff place, then recheck ownership and the shared deadline under the meal monitor. A later rider can claim their ready meal while an earlier rider is still waiting for a different meal. Neither the readiness wait nor the capacity wait holds the meal monitor. Timeout/interruption of a waiter does not cancel preparation; interruption after a successful claim does not undo it. This focused model reserves ownership, without a physical handoff or Part I's cross-meal bag ledger. Callers retain each meal object rather than using a global registry, and choose their own admission bound.

Run the focused checks with `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home mvn -Dtest=ReadyMealTest test`. See [readiness results](docs/part03-readiness-results.md) for controlled schedules, negative controls, source hashes, and the temporary-copy execution location. The existing load table assumes ready meals and does not measure this added coordination.

## 中文

目标版本是 **JDK 25**。源码分析固定到实验实际使用的 JDK 修订版本。本机 shell 的 `java` 报告 25.0.2，但直接运行 Maven 时目前会选到另一套 Java 27。上面的命令明确设置 `JAVA_HOME`；换机器时改为该机器的 JDK 25 路径。记录结果前用相同的 `JAVA_HOME` 核对 `mvn -version`。

目前使用一个小型 Maven 项目：`kitchen` 实现第一篇的领取与过期，`part02` 用 CAS 发布不可变餐品快照，`part03` 测试独立的餐品就绪与领取，并另行运行有界交接合成负载。测试在 `src/test/java/` 按篇组织。这是同一场景下的针对性实验，尚非完整厨房服务。

11 月 8 日的 JDK 25 验证通过全部 26 个测试，其中包含 10 个餐品就绪用例。[design.md](docs/design.md) 说明范围，[experiments.md](docs/experiments.md) 链接各篇结果。`mvn test` 编译后，可用上面的 `java` 命令运行负载实验。程序先进行四次预热，再输出 12 行 CSV：2、8、16 个平台线程及虚拟线程，各测量三次。参数为最多 20 个已接纳请求、5 个公平交接许可、120 ms 新鲜窗口，以及 12–24 ms 的合成阻塞交接。[part03-results.md](docs/part03-results.md) 记录机器、指标定义、原始文件与限制；这些结果不构成通用性能排名。

`ReadyMeal` 为每个固定的餐品/骑手对应关系保存私有的 `CompletableFuture<Void>`。先等对应餐品就绪，再申请交接位置，最后在餐品 monitor 内重新检查领取状态与共同的截止时间。后到骑手可以先领取自己的已就绪餐品，先到者继续等另一份餐。等待就绪和等待容量时均不持有餐品 monitor。等待者超时或中断不会取消备餐；成功领取后的中断也不会撤销领取。这个独立模型只确认所有权，不执行真实交接，也不整合第一篇跨餐品的骑手背包账本。调用方持有餐品对象，不使用全局注册表，并自行选择接纳上限。

针对性检查命令为 `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home mvn -Dtest=ReadyMealTest test`。[就绪实验结果](docs/part03-readiness-results.md) 记录受控调度、反例验证、源码摘要，以及在临时副本中运行的具体位置。原负载表格假设餐品已经就绪，未测量这次新增的协调逻辑。
