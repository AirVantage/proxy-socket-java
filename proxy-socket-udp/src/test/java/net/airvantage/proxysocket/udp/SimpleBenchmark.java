/*
 * MIT License
 * Copyright (c) 2025 Semtech
 */
package net.airvantage.proxysocket.udp;

import com.amazonaws.proprot.ProxyProtocol;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import net.airvantage.proxysocket.core.v2.ProxyHeader;
import net.airvantage.proxysocket.core.v2.ProxyProtocolV2Decoder;
import net.airvantage.proxysocket.core.v2.ProxyProtocolV2Encoder;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Simple benchmark to compare Custom vs AWS ProProt vs Netty HAProxy implementations.
 */
public class SimpleBenchmark {

    private static class BenchmarkResult {
        String name;
        int packetSize;
        long iterations;
        long totalTimeNs;
        double avgTimeNs;
        double throughputOpsPerSec;

        @Override
        public String toString() {
            return String.format("%s [%dB]: %.2f ns/op, %.0f ops/sec",
                name, packetSize, avgTimeNs, throughputOpsPerSec);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(80));
        System.out.println("ProxyDatagramSocket Benchmark - Custom vs AWS ProProt vs Netty");
        System.out.println("=".repeat(80));
        System.out.println();

        int[] packetSizes = {64, 512, 1500};
        int warmupIterations = 5000;
        int measurementIterations = 50000;

        List<BenchmarkResult> results = new ArrayList<>();

        for (int packetSize : packetSizes) {
            System.out.println("Testing packet size: " + packetSize + " bytes");
            System.out.println("-".repeat(80));

            // Prepare test data
            byte[] appData = new byte[packetSize];
            for (int i = 0; i < appData.length; i++) {
                appData[i] = (byte) (i % 256);
            }

            InetAddress localhost = InetAddress.getLoopbackAddress();
            InetSocketAddress srcAddr = new InetSocketAddress(localhost, 12345);
            InetSocketAddress dstAddr = new InetSocketAddress(localhost, 8080);

            // Create proxy protocol header + payload
            byte[] proxyHeader = new ProxyProtocolV2Encoder()
                .command(ProxyHeader.Command.PROXY)
                .family(ProxyHeader.AddressFamily.INET4)
                .socket(ProxyHeader.TransportProtocol.DGRAM)
                .source(srcAddr)
                .destination(dstAddr)
                .build();

            byte[] payload = new byte[proxyHeader.length + appData.length];
            System.arraycopy(proxyHeader, 0, payload, 0, proxyHeader.length);
            System.arraycopy(appData, 0, payload, proxyHeader.length, appData.length);

            // Benchmark Custom Implementation
            results.add(benchmarkCustom(payload, packetSize, warmupIterations, measurementIterations));

            // Benchmark AWS Implementation
            results.add(benchmarkAWS(payload, packetSize, warmupIterations, measurementIterations));

            // Benchmark Netty Implementation
            results.add(benchmarkNetty(payload, packetSize, warmupIterations, measurementIterations));

            System.out.println();
        }

        // Print summary
        System.out.println("=".repeat(80));
        System.out.println("SUMMARY");
        System.out.println("=".repeat(80));
        for (BenchmarkResult result : results) {
            System.out.println(result);
        }

        // Calculate speedup/slowdown
        System.out.println();
        System.out.println("=".repeat(80));
        System.out.println("RELATIVE PERFORMANCE (Custom as baseline)");
        System.out.println("=".repeat(80));
        for (int i = 0; i < results.size(); i += 3) {
            BenchmarkResult custom = results.get(i);
            BenchmarkResult aws = results.get(i + 1);
            BenchmarkResult netty = results.get(i + 2);

            double awsRatio = aws.avgTimeNs / custom.avgTimeNs;
            String awsComparison = awsRatio > 1.0 ?
                String.format("AWS is %.2fx SLOWER", awsRatio) :
                String.format("AWS is %.2fx FASTER", 1.0 / awsRatio);

            double nettyRatio = netty.avgTimeNs / custom.avgTimeNs;
            String nettyComparison = nettyRatio > 1.0 ?
                String.format("Netty is %.2fx SLOWER", nettyRatio) :
                String.format("Netty is %.2fx FASTER", 1.0 / nettyRatio);

            System.out.println(String.format("[%dB] %s | %s", custom.packetSize, awsComparison, nettyComparison));
        }
    }

    private static BenchmarkResult benchmarkCustom(byte[] payload, int packetSize,
                                                    int warmup, int iterations) throws Exception {
        System.out.print("  Custom Implementation: warming up...");

        // Warmup
        for (int i = 0; i < warmup; i++) {
            ProxyProtocolV2Decoder.parse(payload, 0, payload.length);
        }

        System.out.print(" measuring...");

        // Measurement
        long startTime = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            ProxyProtocolV2Decoder.parse(payload, 0, payload.length);
        }
        long endTime = System.nanoTime();

