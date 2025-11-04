/*
 * MIT License
 * Copyright (c) 2025 Semtech
 */
package net.airvantage.proxysocket.udp;

import net.airvantage.proxysocket.core.ProxyAddressCache;
import net.airvantage.proxysocket.core.cache.ConcurrentMapProxyAddressCache;
import net.airvantage.proxysocket.core.v2.ProxyHeader;
import net.airvantage.proxysocket.core.v2.ProxyProtocolV2Encoder;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/**
 * JMH Benchmarks comparing ProxyDatagramSocket vs ProxyDatagramSocketAWS.
 *
 * Measures latency for:
 * - Single packet send/receive roundtrip
 * - Burst of 1000 packets
 * - Different packet sizes (64B, 512B, 1500B)
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = {"-Xms2G", "-Xmx2G"})
public class BenchmarkProxyDatagramSocket {

    @State(Scope.Thread)
    public static class SocketState {
        ProxyDatagramSocket customSocket;
        ProxyDatagramSocketAWS awsSocket;
        DatagramSocket senderSocket;

        InetAddress localhost;
        int customPort;
        int awsPort;
        int senderPort;

        ProxyAddressCache cache;

        @Param({"64", "512", "1500"})
        int packetSize;

        byte[] customPayload;
        byte[] awsPayload;
        byte[] sendBuffer;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            localhost = InetAddress.getLoopbackAddress();
            cache = new ConcurrentMapProxyAddressCache();

            // Create sockets
            customSocket = new ProxyDatagramSocket(0, localhost);
            customPort = customSocket.getLocalPort();
            customSocket.setCache(cache);
            customSocket.setTrustedProxy(addr -> true); // Trust all for benchmark

            awsSocket = new ProxyDatagramSocketAWS(0, localhost);
            awsPort = awsSocket.getLocalPort();
            awsSocket.setCache(cache);
            awsSocket.setTrustedProxy(addr -> true); // Trust all for benchmark

            senderSocket = new DatagramSocket(0, localhost);
            senderPort = senderSocket.getLocalPort();

            // Prepare payloads with proxy protocol header
            byte[] applicationData = new byte[packetSize];
            for (int i = 0; i < applicationData.length; i++) {
                applicationData[i] = (byte) (i % 256);
            }

            // Create proxy header
            InetSocketAddress srcAddr = new InetSocketAddress(localhost, 12345);
            InetSocketAddress dstAddr = new InetSocketAddress(localhost, customPort);

            // Encode proxy protocol header + payload
            byte[] proxyHeader = new ProxyProtocolV2Encoder()
                .command(ProxyHeader.Command.PROXY)
                .family(ProxyHeader.AddressFamily.INET4)
                .socket(ProxyHeader.TransportProtocol.DGRAM)
                .source(srcAddr)
                .destination(dstAddr)
                .build();

            customPayload = new byte[proxyHeader.length + applicationData.length];
            System.arraycopy(proxyHeader, 0, customPayload, 0, proxyHeader.length);
            System.arraycopy(applicationData, 0, customPayload, proxyHeader.length, applicationData.length);

            awsPayload = new byte[proxyHeader.length + applicationData.length];
            System.arraycopy(proxyHeader, 0, awsPayload, 0, proxyHeader.length);
            System.arraycopy(applicationData, 0, awsPayload, proxyHeader.length, applicationData.length);

            sendBuffer = new byte[Math.max(customPayload.length, awsPayload.length) + 100];
        }

        @TearDown(Level.Trial)
        public void teardown() {
            if (customSocket != null && !customSocket.isClosed()) customSocket.close();
            if (awsSocket != null && !awsSocket.isClosed()) awsSocket.close();
            if (senderSocket != null && !senderSocket.isClosed()) senderSocket.close();
        }
    }

    /**
     * Benchmark: Single packet roundtrip with custom implementation
     */
    @Benchmark
    public void singlePacketCustom(SocketState state, Blackhole bh) throws Exception {
        // Send packet with proxy protocol header
        DatagramPacket sendPacket = new DatagramPacket(
            state.customPayload, 0, state.customPayload.length,
            state.localhost, state.customPort
        );
        state.senderSocket.send(sendPacket);

        // Receive and parse
        DatagramPacket receivePacket = new DatagramPacket(state.sendBuffer, state.sendBuffer.length);
        state.customSocket.setSoTimeout(1000);
        state.customSocket.receive(receivePacket);

        // Consume results
        bh.consume(receivePacket.getLength());
        bh.consume(receivePacket.getSocketAddress());
    }

    /**
     * Benchmark: Single packet roundtrip with AWS implementation
     */
    @Benchmark
    public void singlePacketAWS(SocketState state, Blackhole bh) throws Exception {
        // Send packet with proxy protocol header
        DatagramPacket sendPacket = new DatagramPacket(
            state.awsPayload, 0, state.awsPayload.length,
            state.localhost, state.awsPort
        );
        state.senderSocket.send(sendPacket);

        // Receive and parse
        DatagramPacket receivePacket = new DatagramPacket(state.sendBuffer, state.sendBuffer.length);
        state.awsSocket.setSoTimeout(1000);
        state.awsSocket.receive(receivePacket);

        // Consume results
        bh.consume(receivePacket.getLength());
        bh.consume(receivePacket.getSocketAddress());
    }

    /**
     * Benchmark: Burst of 1000 packets with custom implementation
     */
    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @Warmup(iterations = 3, batchSize = 1000)
    @Measurement(iterations = 10, batchSize = 1000)
    public void burstCustom(SocketState state, Blackhole bh) throws Exception {
        DatagramPacket sendPacket = new DatagramPacket(
            state.customPayload, 0, state.customPayload.length,
            state.localhost, state.customPort
        );

        DatagramPacket receivePacket = new DatagramPacket(state.sendBuffer, state.sendBuffer.length);
        state.customSocket.setSoTimeout(5000);

        for (int i = 0; i < 1000; i++) {
            state.senderSocket.send(sendPacket);
            receivePacket.setLength(state.sendBuffer.length);
            state.customSocket.receive(receivePacket);
            bh.consume(receivePacket.getLength());
        }
    }

    /**
     * Benchmark: Burst of 1000 packets with AWS implementation
     */
    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @Warmup(iterations = 3, batchSize = 1000)
    @Measurement(iterations = 10, batchSize = 1000)
    public void burstAWS(SocketState state, Blackhole bh) throws Exception {
        DatagramPacket sendPacket = new DatagramPacket(
            state.awsPayload, 0, state.awsPayload.length,
            state.localhost, state.awsPort
        );

        DatagramPacket receivePacket = new DatagramPacket(state.sendBuffer, state.sendBuffer.length);
        state.awsSocket.setSoTimeout(5000);

        for (int i = 0; i < 1000; i++) {
            state.senderSocket.send(sendPacket);
            receivePacket.setLength(state.sendBuffer.length);
            state.awsSocket.receive(receivePacket);
            bh.consume(receivePacket.getLength());
        }
    }

    /**
     * Benchmark: Parse-only performance for custom implementation
     */
    @Benchmark
    public void parseOnlyCustom(SocketState state, Blackhole bh) throws Exception {
        DatagramPacket receivePacket = new DatagramPacket(
            state.customPayload, 0, state.customPayload.length
        );
        receivePacket.setSocketAddress(new InetSocketAddress(state.localhost, state.senderPort));

        // Simulate the parsing logic
        net.airvantage.proxysocket.core.v2.ProxyHeader header =
            net.airvantage.proxysocket.core.v2.ProxyProtocolV2Decoder.parse(
                receivePacket.getData(),
                receivePacket.getOffset(),
                receivePacket.getLength()
            );

        bh.consume(header);
    }

    /**
     * Benchmark: Parse-only performance for AWS implementation
     */
    @Benchmark
    public void parseOnlyAWS(SocketState state, Blackhole bh) throws Exception {
        java.io.ByteArrayInputStream inputStream = new java.io.ByteArrayInputStream(state.awsPayload);
        com.amazonaws.proprot.ProxyProtocol proxyProtocol = new com.amazonaws.proprot.ProxyProtocol();
        com.amazonaws.proprot.Header header = proxyProtocol.read(inputStream);

        bh.consume(header);
    }
}

