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
 * Unit tests for ProxyDatagramSocket IP address mapping and cache behavior.
 */
class ProxyDatagramSocketIPMappingTest {

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
    void receive_withValidProxyHeader_populatesCache() throws Exception {
        // Arrange
        InetSocketAddress realClient = new InetSocketAddress("10.1.2.3", 12345);
        InetSocketAddress lbAddress = new InetSocketAddress("127.0.0.1", 54321);
        byte[] payload = "test-data".getBytes(StandardCharsets.UTF_8);

        byte[] proxyHeader = new ProxyProtocolV2Encoder()
                .family(ProxyHeader.AddressFamily.INET4)
                .socket(ProxyHeader.TransportProtocol.DGRAM)
                .source(realClient)
                .destination(new InetSocketAddress("127.0.0.1", localPort))
                .build();

        byte[] packet = new byte[proxyHeader.length + payload.length];
        System.arraycopy(proxyHeader, 0, packet, 0, proxyHeader.length);
        System.arraycopy(payload, 0, packet, proxyHeader.length, payload.length);

        // Create a loopback socket to send from
        try (java.net.DatagramSocket sender = new java.net.DatagramSocket(lbAddress)) {
            sender.send(new DatagramPacket(packet, packet.length,
                    new InetSocketAddress("127.0.0.1", localPort)));
        }

        // Act
        byte[] receiveBuf = new byte[2048];
        DatagramPacket receivePacket = new DatagramPacket(receiveBuf, receiveBuf.length);
        socket.receive(receivePacket);

        // Assert - cache should be populated with realClient -> lbAddress mapping
        ArgumentCaptor<InetSocketAddress> clientCaptor = ArgumentCaptor.forClass(InetSocketAddress.class);
        ArgumentCaptor<InetSocketAddress> lbCaptor = ArgumentCaptor.forClass(InetSocketAddress.class);
        verify(mockCache).put(clientCaptor.capture(), lbCaptor.capture());

        assertEquals(realClient, clientCaptor.getValue());
        assertEquals(lbAddress.getAddress(), lbCaptor.getValue().getAddress());
        assertEquals(lbAddress.getPort(), lbCaptor.getValue().getPort());

        // Verify packet was modified to show real client address
        assertEquals(realClient, receivePacket.getSocketAddress());

        // Verify payload was stripped of proxy header
        assertEquals(payload.length, receivePacket.getLength());
        assertArrayEquals(payload,
                java.util.Arrays.copyOfRange(receivePacket.getData(),
                        receivePacket.getOffset(),
                        receivePacket.getOffset() + receivePacket.getLength()));
    }

    @Test
    void send_withCacheHit_usesLoadBalancerAddress() throws Exception {
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

            // Verify packet was sent to LB address
            byte[] receiveBuf = new byte[2048];
            DatagramPacket receivePacket = new DatagramPacket(receiveBuf, receiveBuf.length);
            receiver.receive(receivePacket);

            // Assert
            assertArrayEquals(payload,
                    java.util.Arrays.copyOfRange(receivePacket.getData(), 0, receivePacket.getLength()));

            // Verify cache was queried
            verify(mockCache).get(realClient);

            // Verify metrics - cache hit
            verify(mockMetrics).onCacheHit(realClient);
            verify(mockMetrics, never()).onCacheMiss(any());
        } finally {
            receiver.close();
        }
    }

    @Test
    void send_withCacheMiss_usesOriginalAddress() throws Exception {
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

            // Verify packet was sent to original address
            byte[] receiveBuf = new byte[2048];
            DatagramPacket receivePacket = new DatagramPacket(receiveBuf, receiveBuf.length);
            receiver.receive(receivePacket);

            // Assert
            assertArrayEquals(payload,
                    java.util.Arrays.copyOfRange(receivePacket.getData(), 0, receivePacket.getLength()));

            // Verify cache was queried
            verify(mockCache).get(clientAddress);

            // Verify metrics - cache miss
            verify(mockMetrics).onCacheMiss(clientAddress);
            verify(mockMetrics, never()).onCacheHit(any());
        } finally {
            receiver.close();
        }
    }

    @Test
    void receive_withUntrustedProxy_skipsProcessing() throws Exception {
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

        // Assert - packet should be delivered unchanged, no parsing
        verify(mockMetrics, never()).onHeaderParsed(any());
        verify(mockCache, never()).put(any(), any());

        // Packet length should include proxy header (not stripped)
        assertEquals(packet.length, receivePacket.getLength());
    }

    @Test
    void receive_withLocalCommand_doesNotPopulateCache() throws Exception {
        // Arrange - create LOCAL command (not proxied)
        byte[] payload = "local".getBytes(StandardCharsets.UTF_8);
        byte[] proxyHeader = new ProxyProtocolV2Encoder()
                .command(ProxyHeader.Command.LOCAL)
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

        // Assert - cache should NOT be populated for LOCAL commands
        verify(mockCache, never()).put(any(), any());

        // But metrics should still be called
        verify(mockMetrics).onHeaderParsed(any());

        // Payload should be stripped of header
        assertEquals(payload.length, receivePacket.getLength());
    }

    @Test
    void receive_withTcpProtocol_doesNotPopulateCache() throws Exception {
        // Arrange - create header with TCP (not DGRAM) protocol
        byte[] payload = "tcp".getBytes(StandardCharsets.UTF_8);
        byte[] proxyHeader = new ProxyProtocolV2Encoder()
                .family(ProxyHeader.AddressFamily.INET4)
                .socket(ProxyHeader.TransportProtocol.STREAM) // TCP, not UDP
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

        // Assert - cache should NOT be populated for non-DGRAM protocols
        verify(mockCache, never()).put(any(), any());

        // Metrics should still be called
        verify(mockMetrics).onHeaderParsed(any());
    }
}

