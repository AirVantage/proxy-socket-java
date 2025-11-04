/*
 * MIT License
 * Copyright (c) 2025 Semtech
 */
package net.airvantage.proxysocket.udp;

import net.airvantage.proxysocket.core.ProxyAddressCache;
import net.airvantage.proxysocket.core.ProxyProtocolMetricsListener;
import net.airvantage.proxysocket.core.v2.ProxyHeader;
import net.airvantage.proxysocket.core.v2.ProxyProtocolV2Encoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ProxyDatagramSocket metrics tracking behavior.
 */
class ProxyDatagramSocketMetricsTest {

    private ProxyDatagramSocket socket;
    private ProxyAddressCache mockCache;
    private ProxyProtocolMetricsListener mockMetrics;
    private int localPort;

    @BeforeEach
    void setUp() throws Exception {
        mockCache = mock(ProxyAddressCache.class);
        mockMetrics = mock(ProxyProtocolMetricsListener.class);

        socket = new ProxyDatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                .setCache(mockCache)
                .setMetrics(mockMetrics)
                .setTrustedProxy(addr -> true); // Trust all for these tests

        localPort = socket.getLocalPort();
    }

    @AfterEach
    void tearDown() {
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    @Test
    void receive_withValidProxyHeader_callsMetricsOnHeaderParsed() throws Exception {
        // Arrange
        InetSocketAddress realClient = new InetSocketAddress("10.1.2.3", 12345);
        byte[] payload = "test".getBytes(StandardCharsets.UTF_8);

        byte[] proxyHeader = new ProxyProtocolV2Encoder()
                .family(ProxyHeader.AddressFamily.INET4)
                .socket(ProxyHeader.TransportProtocol.DGRAM)
                .source(realClient)
                .destination(new InetSocketAddress("127.0.0.1", localPort))
                .build();

        byte[] packet = new byte[proxyHeader.length + payload.length];
        System.arraycopy(proxyHeader, 0, packet, 0, proxyHeader.length);
        System.arraycopy(payload, 0, packet, proxyHeader.length, payload.length);

        // Send packet
        try (java.net.DatagramSocket sender = new java.net.DatagramSocket()) {
            sender.send(new DatagramPacket(packet, packet.length,
                    new InetSocketAddress("127.0.0.1", localPort)));
        }

        // Act
        byte[] receiveBuf = new byte[2048];
        DatagramPacket receivePacket = new DatagramPacket(receiveBuf, receiveBuf.length);
        socket.receive(receivePacket);

        // Assert - onHeaderParsed should be called
        ArgumentCaptor<ProxyHeader> headerCaptor = ArgumentCaptor.forClass(ProxyHeader.class);
        verify(mockMetrics).onHeaderParsed(headerCaptor.capture());

        ProxyHeader capturedHeader = headerCaptor.getValue();
        assertNotNull(capturedHeader);
        assertEquals(ProxyHeader.TransportProtocol.DGRAM, capturedHeader.getProtocol());
        assertEquals(realClient, capturedHeader.getSourceAddress());
    }

    @Test
    void receive_withInvalidData_callsMetricsOnParseError() throws Exception {
        // Arrange - send garbage data
        byte[] garbage = "not-a-proxy-header".getBytes(StandardCharsets.UTF_8);

        try (java.net.DatagramSocket sender = new java.net.DatagramSocket()) {
            sender.send(new DatagramPacket(garbage, garbage.length,
                    new InetSocketAddress("127.0.0.1", localPort)));
        }

        // Act
        byte[] receiveBuf = new byte[2048];
        DatagramPacket receivePacket = new DatagramPacket(receiveBuf, receiveBuf.length);
        socket.receive(receivePacket);

        // Assert - onParseError should be called
        verify(mockMetrics).onParseError(any(Exception.class));

        // Original packet should be delivered unchanged
        assertEquals(garbage.length, receivePacket.getLength());
    }

    @Test
    void send_withCacheHit_callsMetricsOnCacheHit() throws Exception {
        // Arrange
        InetSocketAddress realClient = new InetSocketAddress("10.1.2.3", 12345);
        InetSocketAddress lbAddress = new InetSocketAddress("127.0.0.1", 54321);
        byte[] payload = "response".getBytes(StandardCharsets.UTF_8);

        // Mock cache to return lb address
        when(mockCache.get(realClient)).thenReturn(lbAddress);

        // Create a receiver to verify the packet destination
        java.net.DatagramSocket receiver = new java.net.DatagramSocket(lbAddress);
        receiver.setSoTimeout(1000);

        try {
            // Act - send to real client, should be redirected to LB
            DatagramPacket sendPacket = new DatagramPacket(payload, payload.length, realClient);
            socket.send(sendPacket);

            // Receive the packet (to avoid timeout)
            byte[] receiveBuf = new byte[2048];
            DatagramPacket receivePacket = new DatagramPacket(receiveBuf, receiveBuf.length);
            receiver.receive(receivePacket);

            // Assert - onCacheHit should be called
            verify(mockMetrics).onCacheHit(realClient);
            verify(mockMetrics, never()).onCacheMiss(any());
        } finally {
            receiver.close();
        }
    }

    @Test
    void send_withCacheMiss_callsMetricsOnCacheMiss() throws Exception {
        // Arrange
        InetSocketAddress clientAddress = new InetSocketAddress("127.0.0.1", 55555);
        byte[] payload = "response".getBytes(StandardCharsets.UTF_8);

        // Mock cache to return null (cache miss)
        when(mockCache.get(clientAddress)).thenReturn(null);

        // Create a receiver at the client address
        java.net.DatagramSocket receiver = new java.net.DatagramSocket(clientAddress);
        receiver.setSoTimeout(1000);

        try {
            // Act - send to client address
            DatagramPacket sendPacket = new DatagramPacket(payload, payload.length, clientAddress);
            socket.send(sendPacket);

            // Receive the packet (to avoid timeout)
            byte[] receiveBuf = new byte[2048];
            DatagramPacket receivePacket = new DatagramPacket(receiveBuf, receiveBuf.length);
            receiver.receive(receivePacket);

            // Assert - onCacheMiss should be called
            verify(mockMetrics).onCacheMiss(clientAddress);
            verify(mockMetrics, never()).onCacheHit(any());
        } finally {
            receiver.close();
        }
    }

    @Test
    void receive_withUntrustedProxy_doesNotCallMetrics() throws Exception {
        // Arrange - configure to reject all sources
        socket.setTrustedProxy(addr -> false);

        byte[] payload = "test".getBytes(StandardCharsets.UTF_8);
        byte[] proxyHeader = new ProxyProtocolV2Encoder()
                .family(ProxyHeader.AddressFamily.INET4)
                .socket(ProxyHeader.TransportProtocol.DGRAM)
                .source(new InetSocketAddress("10.1.2.3", 12345))
                .destination(new InetSocketAddress("127.0.0.1", localPort))
                .build();

        byte[] packet = new byte[proxyHeader.length + payload.length];
        System.arraycopy(proxyHeader, 0, packet, 0, proxyHeader.length);
        System.arraycopy(payload, 0, packet, proxyHeader.length, payload.length);

        try (java.net.DatagramSocket sender = new java.net.DatagramSocket()) {
            sender.send(new DatagramPacket(packet, packet.length,
                    new InetSocketAddress("127.0.0.1", localPort)));
        }

        // Act
        byte[] receiveBuf = new byte[2048];
        DatagramPacket receivePacket = new DatagramPacket(receiveBuf, receiveBuf.length);
        socket.receive(receivePacket);

        // Assert - no metrics should be called for untrusted sources
        verify(mockMetrics, never()).onHeaderParsed(any());
        verify(mockMetrics, never()).onParseError(any());
    }
}

