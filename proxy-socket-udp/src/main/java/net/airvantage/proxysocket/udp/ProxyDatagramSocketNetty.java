/*
 * MIT License
 * Copyright (c) 2025 Semtech
 */
package net.airvantage.proxysocket.udp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import net.airvantage.proxysocket.core.ProxyAddressCache;
import net.airvantage.proxysocket.core.ProxyProtocolMetricsListener;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * DatagramSocket implementation using Netty HAProxy codec for Proxy Protocol v2 parsing.
 *
 * Thread-safety: This class is thread-safe to the extent that {@link DatagramSocket}
 * is documented as thread-safe for concurrent send/receive by the JDK. The internal
 * cache and metrics listener are expected to be thread-safe. Note that HAProxyMessageDecoder
 * is stateful and not thread-safe, so we create a new instance per parse.
 */
public class ProxyDatagramSocketNetty extends DatagramSocket {
    private static final Logger LOG = Logger.getLogger(ProxyDatagramSocketNetty.class.getName());

    private ProxyAddressCache addressCache;
    private ProxyProtocolMetricsListener metrics;
    private Predicate<InetSocketAddress> trustedProxyPredicate;

    public ProxyDatagramSocketNetty() throws SocketException {
        super();
    }

    public ProxyDatagramSocketNetty(SocketAddress bindaddr) throws SocketException {
        super(bindaddr);
    }

    public ProxyDatagramSocketNetty(int port) throws SocketException {
        super(port);
    }

    public ProxyDatagramSocketNetty(int port, java.net.InetAddress laddr) throws SocketException {
        super(port, laddr);
    }

    public ProxyDatagramSocketNetty setCache(ProxyAddressCache cache) {
        this.addressCache = cache;
        return this;
    }

    public ProxyDatagramSocketNetty setMetrics(ProxyProtocolMetricsListener metrics) {
        this.metrics = metrics;
        return this;
    }

    public ProxyDatagramSocketNetty setTrustedProxy(Predicate<InetSocketAddress> predicate) {
        this.trustedProxyPredicate = predicate;
        return this;
    }

    @Override
    public void receive(DatagramPacket packet) throws IOException {
        super.receive(packet);
        try {
            InetSocketAddress lbAddress = (InetSocketAddress) packet.getSocketAddress();
            if (trustedProxyPredicate != null && !trustedProxyPredicate.test(lbAddress)) {
                // Untrusted source: do not parse, deliver original packet
                return;
            }

            // Parse using Netty HAProxy decoder
            ByteBuf buffer = Unpooled.wrappedBuffer(packet.getData(), packet.getOffset(), packet.getLength());

            // Create decoder instance (not thread-safe, so create per-parse)
            ProxyDecoder decoder = new ProxyDecoder();
            List<Object> out = new ArrayList<>();

            try {
                decoder.decodePublic(new NoOpChannelHandlerContext(), buffer, out);
            } catch (Exception e) {
                // Not a proxy protocol packet or parsing error, deliver as-is
                LOG.log(Level.FINE, "No proxy header detected", e);
                buffer.release();
                return;
            }

            if (out.isEmpty()) {
                buffer.release();
                return;
            }

            HAProxyMessage proxyMsg = (HAProxyMessage) out.get(0);

            try {
                if (metrics != null) {
                    // Mark successful parse
                }

                // Only process PROXY command (not LOCAL)
                if (proxyMsg.command() == io.netty.handler.codec.haproxy.HAProxyCommand.PROXY) {
                    String srcAddrStr = proxyMsg.sourceAddress();
                    int srcPort = proxyMsg.sourcePort();

                    if (srcAddrStr != null && lbAddress != null) {
                        InetAddress srcAddr = InetAddress.getByName(srcAddrStr);
                        InetSocketAddress realClient = new InetSocketAddress(srcAddr, srcPort);
                        if (addressCache != null) addressCache.put(realClient, lbAddress);
                        packet.setSocketAddress(realClient);
                    }
                }

                // Extract payload after header
                byte[] payload = new byte[buffer.readableBytes()];
                buffer.readBytes(payload);
                packet.setData(payload, 0, payload.length);

            } finally {
                proxyMsg.release();
                buffer.release();
            }

        } catch (Exception e) {
            LOG.log(Level.WARNING, "Proxy socket parse error; delivering original packet.", e);
            if (metrics != null) metrics.onParseError(e);
        }
    }

    @Override
    public void send(DatagramPacket packet) throws IOException {
        InetSocketAddress client = (InetSocketAddress) packet.getSocketAddress();
        InetSocketAddress lb = addressCache != null ? addressCache.get(client) : null;
        if (lb != null) {
            packet.setSocketAddress(lb);
            if (metrics != null) metrics.onCacheHit(client);
        } else {
            if (metrics != null) metrics.onCacheMiss(client);
        }
        super.send(packet);
    }

