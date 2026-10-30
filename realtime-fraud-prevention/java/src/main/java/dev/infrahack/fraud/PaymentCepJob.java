package dev.infrahack.fraud;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

/** Bounded JSONL demo. Kafka source/sink integration is the next slice. */
public final class PaymentCepJob {
    private PaymentCepJob() {}
    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) {
            throw new IllegalArgumentException("input.jsonl coverage-start-ISO run-id [disorder-ms=45000]");
        }
        long coverageStart = Instant.parse(args[1]).toEpochMilli();
        long disorder = args.length == 4 ? Long.parseLong(args[3]) : 45_000;
        if (disorder < 0) throw new IllegalArgumentException("disorder must be nonnegative");
        var env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        env.setRuntimeMode(RuntimeExecutionMode.STREAMING);
        var input = env.fromData(Files.readAllLines(Path.of(args[0])), Types.STRING)
            .assignTimestampsAndWatermarks(WatermarkStrategy.<String>forBoundedOutOfOrderness(Duration.ofMillis(disorder))
                .withTimestampAssigner((raw, previous) -> {
                    try { return PaymentJson.parse(raw).occurredAt; }
                    catch (Exception e) { return Long.MIN_VALUE; }
                }));
        var streams = PaymentCep.build(input, coverageStart, args[2]);
        streams.cases().print("case");
        streams.rejected().print("rejected");
        streams.cepLate().map(p -> p.eventId).print("unexpected-cep-late");
        env.execute("Payment retry event-time CEP (bounded JSONL)");
    }
}