        long totalTime = endTime - startTime;
        double avgTime = (double) totalTime / iterations;
        double throughput = 1_000_000_000.0 / avgTime;

        System.out.println(" done!");
        System.out.println(String.format("    Average: %.2f ns/op", avgTime));
        System.out.println(String.format("    Throughput: %.0f ops/sec", throughput));

        BenchmarkResult result = new BenchmarkResult();
        result.name = "Custom";
        result.packetSize = packetSize;
        result.iterations = iterations;
        result.totalTimeNs = totalTime;
        result.avgTimeNs = avgTime;
        result.throughputOpsPerSec = throughput;

        return result;
    }

    private static BenchmarkResult benchmarkAWS(byte[] payload, int packetSize,
                                                 int warmup, int iterations) throws Exception {
        System.out.print("  AWS ProProt:           warming up...");

        ProxyProtocol parser = new ProxyProtocol();
        parser.setEnforceChecksum(false); // Disable checksum enforcement for benchmark

        // Warmup
        for (int i = 0; i < warmup; i++) {
            ByteArrayInputStream is = new ByteArrayInputStream(payload);
            parser.read(is);
        }

        System.out.print(" measuring...");

        // Measurement
        long startTime = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            ByteArrayInputStream is = new ByteArrayInputStream(payload);
            parser.read(is);
        }
        long endTime = System.nanoTime();

        long totalTime = endTime - startTime;
        double avgTime = (double) totalTime / iterations;
        double throughput = 1_000_000_000.0 / avgTime;

        System.out.println(" done!");
        System.out.println(String.format("    Average: %.2f ns/op", avgTime));
        System.out.println(String.format("    Throughput: %.0f ops/sec", throughput));

        BenchmarkResult result = new BenchmarkResult();
        result.name = "AWS";
        result.packetSize = packetSize;
        result.iterations = iterations;
        result.totalTimeNs = totalTime;
        result.avgTimeNs = avgTime;
        result.throughputOpsPerSec = throughput;

        return result;
    }

    private static BenchmarkResult benchmarkNetty(byte[] payload, int packetSize,
                                                   int warmup, int iterations) throws Exception {
        System.out.print("  Netty HAProxy:         warming up...");

        // Warmup
        for (int i = 0; i < warmup; i++) {
            ByteBuf buffer = Unpooled.wrappedBuffer(payload);
            NettyDecoder decoder = new NettyDecoder();
            List<Object> out = new ArrayList<>();
            try {
                decoder.decodePublic(new ProxyDatagramSocketNetty.NoOpChannelHandlerContext(), buffer, out);
                if (!out.isEmpty()) {
                    ((io.netty.handler.codec.haproxy.HAProxyMessage) out.get(0)).release();
                }
            } catch (Exception e) {
                // Expected for invalid/incomplete messages
            } finally {
                buffer.release();
            }
        }

        System.out.print(" measuring...");

        // Measurement
        long startTime = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            ByteBuf buffer = Unpooled.wrappedBuffer(payload);
            NettyDecoder decoder = new NettyDecoder();
            List<Object> out = new ArrayList<>();
            try {
                decoder.decodePublic(new ProxyDatagramSocketNetty.NoOpChannelHandlerContext(), buffer, out);
                if (!out.isEmpty()) {
                    ((io.netty.handler.codec.haproxy.HAProxyMessage) out.get(0)).release();
                }
            } catch (Exception e) {
                // Expected for invalid/incomplete messages
            } finally {
                buffer.release();
            }
        }
        long endTime = System.nanoTime();

        long totalTime = endTime - startTime;
        double avgTime = (double) totalTime / iterations;
        double throughput = 1_000_000_000.0 / avgTime;

        System.out.println(" done!");
        System.out.println(String.format("    Average: %.2f ns/op", avgTime));
        System.out.println(String.format("    Throughput: %.0f ops/sec", throughput));

        BenchmarkResult result = new BenchmarkResult();
        result.name = "Netty";
        result.packetSize = packetSize;
        result.iterations = iterations;
        result.totalTimeNs = totalTime;
        result.avgTimeNs = avgTime;
        result.throughputOpsPerSec = throughput;

        return result;
    }

    /**
     * Wrapper for HAProxyMessageDecoder that exposes the decode method.
     */
    private static class NettyDecoder extends HAProxyMessageDecoder {
        public void decodePublic(io.netty.channel.ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
            decode(ctx, in, out);
        }
    }
}

