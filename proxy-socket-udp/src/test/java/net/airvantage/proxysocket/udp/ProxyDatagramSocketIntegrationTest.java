/*
 * MIT License
 * Copyright (c) 2025 Semtech
 */
package net.airvantage.proxysocket.udp;

import net.airvantage.proxysocket.core.ProxyProtocolMetricsListener;
import net.airvantage.proxysocket.core.cache.ConcurrentMapProxyAddressCache;
import net.airvantage.proxysocket.core.v2.ProxyHeader;
import net.airvantage.proxysocket.core.v2.ProxyProtocolV2Encoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.nginx.NginxContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.output.OutputFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.images.builder.Transferable;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.InternetProtocol;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.Ports.Binding;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;


/**
 * End-to-end UDP integration using an in-process Proxy Protocol v2 injector.
 * The injector emulates a UDP-aware LB (e.g., NGINX/Envoy) that prepends PPv2
 * and forwards datagrams to the backend echo server built on ProxyDatagramSocket.
 */
public class ProxyDatagramSocketIntegrationTest {

    private static final byte[] PAYLOAD = "hello".getBytes(StandardCharsets.UTF_8);
    private static final Logger LOG = LoggerFactory.getLogger(ProxyDatagramSocketIntegrationTest.class);

    private DatagramSocket client;
    private ProxyDatagramSocket backend;
    private DatagramSocket injector;
    private int backendPort;
    private int injectorPort;

    private java.util.concurrent.ExecutorService executor;
    private Future<?> backendLoop;

