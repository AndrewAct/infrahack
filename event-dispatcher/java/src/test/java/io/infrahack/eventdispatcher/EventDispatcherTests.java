package io.infrahack.eventdispatcher;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

public final class EventDispatcherTests {
    private EventDispatcherTests() {
    }

    public static void main(String[] args) throws Exception {
        fanOutDispatchesToAllSubscribers();
        deregisterStopsFutureDelivery();
        failingSubscriberDoesNotBlockHealthySubscriber();
        slowSubscriberDoesNotBlockFastSubscriber();
        fullSubscriberQueueRejectsNewWork();
        System.out.println("all tests passed");
    }

    private static void fanOutDispatchesToAllSubscribers() throws Exception {
        InMemoryMetricsRecorder metrics = new InMemoryMetricsRecorder();
        AsyncEventBus bus = new AsyncEventBus(new LoggingErrorHandler(), metrics);
        ThreadPoolExecutor firstExecutor = BoundedExecutors.fixed("test-fanout-a", 1, 4);
        ThreadPoolExecutor secondExecutor = BoundedExecutors.fixed("test-fanout-b", 1, 4);
        CountDownLatch handled = new CountDownLatch(2);

        bus.register(EventType.AUTH, new SubscriberRegistration("a", event -> handled.countDown(), firstExecutor));
        bus.register(EventType.AUTH, new SubscriberRegistration("b", event -> handled.countDown(), secondExecutor));

        DispatchResult result = bus.publish(testEvent());

        assertEquals(2, result.matchedSubscribers(), "fanout matched subscribers");
        assertEquals(2, result.submitted(), "fanout submitted deliveries");
        assertTrue(handled.await(1, TimeUnit.SECONDS), "both subscribers should receive event");
        await(() -> metrics.snapshot().succeeded() == 2, "success metric should reach 2");

        bus.close();
    }

    private static void deregisterStopsFutureDelivery() throws Exception {
        InMemoryMetricsRecorder metrics = new InMemoryMetricsRecorder();
        AsyncEventBus bus = new AsyncEventBus(new LoggingErrorHandler(), metrics);
        ThreadPoolExecutor executor = BoundedExecutors.fixed("test-deregister", 1, 4);
        CountDownLatch handled = new CountDownLatch(1);

        bus.register(EventType.AUTH, new SubscriberRegistration("temporary", event -> handled.countDown(), executor));
        bus.deregister(EventType.AUTH, "temporary");

        DispatchResult result = bus.publish(testEvent());

        assertEquals(0, result.matchedSubscribers(), "deregister should remove subscriber");
        assertTrue(!handled.await(150, TimeUnit.MILLISECONDS), "deregistered subscriber should not run");
        assertEquals(1L, metrics.snapshot().noSubscriber(), "no-subscriber metric");

        bus.close();
    }

    private static void failingSubscriberDoesNotBlockHealthySubscriber() throws Exception {
        InMemoryMetricsRecorder metrics = new InMemoryMetricsRecorder();
        AsyncEventBus bus = new AsyncEventBus(new LoggingErrorHandler(), metrics);
        ThreadPoolExecutor badExecutor = BoundedExecutors.fixed("test-failure-bad", 1, 4);
        ThreadPoolExecutor goodExecutor = BoundedExecutors.fixed("test-failure-good", 1, 4);
        CountDownLatch healthyHandled = new CountDownLatch(1);

        bus.register(EventType.AUTH, new SubscriberRegistration("bad", event -> {
            throw new IllegalStateException("boom");
        }, badExecutor));
        bus.register(EventType.AUTH, new SubscriberRegistration("good", event -> healthyHandled.countDown(), goodExecutor));

        bus.publish(testEvent());

        assertTrue(healthyHandled.await(1, TimeUnit.SECONDS), "healthy subscriber should still run");
        await(() -> metrics.snapshot().failed() == 1, "failure metric should reach 1");
        await(() -> metrics.snapshot().succeeded() == 1, "success metric should reach 1");

        bus.close();
    }

    private static void slowSubscriberDoesNotBlockFastSubscriber() throws Exception {
        InMemoryMetricsRecorder metrics = new InMemoryMetricsRecorder();
        AsyncEventBus bus = new AsyncEventBus(new LoggingErrorHandler(), metrics);
        ThreadPoolExecutor slowExecutor = BoundedExecutors.fixed("test-slow", 1, 4);
        ThreadPoolExecutor fastExecutor = BoundedExecutors.fixed("test-fast", 1, 4);
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        CountDownLatch fastHandled = new CountDownLatch(1);

        bus.register(EventType.AUTH, new SubscriberRegistration("slow", event -> {
            slowStarted.countDown();
            releaseSlow.await(1, TimeUnit.SECONDS);
        }, slowExecutor));
        bus.register(EventType.AUTH, new SubscriberRegistration("fast", event -> fastHandled.countDown(), fastExecutor));

        bus.publish(testEvent());

        assertTrue(slowStarted.await(1, TimeUnit.SECONDS), "slow subscriber should start");
        assertTrue(fastHandled.await(1, TimeUnit.SECONDS), "fast subscriber should finish while slow is blocked");
        releaseSlow.countDown();

        bus.close();
    }

    private static void fullSubscriberQueueRejectsNewWork() throws Exception {
        InMemoryMetricsRecorder metrics = new InMemoryMetricsRecorder();
        AsyncEventBus bus = new AsyncEventBus(new LoggingErrorHandler(), metrics);
        ThreadPoolExecutor executor = BoundedExecutors.fixed("test-overload", 1, 1);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        bus.register(EventType.AUTH, new SubscriberRegistration("bounded", event -> {
            firstStarted.countDown();
            releaseFirst.await(1, TimeUnit.SECONDS);
        }, executor));

        bus.publish(testEvent());
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS), "first task should occupy the worker");

        DispatchResult queued = bus.publish(testEvent());
        DispatchResult rejected = bus.publish(testEvent());

        assertEquals(1, queued.submitted(), "second task should fit in queue");
        assertEquals(1, rejected.rejected(), "third task should be rejected");
        assertEquals(1L, metrics.snapshot().rejected(), "rejected metric");

        releaseFirst.countDown();
        bus.close();
    }

    private static DomainEvent testEvent() {
        return DomainEvent.of(EventType.AUTH, "payment-test", Map.of("amount", "10.00"));
    }

    private static void await(BooleanSupplier condition, String message) throws Exception {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadlineNanos) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError(message);
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(long expected, long actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
        }
    }
}
