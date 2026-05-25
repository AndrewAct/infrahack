package io.infrahack.eventdispatcher;

public interface EventBus extends AutoCloseable {
    void register(EventType eventType, SubscriberRegistration registration);

    void deregister(EventType eventType, String subscriberId);

//    void publish(DomainEvent event);
    DispatchResult publish(DomainEvent event);

    @Override
    void close();
}
