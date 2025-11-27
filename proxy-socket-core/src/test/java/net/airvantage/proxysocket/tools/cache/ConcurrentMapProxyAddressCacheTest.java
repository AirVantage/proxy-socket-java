/*
 * MIT License
 * Copyright (c) 2025 Semtech
 */
package net.airvantage.proxysocket.tools.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

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
    void testClear() {
        cache.put(clientAddr1, proxyAddr1);
        cache.put(clientAddr2, proxyAddr2);

        cache.clear();

        assertNull(cache.get(clientAddr1));
        assertNull(cache.get(clientAddr2));
    }
}

