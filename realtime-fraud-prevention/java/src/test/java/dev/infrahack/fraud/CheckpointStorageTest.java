package dev.infrahack.fraud;

import java.nio.file.Path;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.streaming.api.operators.KeyedProcessOperator;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CheckpointStorageTest {
    @TempDir Path temporary;

    @Test void loadCheckpointBeyondFiveMiBCanRestoreBusinessDedup() throws Exception {
        OperatorSubtaskState snapshot;
        Payment first = null;
        var storage = org.apache.flink.runtime.state.CheckpointStorageLoader.fromConfig(
            PipelineLoadBenchmark.checkpointConfig(temporary.resolve("checkpoints")), getClass().getClassLoader(),
            org.slf4j.LoggerFactory.getLogger(getClass())).orElseThrow();
        try (var h = new KeyedOneInputStreamOperatorTestHarness<String, Payment, Payment>(
                new KeyedProcessOperator<>(new Admission()), Payment::key, Types.STRING)) {
            h.setCheckpointStorage(storage); h.open();
            for (int i = 0; i < 4000; i++) {
                Payment payment = PaymentJson.parse(Timelines.event("large_" + i, "large", i, i, false));
                if (i == 0) first = payment;
                h.processElement(new StreamRecord<>(payment, payment.occurredAt));
            }
            snapshot = h.snapshot(1, 1);
            assertTrue(snapshot.getStateSize() > 5 * 1024 * 1024, "exercise the previous memory-checkpoint limit");
        }
        try (var h = new KeyedOneInputStreamOperatorTestHarness<String, Payment, Payment>(
                new KeyedProcessOperator<>(new Admission()), Payment::key, Types.STRING)) {
            h.setCheckpointStorage(storage); h.setup(); h.initializeState(snapshot); h.open();
            h.processElement(new StreamRecord<>(first, first.occurredAt));
            assertTrue(h.extractOutputValues().isEmpty());
            assertEquals(1, h.getSideOutput(Admission.REJECTED).size());
            assertEquals("duplicate:large_0", h.getSideOutput(Admission.REJECTED).peek().getValue());
        }
    }
}
