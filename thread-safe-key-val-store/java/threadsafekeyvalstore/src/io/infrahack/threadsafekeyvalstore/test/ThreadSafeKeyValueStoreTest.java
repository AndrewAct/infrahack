package io.infrahack.threadsafekeyvalstore.test;
import io.infrahack.threadsafekeyvalstore.model.KeyValueStore;
import io.infrahack.threadsafekeyvalstore.model.ThreadSafeKeyValueStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

class ThreadSafeKeyValueStoreTest {
    @Test
    void putAndGetValue() {
        KeyValueStore<String> store = new ThreadSafeKeyValueStore<>();

        store.put("name", "netflix");

        assertEquals("netflix", store.get("name"));
    }

    @Test
    void overwriteExistingValue() {
        KeyValueStore<Integer> store = new ThreadSafeKeyValueStore<>();

        store.put("score", 1);
        store.put("score", 2);

        assertEquals(2, store.get("score"));
    }

    @Test
    void getMissingKeyReturnsNull() {
        KeyValueStore<String> store = new ThreadSafeKeyValueStore<>();

        assertNull(store.get("missing"));
    }

    @Test
    void deleteRemovesKey() {
        KeyValueStore<String> store = new ThreadSafeKeyValueStore<>();

        store.put("session", "abc");
        store.delete("session");

        assertNull(store.get("session"));
    }

    @Test
    void nullKeyIsRejected() {
        KeyValueStore<String> store = new ThreadSafeKeyValueStore<>();

        assertThrows(NullPointerException.class, () -> store.put(null, "value"));
        assertThrows(NullPointerException.class, () -> store.get(null));
        assertThrows(NullPointerException.class, () -> store.delete(null));
    }

    @Test
    void nullValueIsRejected() {
        KeyValueStore<String> store = new ThreadSafeKeyValueStore<>();

        assertThrows(NullPointerException.class, () -> store.put("key", null));
    }

    @Test
    void concurrentWritesToDifferentKeysAreSafe() throws InterruptedException {
        ThreadSafeKeyValueStore<Integer> store = new ThreadSafeKeyValueStore<>(16);

        int threadCount = 20;
        int writesPerThread = 1_000;

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        List<Thread> threads = new ArrayList<>();

        for (int threadId = 0; threadId < threadCount; threadId++) {
            final int id = threadId;

            Thread thread = new Thread(() -> {
                try {
                    start.await();

                    for (int i = 0; i < writesPerThread; i++) {
                        String key = "thread-" + id + "-key-" + i;
                        store.put(key, i);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });

            threads.add(thread);
            thread.start();
        }

        start.countDown();
        done.await();

        assertEquals(threadCount * writesPerThread, store.size());

        for (int threadId = 0; threadId < threadCount; threadId++) {
            for (int i = 0; i < writesPerThread; i++) {
                String key = "thread-" + threadId + "-key-" + i;
                assertEquals(i, store.get(key));
            }
        }
    }

    @Test
    void concurrentWritesToSameKeyEndWithOneValidValue() throws InterruptedException {
        KeyValueStore<Integer> store = new ThreadSafeKeyValueStore<>(4);

        int threadCount = 50;

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int value = i;

            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    store.put("shared-key", value);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });

            thread.start();
        }

        start.countDown();
        done.await();

        Integer finalValue = store.get("shared-key");

        assertNotNull(finalValue);
        assertTrue(finalValue >= 0 && finalValue < threadCount);
    }

    @Test
    void concurrentPutGetDeleteDoesNotCorruptStore() throws InterruptedException {
        KeyValueStore<Integer> store = new ThreadSafeKeyValueStore<>(8);

        int threadCount = 30;
        int operationsPerThread = 5_000;

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        for (int threadId = 0; threadId < threadCount; threadId++) {
            final int id = threadId;

            Thread thread = new Thread(() -> {
                try {
                    start.await();

                    for (int i = 0; i < operationsPerThread; i++) {
                        String key = "key-" + (i % 100);

                        if (i % 3 == 0) {
                            store.put(key, id);
                        } else if (i % 3 == 1) {
                            store.get(key);
                        } else {
                            store.delete(key);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });

            thread.start();
        }

        start.countDown();
        done.await();
    }
}