    @BeforeEach
    void setUp() throws Exception {
        client = new DatagramSocket();
        client.setSoTimeout(3000);

        ConcurrentMapProxyAddressCache cache = new ConcurrentMapProxyAddressCache();
        backend = new ProxyDatagramSocket((new InetSocketAddress(InetAddress.getLoopbackAddress(), 0)))
                .setCache(cache)
                .setMetrics(new NoopMetrics());
        backendPort = backend.getLocalPort();
        LOG.info("Backend listening on 127.0.0.1:{}", backendPort);

//        injector = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
//        injectorPort = injector.getLocalPort();

        // Start backend echo loop
        executor = Executors.newSingleThreadExecutor();
        backendLoop = executor.submit(() -> {
            try {
                byte[] buf = new byte[2048];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                while (!Thread.currentThread().isInterrupted()) {
                    backend.receive(p);

                    LOG.info("Received {} bytes request from {} original address {}", p.getLength(), p.getSocketAddress(), cache.get((InetSocketAddress)p.getSocketAddress()));

                    // Echo back exactly what was after proxy header
                    byte[] echo = new byte[p.getLength()];
                    System.arraycopy(p.getData(), p.getOffset(), echo, 0, p.getLength());
                    p.setData(echo);
                    backend.send(p);
                    p.setData(buf);
                }
            } catch (SocketException ignore) {
                // socket closed during shutdown
            } catch (Exception e) {
                // Allow exceptions to fail the test
                throw new RuntimeException(e);
            }
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        if (backendLoop != null) backendLoop.cancel(true);
        if (executor != null) executor.shutdownNow();
        if (backend != null) backend.close();
 //       if (injector != null) injector.close();
        if (client != null) client.close();
    }

    /*
    @Test
    void udpEndToEnd_withProxyProtocolV2Header() throws Exception {
        // Source perceived by the LB (injector) is client.getLocalAddress():client.getLocalPort()
        InetSocketAddress src = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), client.getLocalPort());
        InetSocketAddress dst = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), backendPort);

        byte[] header = new ProxyProtocolV2Encoder()
                .family(ProxyHeader.AddressFamily.INET4)
                .socket(ProxyHeader.TransportProtocol.DGRAM)
                .source(src)
                .destination(dst)
                .build();

        byte[] out = new byte[header.length + PAYLOAD.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(PAYLOAD, 0, out, header.length, PAYLOAD.length);

        // Send to injector; injector forwards to backend and back to client
        DatagramPacket toInjector = new DatagramPacket(out, out.length, new InetSocketAddress("127.0.0.1", injectorPort));
        // Set up injector forwarder (bi-directional for the test)
        BlockingQueue<byte[]> forwardQueue = new ArrayBlockingQueue<>(1);
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                byte[] buf = new byte[4096];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                // Receive from client
                injector.receive(p);
                InetSocketAddress clientAddr = (InetSocketAddress) p.getSocketAddress();
                byte[] recv = new byte[p.getLength()];
                System.arraycopy(p.getData(), p.getOffset(), recv, 0, p.getLength());
                forwardQueue.add(recv);

                // Forward to backend
                DatagramPacket toBackend = new DatagramPacket(recv, recv.length, new InetSocketAddress("127.0.0.1", backendPort));
                injector.send(toBackend);

                // Await response from backend
                DatagramPacket fromBackend = new DatagramPacket(new byte[4096], 4096);
                injector.receive(fromBackend);
                byte[] backendResp = new byte[fromBackend.getLength()];
                System.arraycopy(fromBackend.getData(), fromBackend.getOffset(), backendResp, 0, fromBackend.getLength());

                // Forward back to original client
                DatagramPacket backToClient = new DatagramPacket(backendResp, backendResp.length, clientAddr);
                injector.send(backToClient);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        client.send(toInjector);

        // Receive echo back through backend -> injector -> client
        DatagramPacket resp = new DatagramPacket(new byte[4096], 4096);
        resp.setLength(4096);
        client.receive(resp);

        String respStr = new String(resp.getData(), resp.getOffset(), resp.getLength(), StandardCharsets.UTF_8);
        assertEquals("hello", respStr);

        // Sanity: ensure injector forwarded the PROXY header + payload to backend
        byte[] forwarded = forwardQueue.poll(2, java.util.concurrent.TimeUnit.SECONDS);
        assertNotNull(forwarded);
        assertTrue(forwarded.length >= header.length + PAYLOAD.length);
    } */

    @Test
    void udpEndToEnd_viaNginxContainer_proxyProtocolV2() throws Exception {
        // Expose the host backend port to containers using Testcontainers' gateway helper
        Testcontainers.exposeHostPorts(backendPort);

        int nginxInternalPort = 5684; // container internal UDP listen port

        String nginxConf = "worker_processes  1;\n" +
                "events { worker_connections 1024; }\n" +
                "stream {\n" +
                "  upstream backend { server host.docker.internal:" + backendPort + "; }\n" +
                "    log_format proxy '$remote_addr [$time_local] '\n" +
                "     '$protocol $status $bytes_sent $bytes_received '\n" +
                "     '$session_time \"$upstream_addr\" '\n" +
                "     '\"$upstream_bytes_sent\" \"$upstream_bytes_received\" \"$upstream_connect_time\"';" +
                "  server {\n" +
                "    listen 0.0.0.0:" + nginxInternalPort + " udp;\n" +
                "    proxy_pass backend;\n" +
                "    proxy_responses 1;\n" +
                "    proxy_timeout 5s;\n" +
                "    proxy_protocol on;\n" +
                "    access_log stdout proxy;\n" +
                "  }\n" +
                "}\n";

        ExposedPort udp = new ExposedPort(nginxInternalPort, InternetProtocol.UDP);

        GenericContainer<?> nginx = new GenericContainer<>(DockerImageName.parse("nginx:1.29-alpine"))
                .withCopyToContainer(Transferable.of(nginxConf.getBytes(StandardCharsets.UTF_8)), "/etc/nginx/nginx.conf")
                //.withCommand("nginx", "-g", "daemon off;")
                .withCreateContainerCmdModifier(cmd -> {
                    List<ExposedPort> exposedPorts = new ArrayList<>();
                    for (ExposedPort p : cmd.getExposedPorts()) {
                        exposedPorts.add(p);
                    }
                    exposedPorts.add(udp);
                    cmd.withExposedPorts(exposedPorts);

                    //Add previous port bindings and UDP port binding
                    Ports ports = cmd.getPortBindings();
                    ports.bind(udp, Ports.Binding.bindIp("0.0.0.0"));
                    cmd.withPortBindings(ports);
                })
                .withLogConsumer(new Slf4jLogConsumer(LOG).withSeparateOutputStreams());
        nginx.start();

        String containerIpAddress = nginx.getHost();
        Ports.Binding[] bindings = nginx.getContainerInfo().getNetworkSettings().getPorts().getBindings().get(udp);
        int containerPort = Integer.parseInt(bindings[0].getHostPortSpec());
        LOG.info("NGINX container host: {}, mapped UDP port: {} -> {}:{}", containerIpAddress, nginxInternalPort, containerIpAddress, containerPort);

        try {
            // Java client sends to mapped host UDP port and expects echo
            DatagramSocket sock = new DatagramSocket();
            sock.setSoTimeout(300000);
            byte[] data = PAYLOAD;
            DatagramPacket toNginx = new DatagramPacket(data, data.length, new InetSocketAddress(containerIpAddress, containerPort));
            LOG.info("Sending {} bytes to {}:{}", data.length, containerIpAddress, containerPort);
            sock.send(toNginx);

            DatagramPacket resp = new DatagramPacket(new byte[4096], 4096);
            sock.receive(resp);
            String respStr = new String(resp.getData(), resp.getOffset(), resp.getLength(), StandardCharsets.UTF_8);
            LOG.info("Received {} bytes response: '{}'", resp.getLength(), respStr);
            assertEquals("hello", respStr);
            sock.close();
        } finally {
            try { nginx.stop(); } catch (Throwable ignore) {}
        }
    }

    static class NoopMetrics implements ProxyProtocolMetricsListener {
        @Override public void onHeaderParsed(ProxyHeader header) { }
        @Override public void onParseError(Exception e) { }
        @Override public void onCacheHit(InetSocketAddress client) { }
        @Override public void onCacheMiss(InetSocketAddress client) { }
    }
}


