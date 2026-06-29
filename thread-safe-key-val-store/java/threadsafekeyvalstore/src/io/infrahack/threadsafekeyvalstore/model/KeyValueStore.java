package io.infrahack.threadsafekeyvalstore.model;

public interface KeyValueStore<V> {
    void put(String key, V value);
    V get(String key);
    void delete(String key);
}
