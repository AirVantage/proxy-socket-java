package net.airvantage.proxysocket.guava;

import net.airvantage.proxysocket.core.ProxyAddressCache;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.net.InetSocketAddress;
import java.time.Duration;

public final class GuavaProxyAddressCache implements ProxyAddressCache {
    private final Cache<InetSocketAddress, InetSocketAddress> cache;

    public GuavaProxyAddressCache(long maxSize, Duration ttl) {
        CacheBuilder<Object, Object> builder = CacheBuilder.newBuilder().maximumSize(maxSize);
        if (ttl != null && !ttl.isNegative() && !ttl.isZero()) {
            builder = builder.expireAfterAccess(ttl);
        }
        //noinspection unchecked
        this.cache = (Cache<InetSocketAddress, InetSocketAddress>) (Cache<?, ?>) builder.build();
    }

    @Override
    public void put(InetSocketAddress clientAddr, InetSocketAddress proxyAddr) {
        if (clientAddr == null || proxyAddr == null) return;
        cache.put(clientAddr, proxyAddr);
    }

    @Override
    public InetSocketAddress get(InetSocketAddress clientAddr) {
        if (clientAddr == null) return null;
        return cache.getIfPresent(clientAddr);
    }

    @Override
    public void invalidate(InetSocketAddress clientAddr) {
        if (clientAddr == null) return;
        cache.invalidate(clientAddr);
    }

    @Override
    public void clear() {
        cache.invalidateAll();
    }
}



