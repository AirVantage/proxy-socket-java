# Proxy Socket Java (UDP, Java 17)

## Overview

Library providing HAProxy Proxy Protocol v2 support for UDP and TCP. Multi-module layout:

- proxy-socket-core: zero dependencies, parser, models, interfaces
- proxy-socket-udp: DatagramSocket wrapper
- proxy-socket-guava: optional Guava-based cache

Reference: [HAProxy Proxy Protocol Specifications](https://www.haproxy.org/download/3.3/doc/proxy-protocol.txt)

## Quick start (UDP)

```java
var socket = new net.airvantage.proxysocket.udp.ProxyDatagramSocket.Builder()
    .maxEntries(10_000)
    .ttl(java.time.Duration.ofMinutes(5))
    .metrics(new MyMetrics())
    .build();
socket.bind(new java.net.InetSocketAddress(9999));
var buf = new byte[2048];
var packet = new java.net.DatagramPacket(buf, buf.length);
socket.receive(packet); // header stripped, source set to real client
socket.send(packet);    // destination rewritten to LB if cached
```

## License

BSD-3-Clause License © 2025 Semtech. See [LICENSE.BSD-3-Clause](./LICENSE.BSD-3-Clause).

## Metrics hook

Implement `net.airvantage.proxysocket.core.ProxySocketMetricsListener` and pass it via UDP builder or TCP server ctor.

## Thread safety

- UDP/TCP wrappers follow JDK `DatagramSocket`/`ServerSocket`/`Socket` thread-safety; caches and listeners must be thread-safe.
- Core parser is stateless and thread-safe.

## Configuration

- UDP cache defaults: 10k entries, 5 min TTL if Guava present; otherwise concurrent map (no TTL).
- TCP: blocking header read on accept with configurable timeout.

## Examples

See `proxy-socket-examples` module: `UdpEchoWithProxyProtocol`, `TcpEchoWithProxyProtocol`.
