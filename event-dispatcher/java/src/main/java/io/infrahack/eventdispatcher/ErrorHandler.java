package io.infrahack.eventdispatcher;

public interface ErrorHandler {
    void handle(DomainEvent event, String subscriberId, Exception error);
}
