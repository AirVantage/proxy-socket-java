/**
 * BSD-3-Clause License.
 * Copyright (c) 2025 Semtech
 */
package net.airvantage.proxysocket.udp;

import net.airvantage.proxysocket.core.ProxyAddressCache;
import net.airvantage.proxysocket.core.ProxyProtocolMetricsListener;
import net.airvantage.proxysocket.core.ProxyProtocolParseException;
import net.airvantage.proxysocket.core.v2.ProxyHeader;
import net.airvantage.proxysocket.core.v2.ProxyProtocolV2Decoder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.channels.IllegalBlockingModeException;
import java.util.function.Predicate;

/**
 * DatagramSocket that strips Proxy Protocol v2 headers and exposes real client address.
 *
 * Thread-safety: This class is thread-safe to the extent that {@link DatagramSocket}
 * is documented as thread-safe for concurrent send/receive by the JDK. The internal
 * cache and metrics listener are expected to be thread-safe. The implementation does
 * not mutate shared state beyond those collaborators.
 */
public class ProxyDatagramSocket extends DatagramSocket {
    private static final Logger LOG = LoggerFactory.getLogger(ProxyDatagramSocket.class);

    private final ProxyAddressCache addressCache;
    private final ProxyProtocolMetricsListener metrics;
    private final Predicate<InetSocketAddress> trustedProxyPredicate;

    public ProxyDatagramSocket(SocketAddress bindaddr, ProxyAddressCache cache, ProxyProtocolMetricsListener metrics, Predicate<InetSocketAddress> predicate) throws SocketException {
        super(bindaddr);
        this.addressCache = cache;
        this.metrics = metrics;
        this.trustedProxyPredicate = predicate;
    }

    public ProxyDatagramSocket(ProxyAddressCache cache, ProxyProtocolMetricsListener metrics, Predicate<InetSocketAddress> predicate) throws SocketException {
        this(new InetSocketAddress(0), cache, metrics, predicate);
    }

    public ProxyDatagramSocket(int port, ProxyAddressCache cache, ProxyProtocolMetricsListener metrics, Predicate<InetSocketAddress> predicate) throws SocketException {
        this(port, null, cache, metrics, predicate);
    }

    public ProxyDatagramSocket(int port, java.net.InetAddress laddr, ProxyAddressCache cache, ProxyProtocolMetricsListener metrics, Predicate<InetSocketAddress> predicate) throws SocketException {
        this(new InetSocketAddress(laddr, port), cache, metrics, predicate);
    }

    @Override
    public void receive(DatagramPacket packet)
        throws IOException, SocketTimeoutException, PortUnreachableException, IllegalBlockingModeException {

        super.receive(packet);

        try {
            InetSocketAddress lbAddress = (InetSocketAddress) packet.getSocketAddress();
            if (trustedProxyPredicate != null && !trustedProxyPredicate.test(lbAddress)) {
                // Untrusted source: do not parse, deliver original packet
                LOG.debug("Untrusted proxy source; delivering original packet.");
                if (metrics != null) metrics.onUntrustedProxy(lbAddress.getAddress());
                return;
            }

            ProxyHeader header = ProxyProtocolV2Decoder.parse(packet.getData(), packet.getOffset(), packet.getLength());
            if (metrics != null) metrics.onHeaderParsed(header);

            if (header.isLocal()) {
                // LOCAL: not proxied
                if (metrics != null) metrics.onLocal(lbAddress.getAddress());
            }
            if (header.isProxy() && header.getProtocol() == ProxyHeader.TransportProtocol.DGRAM) {
                if (metrics != null) metrics.onTrustedProxy(lbAddress.getAddress());

                InetSocketAddress realClient = header.getSourceAddress();
                if (realClient != null) { // could be null if address family is unspecified or unix
                    if (addressCache != null) addressCache.put(realClient, lbAddress);
                    packet.setSocketAddress(realClient);
                }
            }

            int headerLen = header.getHeaderLength();
            LOG.trace("Stripping header: {} bytes, original length: {}", headerLen, packet.getLength());
            packet.setData(packet.getData(), packet.getOffset() + headerLen, packet.getLength() - headerLen);
        } catch (ProxyProtocolParseException e) {
            LOG.warn("Proxy socket parse error; delivering original packet.", e);
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
        } else if (addressCache != null) {
            // Cache miss: unable to map client to load balancer address,
            LOG.warn("Cache miss for client {}; unable to map to load balancer address, dropping packet.", client);
            if (metrics != null) metrics.onCacheMiss(client);
            return;
        // } else {
            // No cache: deliver original packet
        }
        super.send(packet);
    }
}
