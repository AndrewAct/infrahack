package io.infrahack.eventdispatcher;

public interface DeadLetterSink {
    void record(DeadLetterRecord record);
}
