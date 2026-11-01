package io.infrahack.explorejavaconcurrency.part02;

public enum TransitionResult {
    MOVED,
    EXPIRED,
    STALE,
    NOT_DUE,
    PAST_DEADLINE,
    TERMINAL
}
