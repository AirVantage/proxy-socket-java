/*
 * MIT License
 * Copyright (c) 2025 Semtech
 */
package net.airvantage.proxysocket.core.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrentMapProxyAddressCacheTest {
    private ConcurrentMapProxyAddressCache cache;
    private InetSocketAddress clientAddr1;
    private InetSocketAddress clientAddr2;
    private InetSocketAddress proxyAddr1;
    private InetSocketAddress proxyAddr2;

    @BeforeEach
    void setUp() {
        cache = new ConcurrentMapProxyAddressCache();
        clientAddr1 = new InetSocketAddress("192.168.1.100", 12345);
        clientAddr2 = new InetSocketAddress("192.168.1.101", 12346);
        proxyAddr1 = new InetSocketAddress("10.0.0.1", 443);
        proxyAddr2 = new InetSocketAddress("10.0.0.2", 443);
    }

    @Test
    void testPutAndGet() {
        cache.put(clientAddr1, proxyAddr1);
        InetSocketAddress result = cache.get(clientAddr1);
        assertEquals(proxyAddr1, result);
    }

    @Test
    void testGetNonExistentAddress() {
        InetSocketAddress result = cache.get(clientAddr1);
        assertNull(result);
    }

    @Test
    void testPutMultipleAddresses() {
        cache.put(clientAddr1, proxyAddr1);
        cache.put(clientAddr2, proxyAddr2);

        assertEquals(proxyAddr1, cache.get(clientAddr1));
        assertEquals(proxyAddr2, cache.get(clientAddr2));
    }

    @Test
    void testPutOverwritesExistingValue() {
        cache.put(clientAddr1, proxyAddr1);
        cache.put(clientAddr1, proxyAddr2);

        InetSocketAddress result = cache.get(clientAddr1);
        assertEquals(proxyAddr2, result);
    }

    @Test
    void testInvalidate() {
        cache.put(clientAddr1, proxyAddr1);
        cache.put(clientAddr2, proxyAddr2);

        cache.invalidate(clientAddr1);

        assertNull(cache.get(clientAddr1));
        assertEquals(proxyAddr2, cache.get(clientAddr2));
    }

    @Test
    void testInvalidateNonExistentAddress() {
        cache.invalidate(clientAddr1);
        // Should not throw exception
        assertNull(cache.get(clientAddr1));
    }

    @Test
    void testInvalidateNullAddress() {
        cache.put(clientAddr1, proxyAddr1);
        cache.invalidate(null);
        // Should not throw exception, should not affect existing entries
        assertEquals(proxyAddr1, cache.get(clientAddr1));
    }

    @Test
    void testClear() {
        cache.put(clientAddr1, proxyAddr1);
        cache.put(clientAddr2, proxyAddr2);

        cache.clear();

        assertNull(cache.get(clientAddr1));
        assertNull(cache.get(clientAddr2));
    }

    @Test
    void testConcurrentPutAndGet() throws InterruptedException {
        int threadCount = 10;
        int operationsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        InetSocketAddress clientAddr = new InetSocketAddress("192.168.1." + threadId, 10000 + j);
                        InetSocketAddress proxyAddr = new InetSocketAddress("10.0.0." + threadId, 443);
                        cache.put(clientAddr, proxyAddr);
                        InetSocketAddress retrieved = cache.get(clientAddr);
                        assertNotNull(retrieved);
                        assertEquals(proxyAddr, retrieved);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void testConcurrentInvalidate() throws InterruptedException {
        // Pre-populate cache
        for (int i = 0; i < 100; i++) {
            InetSocketAddress clientAddr = new InetSocketAddress("192.168.1." + i, 10000);
            InetSocketAddress proxyAddr = new InetSocketAddress("10.0.0." + i, 443);
            cache.put(clientAddr, proxyAddr);
        }

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < 10; j++) {
                        InetSocketAddress clientAddr = new InetSocketAddress("192.168.1." + (threadId * 10 + j), 10000);
                        cache.invalidate(clientAddr);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        // Verify all addresses were invalidated
        for (int i = 0; i < 100; i++) {
            InetSocketAddress clientAddr = new InetSocketAddress("192.168.1." + i, 10000);
            assertNull(cache.get(clientAddr));
        }
    }
}

