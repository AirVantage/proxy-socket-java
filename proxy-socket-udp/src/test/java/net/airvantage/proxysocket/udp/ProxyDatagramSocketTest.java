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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * Unit tests for ProxyDatagramSocket IP address mapping and cache behavior.
 */
class ProxyDatagramSocketTest {
    private static final Logger LOG = LoggerFactory.getLogger(ProxyDatagramSocket.class);

    private ProxyDatagramSocket socket;
    private ProxyAddressCache mockCache;
    private ProxyProtocolMetricsListener mockMetrics;

    private InetSocketAddress realClient;
    private InetSocketAddress serviceAddress;
    private InetSocketAddress backendAddress;
    private int localPort;

    private byte[] buffer = new byte[2048];
    private byte[] proxyHeader;

    @BeforeEach
    void setUp() throws Exception {
        mockCache = mock(ProxyAddressCache.class);
        mockMetrics = mock(ProxyProtocolMetricsListener.class);

        realClient = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
        serviceAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), 54321);
        socket = new ProxyDatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), mockCache, mockMetrics, null);
        localPort = socket.getLocalPort();
        backendAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), localPort);

        proxyHeader = new AwsProxyEncoderHelper()
            .family(ProxyHeader.AddressFamily.AF_INET)
            .socket(ProxyHeader.TransportProtocol.DGRAM)
            .source(realClient)
            .destination(serviceAddress)
            .build();
    }

    @AfterEach
    void tearDown() {
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    @Test
    void receive_withValidProxyHeader() throws Exception {
        // Arrange
        byte[] payload = "test-data".getBytes(StandardCharsets.UTF_8);
        byte[] packet = Utility.createPacket(proxyHeader, payload);
        Utility.sendPacket(packet, serviceAddress, backendAddress);

        // Act
        DatagramPacket receivePacket = new DatagramPacket(buffer, buffer.length);
        socket.receive(receivePacket);

        // Assert - cache should be populated with realClient -> lbAddress mapping
        ArgumentCaptor<InetSocketAddress> clientCaptor = ArgumentCaptor.forClass(InetSocketAddress.class);
        ArgumentCaptor<InetSocketAddress> lbCaptor = ArgumentCaptor.forClass(InetSocketAddress.class);
        verify(mockCache).put(clientCaptor.capture(), lbCaptor.capture());

        assertEquals(realClient, clientCaptor.getValue());
        assertEquals(serviceAddress.getAddress(), lbCaptor.getValue().getAddress());
        assertEquals(serviceAddress.getPort(), lbCaptor.getValue().getPort());

        // Verify packet was modified to show real client address
        assertEquals(realClient, receivePacket.getSocketAddress());

        // Verify payload was stripped of proxy header
        assertEquals(payload.length, receivePacket.getLength());
        assertArrayEquals(payload,
                java.util.Arrays.copyOfRange(receivePacket.getData(),
                        receivePacket.getOffset(),
                        receivePacket.getOffset() + receivePacket.getLength()));

        verify(mockMetrics).onHeaderParsed(any(ProxyHeader.class));
        verify(mockMetrics).onTrustedProxy(serviceAddress.getAddress());
        verify(mockMetrics, never()).onUntrustedProxy(any());
        verify(mockMetrics, never()).onParseError(any());
        verify(mockMetrics, never()).onLocal(any());
    }

    @Test
    void send_withCacheHit_usesLoadBalancerAddress() throws Exception {
        byte[] payload = "response".getBytes(StandardCharsets.UTF_8);

        // Mock cache to return lb address
        when(mockCache.get(realClient)).thenReturn(serviceAddress);

        // Create a receiver to verify the packet destination
        java.net.DatagramSocket receiver = new java.net.DatagramSocket(serviceAddress);
        receiver.setSoTimeout(1000);

        try {
            // Act - send to real client, should be redirected to LB
            DatagramPacket sendPacket = new DatagramPacket(payload, payload.length, realClient);
            socket.send(sendPacket);

            // Verify packet was sent to LB address
            DatagramPacket receivePacket = new DatagramPacket(buffer, buffer.length);
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
    void send_withCacheMiss_dropsPacket() throws Exception {
        // Arrange
        byte[] payload = "response".getBytes(StandardCharsets.UTF_8);

        // Mock cache to return null (cache miss)
        when(mockCache.get(realClient)).thenReturn(null);

        // Create a receiver at the client address to verify packet is NOT sent
        java.net.DatagramSocket receiver = new java.net.DatagramSocket(realClient);
        receiver.setSoTimeout(500); // Short timeout since we expect no packet

        try {
            // Act - send to client address
            DatagramPacket sendPacket = new DatagramPacket(payload, payload.length, realClient);
            socket.send(sendPacket);

            // Try to receive - should timeout since packet was dropped
            DatagramPacket receivePacket = new DatagramPacket(buffer, buffer.length);

            assertThrows(java.net.SocketTimeoutException.class, () -> {
                receiver.receive(receivePacket);
            }, "Expected packet to be dropped on cache miss");

            // Verify cache was queried
            verify(mockCache).get(realClient);

            // Verify metrics - cache miss
            verify(mockMetrics).onCacheMiss(realClient);
            verify(mockMetrics, never()).onCacheHit(any());
        } finally {
            receiver.close();
        }
    }

    @Test
    void receive_withLocalCommand_doesNotPopulateCache() throws Exception {
        // Arrange - create LOCAL command (not proxied)
        byte[] payload = "local".getBytes(StandardCharsets.UTF_8);
        byte[] localProxyHeader = new AwsProxyEncoderHelper()
                .command(ProxyHeader.Command.LOCAL)
                .build();
        byte[] packet = Utility.createPacket(localProxyHeader, payload);
        Utility.sendPacket(packet, backendAddress);

        // Act
        DatagramPacket receivePacket = new DatagramPacket(buffer, buffer.length);
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
        byte[] tcpProxyHeader = new AwsProxyEncoderHelper()
            .family(ProxyHeader.AddressFamily.AF_INET)
            .socket(ProxyHeader.TransportProtocol.STREAM) // TCP, not UDP
            .source(realClient)
            .destination(serviceAddress)
            .build();

        byte[] packet = Utility.createPacket(tcpProxyHeader, payload);
        Utility.sendPacket(packet, backendAddress);

        // Act
        DatagramPacket receivePacket = new DatagramPacket(buffer, buffer.length);
        socket.receive(receivePacket);

        // Assert - cache should NOT be populated for non-DGRAM protocols
        verify(mockCache, never()).put(any(), any());

        // Metrics should still be called
        verify(mockMetrics).onHeaderParsed(any());
    }


    @Test
    void receive_withValidProxyHeader_callsMetricsOnHeaderParsed() throws Exception {
        // Arrange
        byte[] payload = "test".getBytes(StandardCharsets.UTF_8);
        byte[] packet = Utility.createPacket(proxyHeader, payload);
        Utility.sendPacket(packet, backendAddress);

        // Act
        DatagramPacket receivePacket = new DatagramPacket(buffer, buffer.length);
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
        Utility.sendPacket(garbage, backendAddress);

        // Act
        DatagramPacket receivePacket = new DatagramPacket(buffer, buffer.length);
        socket.receive(receivePacket);

        // Assert - onParseError should be called
        verify(mockMetrics).onParseError(any(Exception.class));

        // Original packet should be delivered unchanged
        assertEquals(garbage.length, receivePacket.getLength());
    }

}

