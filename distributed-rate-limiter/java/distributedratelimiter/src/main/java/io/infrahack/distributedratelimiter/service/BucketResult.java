package io.infrahack.distributedratelimiter.service;

/** Outcome for a single bucket after a (possibly multi-bucket, all-or-nothing) check. */
public record BucketResult(boolean allowed, double remaining, long retryAfterMs) {}
