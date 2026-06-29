package io.infrahack.threadsafekeyvalstore.model;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ThreadSafeKeyValueStore<V> implements KeyValueStore<V> {

    private static final int DEFAULT_BUCKET_COUNT= 16;
    private final Bucket<V>[] buckets;

    public ThreadSafeKeyValueStore() {
        this(DEFAULT_BUCKET_COUNT);
    }

    @SuppressWarnings("unchecked")
    public ThreadSafeKeyValueStore(int bucketCount) {
        if (bucketCount <= 0) {
            throw new IllegalArgumentException("bucketCount must be greater than 0");
        }
        this.buckets = (Bucket<V>[]) new Bucket[bucketCount];
        for (int i = 0; i < bucketCount; i++) {
            buckets[i] = new Bucket<>();
        }
    }

    @Override
    public void put(String key, V value) {
        validateKey(key);
        Objects.requireNonNull(value, "value must not be null");
        Bucket<V> bucket = bucketFor(key);
        bucket.lock.writeLock().lock();
        try {
            bucket.entries.put(key, value);
        } finally {
            bucket.lock.writeLock().unlock();
        }
    }

    @Override
    public V get(String key) {
        validateKey(key);
        Bucket<V> bucket = bucketFor(key);
        bucket.lock.readLock().lock();
        try {
            return bucket.entries.get(key);
        } finally {
            bucket.lock.readLock().unlock();
        }
    }

    @Override
    public void delete(String key) {
        validateKey(key);
        Bucket<V> bucket = bucketFor(key);
        bucket.lock.writeLock().lock();
        try {
            bucket.entries.remove(key);
        } finally {
            bucket.lock.writeLock().unlock();
        }
    }

    public int size() {
        int total = 0;
        for (Bucket<V> bucket : buckets) {
            bucket.lock.readLock().lock();
            try {
                total += bucket.entries.size();
            } finally {
                bucket.lock.readLock().unlock();
            }
        }
        return total;
    }

    private Bucket<V> bucketFor(String key) {
        int index = Math.floorMod(key.hashCode(), buckets.length);
        return buckets[index];
    }

    private void validateKey(String key) {
        Objects.requireNonNull(key, "key must not be null");
    }

    private static final class Bucket<V> {
        private final Map<String, V> entries = new HashMap<>();
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    }
}
