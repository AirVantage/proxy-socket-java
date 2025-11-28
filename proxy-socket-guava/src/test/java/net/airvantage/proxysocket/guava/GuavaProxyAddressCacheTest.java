/*
 * MIT License
 * Copyright (c) 2025 Semtech
 */
package net.airvantage.proxysocket.guava;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GuavaProxyAddressCacheTest {
    private GuavaProxyAddressCache cache;
    private InetSocketAddress clientAddr1;
    private InetSocketAddress proxyAddr1;
    private InetSocketAddress proxyAddr2;

    @BeforeEach
    void setUp() {
        cache = new GuavaProxyAddressCache(100, Duration.ofMinutes(10));
        clientAddr1 = new InetSocketAddress("192.168.1.100", 12345);
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
    void testPutOverwritesExistingValue() {
        cache.put(clientAddr1, proxyAddr1);
        cache.put(clientAddr1, proxyAddr2);

        InetSocketAddress result = cache.get(clientAddr1);
        assertEquals(proxyAddr2, result);
    }

    @Test
    void testMaximumSizeEnforcement() {
        GuavaProxyAddressCache smallCache = new GuavaProxyAddressCache(2, Duration.ofMinutes(10));

        InetSocketAddress addr1 = new InetSocketAddress("192.168.1.1", 1);
        InetSocketAddress addr2 = new InetSocketAddress("192.168.1.2", 2);
        InetSocketAddress addr3 = new InetSocketAddress("192.168.1.3", 3);

        smallCache.put(addr1, proxyAddr1);
        smallCache.put(addr2, proxyAddr1);
        smallCache.put(addr3, proxyAddr1);

        // One of the first two entries should have been evicted
        int presentCount = 0;
        if (smallCache.get(addr1) != null) presentCount++;
        if (smallCache.get(addr2) != null) presentCount++;
        if (smallCache.get(addr3) != null) presentCount++;

        assertTrue(presentCount <= 2, "Cache should not exceed maximum size");
    }

    @Test
    void testTTLExpiration() throws InterruptedException {
        GuavaProxyAddressCache ttlCache = new GuavaProxyAddressCache(100, Duration.ofMillis(100));

        ttlCache.put(clientAddr1, proxyAddr1);
        assertNotNull(ttlCache.get(clientAddr1));

        // Wait for TTL to expire
        Thread.sleep(150);

        assertNull(ttlCache.get(clientAddr1), "Entry should have expired");
    }

    @Test
    void testTTLRefreshOnAccess() throws InterruptedException {
        GuavaProxyAddressCache ttlCache = new GuavaProxyAddressCache(100, Duration.ofMillis(200));

        ttlCache.put(clientAddr1, proxyAddr1);

        // Access the entry before expiration to refresh TTL
        Thread.sleep(100);
        assertNotNull(ttlCache.get(clientAddr1));

        // Wait another 100ms (total 200ms since last access)
        Thread.sleep(100);
        assertNotNull(ttlCache.get(clientAddr1), "Entry should still be present due to access refresh");

        // Wait for final expiration
        Thread.sleep(210);
        assertNull(ttlCache.get(clientAddr1), "Entry should have expired");
    }

    // ========== CACHE SATURATION TESTS ==========

    @Test
    void testCacheSaturationBeyondCapacity() {
        int maxSize = 10;
        GuavaProxyAddressCache smallCache = new GuavaProxyAddressCache(maxSize, Duration.ofMinutes(10));

        // Add more entries than max size
        int totalEntries = maxSize + 5;
        List<InetSocketAddress> addresses = new ArrayList<>();
        for (int i = 0; i < totalEntries; i++) {
            InetSocketAddress addr = new InetSocketAddress("192.168.1." + i, 1000 + i);
            addresses.add(addr);
            smallCache.put(addr, proxyAddr1);
        }

        // Count present entries
        int presentCount = 0;
        for (InetSocketAddress addr : addresses) {
            if (smallCache.get(addr) != null) {
                presentCount++;
            }
        }

        // Should not exceed max size
        assertTrue(presentCount <= maxSize, "Cache should not exceed max size");

        // Most recent entries should still be present
        int recentPresentCount = 0;
        for (int i = totalEntries - maxSize; i < totalEntries; i++) {
            if (smallCache.get(addresses.get(i)) != null) {
                recentPresentCount++;
            }
        }
        assertTrue(recentPresentCount > 0, "Recent entries should still be present");
    }

    // ========== CONCURRENCY TESTS ==========

    @Test
    void testConcurrentTTLExpirationWithAccessPatterns() throws Exception {
        // Cache with 300ms TTL
        int ttlMs = 300;
        GuavaProxyAddressCache ttlCache = new GuavaProxyAddressCache(1000, Duration.ofMillis(ttlMs));

        InetSocketAddress testAddr = new InetSocketAddress("192.168.1.100", 10000);
        InetSocketAddress testProxy = new InetSocketAddress("10.0.0.1", 443);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicInteger thread1SuccessfulGets = new AtomicInteger(0);
        AtomicInteger thread2SuccessfulGets = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);

        // Thread 1: Puts value, gets it once with small pause, then long pauses
        Future<?> thread1 = executor.submit(() -> {
            try {
                startLatch.await();

                // Put initial value
                ttlCache.put(testAddr, testProxy);

                // Get it once immediately (should succeed)
                Thread.sleep(50); // Small pause (< TTL)
                if (ttlCache.get(testAddr) != null) {
                    thread1SuccessfulGets.incrementAndGet();
                }

                // Now start long pauses (> TTL) and check if value is still there
                // Without Thread 2 refreshing, these should fail
                for (int i = 0; i < 3; i++) {
                    Thread.sleep(ttlMs + 100); // Wait longer than TTL
                    if (ttlCache.get(testAddr) != null) {
                        thread1SuccessfulGets.incrementAndGet();
                    }
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Thread 2: Fetches value with increasingly slower frequency
        Future<?> thread2 = executor.submit(() -> {
            try {
                startLatch.await();
                Thread.sleep(10); // Let thread1 put the value first

                // Fetch with increasing delays
                int[] delays = {50, 100, 150, 200, 250}; // All < TTL (300ms)
                for (int delay : delays) {
                    if (ttlCache.get(testAddr) != null) {
                        thread2SuccessfulGets.incrementAndGet();
                    }
                    Thread.sleep(delay);
                }

                // Now try with delay > TTL (should fail if no other access)
                Thread.sleep(ttlMs + 50);
                if (ttlCache.get(testAddr) != null) {
                    thread2SuccessfulGets.incrementAndGet();
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Start both threads
        startLatch.countDown();

        thread1.get(10, TimeUnit.SECONDS);
        thread2.get(10, TimeUnit.SECONDS);
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        // Verify expectations:
        // Thread 1: Should get 1 successful fetch initially, then expect ~0-1 more
        // (depends on timing with Thread 2's accesses)
        int t1Gets = thread1SuccessfulGets.get();
        assertTrue(t1Gets >= 1 && t1Gets <= 4,
            "Thread 1 should have 1-4 successful gets, got: " + t1Gets);

        // Thread 2: Should successfully fetch 5 times (all delays < TTL)
        // The 6th fetch (after TTL + 50) might fail depending on Thread 1's timing
        int t2Gets = thread2SuccessfulGets.get();
        assertTrue(t2Gets >= 4 && t2Gets <= 6,
            "Thread 2 should have 4-6 successful gets (keeping cache alive), got: " + t2Gets);

        // The key insight: Thread 2's regular accesses should keep the entry alive
        // for Thread 1's checks, at least for some of them
        int totalGets = t1Gets + t2Gets;
        assertTrue(totalGets >= 5,
            "Total successful gets should be at least 5, got: " + totalGets);
    }
}

