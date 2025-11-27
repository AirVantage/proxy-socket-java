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
    private InetSocketAddress realClient;
    private InetSocketAddress serviceAddress;
    private InetSocketAddress backendAddress;
    private int localPort;
    private byte[] buffer = new byte[2048];

    @BeforeEach
    void setUp() throws Exception {
        mockCache = mock(ProxyAddressCache.class);
        mockMetrics = mock(ProxyProtocolMetricsListener.class);

        realClient = new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345);
        serviceAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), 54321);
        socket = new ProxyDatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), mockCache, mockMetrics, addr -> false);
        localPort = socket.getLocalPort();
        backendAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), localPort);
    }

    @AfterEach
    void tearDown() {
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    @Test
    void receive_withUntrustedProxy_doesNotStripHeader() throws Exception {
        InetAddress lbAddress;

        byte[] payload = "test".getBytes(StandardCharsets.UTF_8);
        byte[] proxyHeader = new AwsProxyEncoderHelper()
                .family(ProxyHeader.AddressFamily.AF_INET)
                .socket(ProxyHeader.TransportProtocol.DGRAM)
                .source(realClient)
                .destination(serviceAddress)
                .build();

        byte[] packet = Utility.createPacket(proxyHeader, payload);

        try (DatagramSocket sender = new DatagramSocket(realClient)) {
            sender.send(new DatagramPacket(packet, packet.length, backendAddress));
            lbAddress = sender.getLocalAddress();
        }

        // Act
        DatagramPacket receivePacket = new DatagramPacket(buffer, buffer.length);
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
        byte[] packet = "Not a proxy header".getBytes(StandardCharsets.UTF_8);
        Utility.sendPacket(packet, backendAddress);

        // Act
        DatagramPacket receivePacket = new DatagramPacket(buffer, buffer.length);
        assertDoesNotThrow(() -> socket.receive(receivePacket), "No ProxyProtocolException should be thrown");

        // Packet length should be the same as the original packet length
        assertEquals(packet.length, receivePacket.getLength());
    }
}

