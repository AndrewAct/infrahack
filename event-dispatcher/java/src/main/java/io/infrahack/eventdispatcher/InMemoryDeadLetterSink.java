package io.infrahack.eventdispatcher;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InMemoryDeadLetterSink implements DeadLetterSink {
    private final CopyOnWriteArrayList<DeadLetterRecord> records = new CopyOnWriteArrayList<>();

    @Override
    public void record(DeadLetterRecord record) {
        records.add(record);
    }

    public List<DeadLetterRecord> records() {
        return List.copyOf(records);
    }

    public int size() {
        return records.size();
    }
}
