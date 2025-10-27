/*
 * MIT License
 * Copyright (c) 2025 Semtech
 */
package net.airvantage.proxysocket.core.cache;

import net.airvantage.proxysocket.core.ProxyAddressCache;
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Simple thread-safe cache backed by {@link ConcurrentHashMap}.
 */
public final class ConcurrentMapProxyAddressCache implements ProxyAddressCache {
    private final ConcurrentMap<InetSocketAddress, InetSocketAddress> map = new ConcurrentHashMap<>();

    @Override
    public void put(InetSocketAddress clientAddr, InetSocketAddress proxyAddr) {
        if (clientAddr == null || proxyAddr == null) {
            return;
        }
        map.put(clientAddr, proxyAddr);
    }

    @Override
    public InetSocketAddress get(InetSocketAddress clientAddr) {
        if (clientAddr == null) {
            return null;
        }
        return map.get(clientAddr);
    }

    @Override
    public void invalidate(InetSocketAddress clientAddr) {
        if (clientAddr == null) {
            return;
        }
        map.remove(clientAddr);
    }

    @Override
    public void clear() {
        map.clear();
    }
}



