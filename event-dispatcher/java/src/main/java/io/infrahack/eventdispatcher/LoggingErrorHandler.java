package io.infrahack.eventdispatcher;

public final class LoggingErrorHandler implements ErrorHandler {
    @Override
    public void handle(DomainEvent event, String subscriberId, Exception error) {
        System.err.printf("Error handling event %s, eventType = %s for subscriber %s error = %s%n", event, event.eventType(), subscriberId, error);
    }
}
