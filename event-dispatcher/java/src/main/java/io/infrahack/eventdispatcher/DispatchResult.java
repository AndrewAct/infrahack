package io.infrahack.eventdispatcher;

/*
 * DispatchResult represents the outcome of an event dispatch operation.
 * It encapsulates the number of matched subscribers, submitted events, and rejected events.
 * We don't use boolean for submitted or rejected because we want to keep track of the fan-out process
 */
public record DispatchResult(int matchedSubscribers, int submitted, int rejected) {
    public boolean fullyAccepted() {
        return rejected == 0;
    }

    // It is possible that a subscriber is registered but not subscribed to the event
    public boolean hasNoSubscribers() {
        return matchedSubscribers == 0;
    }

    // Only consider the event as fullyRejected if matchedSubscribers > 0 and all subscribers were rejected
    public boolean fullyRejected() {
        return matchedSubscribers > 0 && rejected == matchedSubscribers;
    }
}
