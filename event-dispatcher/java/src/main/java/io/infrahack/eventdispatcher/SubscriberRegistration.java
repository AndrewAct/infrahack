package io.infrahack.eventdispatcher;

import java.util.Objects;
import java.util.concurrent.ExecutorService;

public record SubscriberRegistration(
        String subscriberId,
        Subscriber subscriber,
        ExecutorService executor) {

    public SubscriberRegistration {
        Objects.requireNonNull(subscriberId, "subscriberId");
        Objects.requireNonNull(subscriber, "subscriber");
        Objects.requireNonNull(executor, "executor");
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof SubscriberRegistration that)) {
            return false;
        }
        return subscriberId.equals(that.subscriberId);
    }

    @Override
    public int hashCode() {
        return subscriberId.hashCode();
    }
}
