/*
 * MIT License
 * Copyright (c) 2025 Semtech
 */
package net.airvantage.proxysocket.core;

import java.net.InetSocketAddress;

/**
 * Thread-safe cache abstraction mapping real client addresses to proxy/load-balancer addresses.
 */
public interface ProxyAddressCache {
    void put(InetSocketAddress clientAddr, InetSocketAddress proxyAddr);
    InetSocketAddress get(InetSocketAddress clientAddr);
    void invalidate(InetSocketAddress clientAddr);
    void clear();
}



