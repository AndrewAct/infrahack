package dev.infrahack.fraud;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.RestartStrategyOptions;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.kafka.clients.producer.ProducerRecord;

/** Single-machine article runner. Reuses the same business operators as the bounded JSONL demo. */
public final class KafkaPaymentCepJob {
    private KafkaPaymentCepJob() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 5 || args.length > 6) {
            throw new IllegalArgumentException("bootstrap raw-topic case-topic namespace coverage-start-ISO [bounded]");
        }
        boolean bounded = args.length == 6 && args[5].equals("bounded");
        if (args.length == 6 && !bounded) throw new IllegalArgumentException("last argument must be bounded");
        var env = environment();
        var input = env.fromSource(source(args[0], args[1], args[3], bounded), watermarks(45_000), "raw-payments")
            .uid("kafka-payments-v1");
        var streams = PaymentCep.build(input, Instant.parse(args[4]).toEpochMilli(), args[3]);
        streams.cases().sinkTo(sink(args[0], args[2], args[3], DeliveryGuarantee.EXACTLY_ONCE)).uid("kafka-cases-v1");
        streams.rejected().print("rejected");
        streams.cepLate().map(p -> p.eventId).print("unexpected-cep-late");
        env.execute("Payment CEP: " + args[3]);
    }

    static StreamExecutionEnvironment environment() {
        var config = new Configuration();
        config.set(RestartStrategyOptions.RESTART_STRATEGY, "fixed-delay");
        config.set(RestartStrategyOptions.RESTART_STRATEGY_FIXED_DELAY_ATTEMPTS, 1);
        config.set(RestartStrategyOptions.RESTART_STRATEGY_FIXED_DELAY_DELAY, Duration.ofSeconds(1));
        var env = StreamExecutionEnvironment.getExecutionEnvironment(config);
        env.setParallelism(1);
        env.enableCheckpointing(1_000);
        return env;
    }

    static KafkaSource<String> source(String bootstrap, String topic, String group, boolean bounded) {
        var builder = KafkaSource.<String>builder().setBootstrapServers(bootstrap).setTopics(topic).setGroupId(group)
            .setStartingOffsets(OffsetsInitializer.earliest()).setValueOnlyDeserializer(new SimpleStringSchema())
            .setProperty("isolation.level", "read_committed");
        if (bounded) builder.setBounded(OffsetsInitializer.latest());
        return builder.build();
    }

    static WatermarkStrategy<String> watermarks(long disorderMillis) {
        if (disorderMillis < 0) throw new IllegalArgumentException("negative disorder");
        return WatermarkStrategy.<String>forBoundedOutOfOrderness(Duration.ofMillis(disorderMillis))
            .withTimestampAssigner((raw, previous) -> {
                try { return PaymentJson.parse(raw).occurredAt; }
                catch (Exception e) { return Long.MIN_VALUE; }
            });
    }

    static KafkaSink<String> sink(String bootstrap, String topic, String namespace, DeliveryGuarantee guarantee) {
        return KafkaSink.<String>builder().setBootstrapServers(bootstrap)
            .setRecordSerializer(new CaseSerializer(topic)).setDeliveryGuarantee(guarantee)
            .setTransactionalIdPrefix(namespace + "-cases-")
            .setProperty("transaction.timeout.ms", "600000").build();
    }

    static byte[] accountKey(String tenant, String account) {
        try { return PaymentJson.JSON.writeValueAsBytes(List.of(tenant, account)); }
        catch (Exception e) { throw new IllegalArgumentException(e); }
    }

    static final class CaseSerializer implements KafkaRecordSerializationSchema<String> {
        private final String topic;
        CaseSerializer(String topic) { this.topic = topic; }
        @Override public ProducerRecord<byte[], byte[]> serialize(String value, KafkaSinkContext context, Long timestamp) {
            try {
                var n = PaymentJson.JSON.readTree(value);
                return new ProducerRecord<>(topic, accountKey(n.path("tenant_id").asText(), n.path("account_id").asText()),
                    value.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) { throw new IllegalArgumentException("invalid case JSON", e); }
        }
    }
}
