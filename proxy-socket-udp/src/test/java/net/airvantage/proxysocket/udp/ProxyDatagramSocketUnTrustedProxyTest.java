/*
 * MIT License
 * Copyright (c) 2025 Semtech
 */
package net.airvantage.proxysocket.udp;

import net.airvantage.proxysocket.core.ProxyAddressCache;
import net.airvantage.proxysocket.core.ProxyProtocolMetricsListener;
import net.airvantage.proxysocket.core.v2.ProxyHeader;
import net.airvantage.proxysocket.core.v2.AwsProxyEncoderHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ProxyDatagramSocket with untrusted proxy source.
 */
class ProxyDatagramSocketUnTrustedProxyTest {

    private ProxyDatagramSocket socket;
    private ProxyAddressCache mockCache;
    private ProxyProtocolMetricsListener mockMetrics;
    private int localPort;

    @BeforeEach
    void setUp() throws Exception {
        mockCache = mock(ProxyAddressCache.class);
        mockMetrics = mock(ProxyProtocolMetricsListener.class);

        socket = new ProxyDatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), mockCache, mockMetrics, addr -> false);
        localPort = socket.getLocalPort();
    }

    @AfterEach
    void tearDown() {
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    @Test
    void receive_withUntrustedProxy_doesNotStripHeader() throws Exception {
        InetSocketAddress clientAddress = new InetSocketAddress("127.0.0.1", 12345);
        InetAddress lbAddress;

        byte[] payload = "test".getBytes(StandardCharsets.UTF_8);
        byte[] proxyHeader = new AwsProxyEncoderHelper()
                .family(ProxyHeader.AddressFamily.AF_INET)
                .socket(ProxyHeader.TransportProtocol.DGRAM)
                .source(clientAddress)
                .destination(new InetSocketAddress("127.0.0.1", localPort))
                .build();

        byte[] packet = new byte[proxyHeader.length + payload.length];
        System.arraycopy(proxyHeader, 0, packet, 0, proxyHeader.length);
        System.arraycopy(payload, 0, packet, proxyHeader.length, payload.length);

        try (DatagramSocket sender = new DatagramSocket(clientAddress)) {
            sender.send(new DatagramPacket(packet, packet.length,
                    new InetSocketAddress("127.0.0.1", localPort)));
            lbAddress = sender.getLocalAddress();
        }

        // Act
        byte[] receiveBuf = new byte[2048];
        DatagramPacket receivePacket = new DatagramPacket(receiveBuf, receiveBuf.length);
        socket.receive(receivePacket);

        // Assert - no metrics should be called for untrusted sources
        verify(mockMetrics, never()).onHeaderParsed(any());
        verify(mockMetrics, never()).onParseError(any());
        verify(mockMetrics, never()).onTrustedProxy(any());
        verify(mockMetrics).onUntrustedProxy(lbAddress);

        // Packet length should include proxy header (not stripped)
        assertEquals(packet.length, receivePacket.getLength());
    }

    @Test
    void receive_withUntrustedProxy_doesNotParse() throws Exception {
        InetSocketAddress clientAddress = new InetSocketAddress("127.0.0.1", 12345);

        byte[] packet = "Not a proxy header".getBytes(StandardCharsets.UTF_8);

        try (java.net.DatagramSocket sender = new java.net.DatagramSocket()) {
            sender.send(new DatagramPacket(packet, packet.length,
                    new InetSocketAddress("127.0.0.1", localPort)));
        }

        // Act
        byte[] receiveBuf = new byte[2048];
        DatagramPacket receivePacket = new DatagramPacket(receiveBuf, receiveBuf.length);
        assertDoesNotThrow(() -> socket.receive(receivePacket), "No ProxyProtocolException should be thrown");

        // Packet length should be the same as the original packet length
        assertEquals(packet.length, receivePacket.getLength());
    }
}

