/*
 * MIT License
 * Copyright (c) 2025 Semtech
 */
package net.airvantage.proxysocket.udp;

import com.amazonaws.proprot.Header;
import com.amazonaws.proprot.ProxyProtocol;
import com.amazonaws.proprot.ProxyProtocolSpec;
import net.airvantage.proxysocket.core.ProxyAddressCache;
import net.airvantage.proxysocket.core.ProxyProtocolMetricsListener;
import net.airvantage.proxysocket.core.cache.ConcurrentMapProxyAddressCache;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.function.Predicate;

/**
 * DatagramSocket implementation using AWS ProProt library for Proxy Protocol v2 parsing.
 *
 * Thread-safety: This class is thread-safe to the extent that {@link DatagramSocket}
 * is documented as thread-safe for concurrent send/receive by the JDK. The internal
 * cache and metrics listener are expected to be thread-safe. The implementation does
 * not mutate shared state beyond those collaborators.
 */
public class ProxyDatagramSocketAWS extends DatagramSocket {
    private static final Logger LOG = Logger.getLogger(ProxyDatagramSocketAWS.class.getName());

    private ProxyAddressCache addressCache;
    private ProxyProtocolMetricsListener metrics;
    private Predicate<InetSocketAddress> trustedProxyPredicate;
    private final ProxyProtocol proxyProtocol;

    public ProxyDatagramSocketAWS() throws SocketException {
        super();
        this.proxyProtocol = new ProxyProtocol();
        this.proxyProtocol.setEnforceChecksum(false); // Disable checksum enforcement
    }

    public ProxyDatagramSocketAWS(SocketAddress bindaddr) throws SocketException {
        super(bindaddr);
        this.proxyProtocol = new ProxyProtocol();
        this.proxyProtocol.setEnforceChecksum(false); // Disable checksum enforcement
    }

    public ProxyDatagramSocketAWS(int port) throws SocketException {
        super(port);
        this.proxyProtocol = new ProxyProtocol();
        this.proxyProtocol.setEnforceChecksum(false); // Disable checksum enforcement
    }

    public ProxyDatagramSocketAWS(int port, java.net.InetAddress laddr) throws SocketException {
        super(port, laddr);
        this.proxyProtocol = new ProxyProtocol();
        this.proxyProtocol.setEnforceChecksum(false); // Disable checksum enforcement
    }

    public ProxyDatagramSocketAWS setCache(ProxyAddressCache cache) {
        this.addressCache = cache;
        return this;
    }

    public ProxyDatagramSocketAWS setMetrics(ProxyProtocolMetricsListener metrics) {
        this.metrics = metrics;
        return this;
    }

    public ProxyDatagramSocketAWS setTrustedProxy(Predicate<InetSocketAddress> predicate) {
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

            // Parse using AWS ProProt library
            ByteArrayInputStream inputStream = new ByteArrayInputStream(
                packet.getData(), packet.getOffset(), packet.getLength()
            );
            Header header = proxyProtocol.read(inputStream);

            if (metrics != null) {
                // Note: AWS library doesn't expose the same metrics interface
                // We'll just mark successful parse
            }

            if (header.getCommand() == ProxyProtocolSpec.Command.LOCAL) {
                // LOCAL: not proxied
            } else if (header.getCommand() == ProxyProtocolSpec.Command.PROXY
                       && header.getTransportProtocol() == ProxyProtocolSpec.TransportProtocol.DGRAM) {
                byte[] srcAddrBytes = header.getSrcAddress();
                int srcPort = header.getSrcPort();
                if (srcAddrBytes != null && lbAddress != null) {
                    InetAddress srcAddr = InetAddress.getByAddress(srcAddrBytes);
                    InetSocketAddress realClient = new InetSocketAddress(srcAddr, srcPort);
                    if (addressCache != null) addressCache.put(realClient, lbAddress);
                    packet.setSocketAddress(realClient);
                }
            }

            // Calculate how many bytes were read from the stream
            int bytesRead = packet.getLength() - inputStream.available();
            packet.setData(packet.getData(), packet.getOffset() + bytesRead, packet.getLength() - bytesRead);
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
}

