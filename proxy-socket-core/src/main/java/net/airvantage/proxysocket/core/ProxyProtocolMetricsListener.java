/**
 * BSD-3-Clause License.
 * Copyright (c) 2025 Semtech
 */
package net.airvantage.proxysocket.core;

import net.airvantage.proxysocket.core.v2.ProxyHeader;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * Metrics/observability callbacks for Proxy Protocol processing.
 * Implementations must be thread-safe.
 */
public interface ProxyProtocolMetricsListener {
    default void onHeaderParsed(ProxyHeader header) {}
    default void onParseError(Exception e) {}
    default void onCacheHit(InetSocketAddress client) {}
    default void onCacheMiss(InetSocketAddress client) {}
    default void onUntrustedProxy(InetAddress proxy) {}
    default void onTrustedProxy(InetAddress proxy) {}
    default void onLocal(InetAddress proxy) {}
}
