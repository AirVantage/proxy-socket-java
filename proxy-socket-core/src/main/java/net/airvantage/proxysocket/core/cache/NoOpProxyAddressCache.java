/*
 * MIT License
 * Copyright (c) 2025 Semtech
 */
package net.airvantage.proxysocket.core.cache;

import net.airvantage.proxysocket.core.ProxyAddressCache;
import java.net.InetSocketAddress;

/**
 * No-op implementation useful when state should not be retained.
 */
public final class NoOpProxyAddressCache implements ProxyAddressCache {
    @Override
    public void put(InetSocketAddress clientAddr, InetSocketAddress proxyAddr) { }

    @Override
    public InetSocketAddress get(InetSocketAddress clientAddr) { return null; }

    @Override
    public void invalidate(InetSocketAddress clientAddr) { }

    @Override
    public void clear() { }
}



