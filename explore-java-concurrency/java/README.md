# Java kitchen experiments

[English](#english) | [中文](#中文)

## English

Target JDK: **25**. Keep the source analysis pinned to the JDK revision used by the experiments. The local shell's `java` command reports 25.0.2, while Maven currently selects a separate Java 27 installation unless `JAVA_HOME` is set. The verified local command is:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home mvn test
```

Run this from `explore-java-concurrency/java/`. On another machine, set `JAVA_HOME` to its JDK 25 installation. Check `mvn -version` with the same `JAVA_HOME` before reporting results.

The module is one small Maven project. The `kitchen` package implements part 1 claim/expiry, `part02` publishes immutable meal snapshots with CAS, and `part03` runs a synthetic bounded handoff workload. Tests mirror each slice under `src/test/java/`. These are focused experiments around the same scenario rather than one complete kitchen service.

All 13 tests pass on the recorded JDK 25 build. Read [design.md](docs/design.md) for scope and [experiments.md](docs/experiments.md) for result links. After `mvn test` compiles the classes, run the load experiment with:

```bash
/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home/bin/java -cp target/classes io.infrahack.explorejavaconcurrency.part03.LunchRushExperiment
```

The program prints 12 CSV rows after four warmup runs: 2, 8, and 16 platform workers plus virtual threads, each measured three times. It uses a 20-request admission limit, 5 fair handoff permits, a 120 ms freshness window, and synthetic blocking handoffs of 12–24 ms. [part03-results.md](docs/part03-results.md) records the machine, definitions, raw files, and limitations. No universal performance ranking is implied.

## 中文

目标版本是 **JDK 25**。源码分析固定到实验实际使用的 JDK 修订版本。本机 shell 的 `java` 报告 25.0.2，但直接运行 Maven 时目前会选到另一套 Java 27。上面的命令明确设置 `JAVA_HOME`；换机器时改为该机器的 JDK 25 路径。记录结果前用相同的 `JAVA_HOME` 核对 `mvn -version`。

目前使用一个小型 Maven 项目：`kitchen` 实现第一篇的领取与过期，`part02` 用 CAS 发布不可变餐品快照，`part03` 运行有界交接合成负载。测试在 `src/test/java/` 按篇组织。这是同一场景下的针对性实验，尚非完整厨房服务。

已在记录的 JDK 25 上通过全部 13 个测试。[design.md](docs/design.md) 说明范围，[experiments.md](docs/experiments.md) 链接各篇结果。`mvn test` 编译后，可用上面的 `java` 命令运行负载实验。程序先进行四次预热，再输出 12 行 CSV：2、8、16 个平台线程及虚拟线程，各测量三次。参数为最多 20 个已接纳请求、5 个公平交接许可、120 ms 新鲜窗口，以及 12–24 ms 的合成阻塞交接。[part03-results.md](docs/part03-results.md) 记录机器、指标定义、原始文件与限制；这些结果不构成通用性能排名。
