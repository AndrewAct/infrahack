package dev.infrahack.fraud;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.apache.flink.api.common.state.*;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.streaming.api.operators.KeyedProcessOperator;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ManagedStateTest {
    private static Payment at(String id, long t) throws Exception {
        return PaymentJson.parse(Timelines.event(id, "state", t - Timelines.BASE, t, false));
    }
    private static KeyedOneInputStreamOperatorTestHarness<String, Payment, Payment> admission() throws Exception {
        return new KeyedOneInputStreamOperatorTestHarness<>(new KeyedProcessOperator<>(new Admission()), Payment::key, Types.STRING);
    }
    private static KeyedOneInputStreamOperatorTestHarness<String, Payment, Payment> history() throws Exception {
        return new KeyedOneInputStreamOperatorTestHarness<>(new KeyedProcessOperator<>(new FeatureHistory(-600_000)), Payment::key, Types.STRING);
    }
    private static int ledgerSize(KeyedOneInputStreamOperatorTestHarness<String, Payment, Payment> h) throws Exception {
        var state = h.getOperator().getKeyedStateStore().getMapState(new MapStateDescriptor<>("attempts", String.class, Admission.Seen.class));
        int count = 0;
        for (var ignored : state.entries()) count++;
        return count;
    }
    private static int historySize(KeyedOneInputStreamOperatorTestHarness<String, Payment, Payment> h) throws Exception {
        var state = h.getOperator().getKeyedStateStore().getListState(new ListStateDescriptor<>("ten-minute-history", Payment.class));
        int count = 0;
        for (var ignored : state.get()) count++;
        return count;
    }
    private static ObjectNode record(String fixture, long watermark, int entries, long bytes) {
        return PaymentJson.JSON.createObjectNode().put("fixture", fixture).put("watermark", watermark)
            .put("retained_attempts", entries).put("serialized_operator_snapshot_bytes", bytes);
    }
    private static void evidence(String name, List<ObjectNode> rows) throws Exception {
        PaymentJson.JSON.writerWithDefaultPrettyPrinter().writeValue(CepIntegrationTest.results().resolve(name + ".json").toFile(), rows);
    }

    @Test void exactDedupExpiryAndRestoredTimerRejectOldRedelivery() throws Exception {
        OperatorSubtaskState snapshot;
        Payment p = at("expiry", 0);
        var observations = new ArrayList<ObjectNode>();
        try (var h = admission()) {
            h.open();
            h.processWatermark(new Watermark(-1));
            h.processElement(new StreamRecord<>(p, 0));
            h.processWatermark(new Watermark(119_999));
            assertEquals(1, ledgerSize(h));
            snapshot = h.snapshot(1, 1);
            observations.add(record("before-expiry", 119_999, ledgerSize(h), snapshot.getStateSize()));
            assertTrue(snapshot.getStateSize() > 0);
        }
        try (var h = admission()) {
            h.setup();
            h.initializeState(snapshot);
            h.open();
            h.getOperator().setCurrentKey(p.key());
            assertEquals(1, ledgerSize(h));
            h.processWatermark(new Watermark(120_000));
            assertEquals(0, ledgerSize(h));
            h.processElement(new StreamRecord<>(p, 0));
            h.processElement(new StreamRecord<>(at("equal-watermark", 120_000), 120_000));
            assertEquals(0, ledgerSize(h));
            assertEquals(0, h.extractOutputValues().size());
            assertEquals(List.of("late:expiry", "late:equal-watermark"),
                h.getSideOutput(Admission.REJECTED).stream().map(StreamRecord::getValue).toList());
            observations.add(record("at-expiry-after-restore-and-redelivery", 120_000, ledgerSize(h), h.snapshot(2, 2).getStateSize()));
        }
        evidence("dedup-expiry", observations);
    }

    @Test void stalledWatermarkGrowsRealStateThenExpiresOnlyDueEntries() throws Exception {
        var rows = new ArrayList<ObjectNode>();
        try (var h = admission()) {
            h.open();
            h.processWatermark(new Watermark(-1));
            for (int i = 0; i < 24; i++) {
                h.processElement(new StreamRecord<>(at("stalled_" + i, i), i));
                assertEquals(i + 1, ledgerSize(h));
                if (i == 0 || i == 23) rows.add(record("stalled", -1, ledgerSize(h), h.snapshot(i + 1, i).getStateSize()));
            }
            h.processWatermark(new Watermark(120_000));
            assertEquals(23, ledgerSize(h));
            rows.add(record("resume-first-expiry", 120_000, ledgerSize(h), h.snapshot(25, 25).getStateSize()));
            h.processWatermark(new Watermark(120_023));
            assertEquals(0, ledgerSize(h));
            assertEquals(0, h.numEventTimeTimers());
            rows.add(record("resume-all-due", 120_023, ledgerSize(h), h.snapshot(26, 26).getStateSize()));
        }
        assertTrue(rows.get(1).path("serialized_operator_snapshot_bytes").asLong() > rows.getFirst().path("serialized_operator_snapshot_bytes").asLong());
        evidence("stalled-watermark", rows);
    }

    @Test void historyHasIndependentTenMinuteExpiry() throws Exception {
        var rows = new ArrayList<ObjectNode>();
        try (var h = history()) {
            h.open();
            h.processElement(new StreamRecord<>(at("history", 0), 0));
            h.processWatermark(new Watermark(0));
            assertEquals(1, historySize(h));
            h.processWatermark(new Watermark(599_999));
            assertEquals(1, historySize(h));
            rows.add(PaymentJson.JSON.createObjectNode().put("watermark", 599_999).put("history_entries", historySize(h)));
            h.processWatermark(new Watermark(600_000));
            assertEquals(0, historySize(h));
            rows.add(PaymentJson.JSON.createObjectNode().put("watermark", 600_000).put("history_entries", historySize(h)));
            assertEquals(0, h.numEventTimeTimers());
        }
        evidence("feature-history-expiry", rows);
    }

    @Test void bufferedFeaturesSurviveOperatorSnapshot() throws Exception {
        OperatorSubtaskState snapshot;
        List<String> docs = Timelines.sequence("restore", "FFFS", new long[] {0, 30_000, 60_000, 90_000});
        try (var h = new KeyedOneInputStreamOperatorTestHarness<String, Payment, Payment>(
                new KeyedProcessOperator<>(new FeatureHistory(Timelines.BASE - 600_000)), Payment::key, Types.STRING)) {
            h.open();
            for (String doc : docs) {
                Payment p = PaymentJson.parse(doc);
                h.processElement(new StreamRecord<>(p, p.occurredAt));
            }
            h.processWatermark(new Watermark(Timelines.BASE));
            snapshot = h.snapshot(1, 1);
        }
        try (var h = new KeyedOneInputStreamOperatorTestHarness<String, Payment, Payment>(
                new KeyedProcessOperator<>(new FeatureHistory(Timelines.BASE - 600_000)), Payment::key, Types.STRING)) {
            h.setup(); h.initializeState(snapshot); h.open();
            h.processWatermark(new Watermark(Timelines.BASE + 90_000));
            var output = h.extractOutputValues();
            assertEquals(3, output.size());
            Features f = output.getLast().snapshot;
            assertEquals(4, f.attempts);
            assertEquals(3, f.failures);
            assertEquals(30, f.medianGapSeconds);
            assertTrue(f.complete);
        }
    }
}