    /**
     * Wrapper for HAProxyMessageDecoder that exposes the decode method.
     */
    private static class ProxyDecoder extends HAProxyMessageDecoder {
        public void decodePublic(io.netty.channel.ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
            decode(ctx, in, out);
        }
    }

    /**
     * Minimal no-op implementation of ChannelHandlerContext for Netty decoder.
     */
    public static class NoOpChannelHandlerContext implements io.netty.channel.ChannelHandlerContext {
        @Override public io.netty.channel.Channel channel() { return null; }
        @Override public io.netty.util.concurrent.EventExecutor executor() { return null; }
        @Override public String name() { return null; }
        @Override public io.netty.channel.ChannelHandler handler() { return null; }
        @Override public boolean isRemoved() { return false; }
        @Override public io.netty.channel.ChannelHandlerContext fireChannelRegistered() { return this; }
        @Override public io.netty.channel.ChannelHandlerContext fireChannelUnregistered() { return this; }
        @Override public io.netty.channel.ChannelHandlerContext fireChannelActive() { return this; }
        @Override public io.netty.channel.ChannelHandlerContext fireChannelInactive() { return this; }
        @Override public io.netty.channel.ChannelHandlerContext fireExceptionCaught(Throwable cause) { return this; }
        @Override public io.netty.channel.ChannelHandlerContext fireUserEventTriggered(Object evt) { return this; }
        @Override public io.netty.channel.ChannelHandlerContext fireChannelRead(Object msg) { return this; }
        @Override public io.netty.channel.ChannelHandlerContext fireChannelReadComplete() { return this; }
        @Override public io.netty.channel.ChannelHandlerContext fireChannelWritabilityChanged() { return this; }
        @Override public io.netty.channel.ChannelFuture bind(SocketAddress localAddress) { return null; }
        @Override public io.netty.channel.ChannelFuture connect(SocketAddress remoteAddress) { return null; }
        @Override public io.netty.channel.ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) { return null; }
        @Override public io.netty.channel.ChannelFuture disconnect() { return null; }
        @Override public io.netty.channel.ChannelFuture close() { return null; }
        @Override public io.netty.channel.ChannelFuture deregister() { return null; }
        @Override public io.netty.channel.ChannelFuture bind(SocketAddress localAddress, io.netty.channel.ChannelPromise promise) { return null; }
        @Override public io.netty.channel.ChannelFuture connect(SocketAddress remoteAddress, io.netty.channel.ChannelPromise promise) { return null; }
        @Override public io.netty.channel.ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress, io.netty.channel.ChannelPromise promise) { return null; }
        @Override public io.netty.channel.ChannelFuture disconnect(io.netty.channel.ChannelPromise promise) { return null; }
        @Override public io.netty.channel.ChannelFuture close(io.netty.channel.ChannelPromise promise) { return null; }
        @Override public io.netty.channel.ChannelFuture deregister(io.netty.channel.ChannelPromise promise) { return null; }
        @Override public io.netty.channel.ChannelHandlerContext read() { return this; }
        @Override public io.netty.channel.ChannelFuture write(Object msg) { return null; }
        @Override public io.netty.channel.ChannelFuture write(Object msg, io.netty.channel.ChannelPromise promise) { return null; }
        @Override public io.netty.channel.ChannelHandlerContext flush() { return this; }
        @Override public io.netty.channel.ChannelFuture writeAndFlush(Object msg, io.netty.channel.ChannelPromise promise) { return null; }
        @Override public io.netty.channel.ChannelFuture writeAndFlush(Object msg) { return null; }
        @Override public io.netty.channel.ChannelPipeline pipeline() { return null; }
        @Override public io.netty.buffer.ByteBufAllocator alloc() { return null; }
        @Override public io.netty.channel.ChannelPromise newPromise() { return null; }
        @Override public io.netty.channel.ChannelProgressivePromise newProgressivePromise() { return null; }
        @Override public io.netty.channel.ChannelFuture newSucceededFuture() { return null; }
        @Override public io.netty.channel.ChannelFuture newFailedFuture(Throwable cause) { return null; }
        @Override public io.netty.channel.ChannelPromise voidPromise() { return null; }
        @Override public <T> io.netty.util.Attribute<T> attr(io.netty.util.AttributeKey<T> key) { return null; }
        @Override public <T> boolean hasAttr(io.netty.util.AttributeKey<T> key) { return false; }
    }
}

