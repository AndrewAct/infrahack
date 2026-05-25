package io.infrahack.eventdispatcher;

import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class Demo {
    private Demo() {
    }

    public static void main(String[] args) throws Exception {
        InMemoryMetricsRecorder metrics = new InMemoryMetricsRecorder();
        AsyncEventBus eventBus = new AsyncEventBus(new LoggingErrorHandler(), metrics);

        ThreadPoolExecutor fraudExecutor = BoundedExecutors.fixed("fraud-subscriber", 1, 16);
        ThreadPoolExecutor notificationExecutor = BoundedExecutors.fixed("notification-subscriber", 1, 16);

        eventBus.register(EventType.AUTH, new SubscriberRegistration("fraud-check", event -> {
            Thread.sleep(250);
            System.out.printf("fraud-check handled %s amount=%s%n",
                    event.eventId(),
                    event.attributes().get("amount"));
        }, fraudExecutor));

        eventBus.register(EventType.AUTH, new SubscriberRegistration("notification", event -> {
            Thread.sleep(50);
            System.out.printf("notification handled %s user=%s%n",
                    event.eventId(),
                    event.attributes().get("userId"));
        }, notificationExecutor));

        DomainEvent firstEvent = DomainEvent.of(EventType.AUTH, "payment-1001", Map.of(
                "userId", "user-42",
                "amount", "199.99",
                "currency", "USD"));

        DispatchResult firstResult = eventBus.publish(firstEvent);
        System.out.println("first publish result: " + firstResult);

        Thread.sleep(500);
        eventBus.deregister(EventType.AUTH, "notification");

        DomainEvent secondEvent = DomainEvent.of(EventType.AUTH, "payment-1002", Map.of(
                "userId", "user-99",
                "amount", "29.00",
                "currency", "USD"));

        DispatchResult secondResult = eventBus.publish(secondEvent);
        System.out.println("second publish result: " + secondResult);

        Thread.sleep(500);
        eventBus.close();
        fraudExecutor.awaitTermination(1, TimeUnit.SECONDS);
        notificationExecutor.awaitTermination(1, TimeUnit.SECONDS);

        System.out.println("metrics: " + metrics.snapshot());
    }
}
