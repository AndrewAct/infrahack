package io.infrahack.eventdispatcher;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class BoundedExecutors {
    private BoundedExecutors() {
    }

    public static ThreadPoolExecutor fixed(String name, int workers, int queueCapacity) {
        if (workers <= 0) {
            throw new IllegalArgumentException("workers must be positive");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }

        return new ThreadPoolExecutor(
                workers,
                workers,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                namedThreadFactory(name),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static ThreadFactory namedThreadFactory(String name) {
        Objects.requireNonNull(name, "name");
        AtomicInteger nextId = new AtomicInteger(1);
        return task -> {
            Thread thread = new Thread(task);
            thread.setName(name + "-" + nextId.getAndIncrement());
            return thread;
        };
    }
}
