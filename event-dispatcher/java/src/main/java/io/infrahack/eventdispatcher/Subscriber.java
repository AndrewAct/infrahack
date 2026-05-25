package io.infrahack.eventdispatcher;

@FunctionalInterface
public interface Subscriber {
    void handle(DomainEvent event) throws Exception;
}
