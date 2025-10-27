Proxy Socket Java (UDP + TCP, Java 17)
=======================================

Overview
--------
Library providing HAProxy Proxy Protocol v2 support for UDP and TCP. Multi-module layout:

- proxy-socket-core: zero dependencies, parser, models, interfaces
- proxy-socket-udp: DatagramSocket wrapper
- proxy-socket-tcp: ServerSocket/Socket wrappers
- proxy-socket-guava: optional Guava-based cache
- proxy-socket-examples: runnable samples

Quick start (UDP)
-----------------
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

Quick start (TCP)
-----------------
```java
try (var server = new net.airvantage.proxysocket.tcp.ProxyServerSocket(9998)) {
  for (;;) {
    var s = (net.airvantage.proxysocket.tcp.ProxySocket) server.accept();
    var header = s.getHeader();
    // header.getSourceAddress() is the real client address
  }
}
```

License
-------
MIT License © 2025 Semtech. See `LICENSE`.

Metrics hook
------------
Implement `net.airvantage.proxysocket.core.ProxySocketMetricsListener` and pass it via UDP builder or TCP server ctor.

Thread safety
-------------
- UDP/TCP wrappers follow JDK `DatagramSocket`/`ServerSocket`/`Socket` thread-safety; caches and listeners must be thread-safe.
- Core parser is stateless and thread-safe.

Configuration
-------------
- UDP cache defaults: 10k entries, 5 min TTL if Guava present; otherwise concurrent map (no TTL).
- TCP: blocking header read on accept with configurable timeout.

Examples
--------
See `proxy-socket-examples` module: `UdpEchoWithProxyProtocol`, `TcpEchoWithProxyProtocol`.


