package io.infrahack.distributedratelimiter.service;

/** One bucket to check, derived from a matched rule + request context. */
public record BucketRequest(String key, double capacity, double refillPerSecond, int cost) {}
