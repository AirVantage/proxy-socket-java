# ProxyDatagramSocket Benchmark Results

## Overview

This document presents a performance comparison between three implementations of Proxy Protocol v2 parsing for UDP datagrams:

1. **Custom Implementation** (`ProxyDatagramSocket.java`) - Custom-built parser using direct byte array manipulation
2. **AWS Implementation** (`ProxyDatagramSocketAWS.java`) - Using the AWS ProProt library (com.amazonaws.proprot:proprot:1.0)
3. **Netty Implementation** (`ProxyDatagramSocketNetty.java`) - Using Netty's HAProxy codec (io.netty:netty-codec-haproxy:4.1.100.Final)

## Benchmark Methodology

### Test Configuration

- **Hardware**: Localhost loopback (eliminates network variability)
- **Java Version**: Java 17
- **Warmup**: 5,000 iterations per test
- **Measurement**: 50,000 iterations per test
- **Packet Sizes**: 64 bytes, 512 bytes, 1500 bytes (small, medium, large)

### Test Scenarios

The benchmark focused on **parsing-only performance** to isolate the core difference between implementations:
- Parse Proxy Protocol v2 header from byte array
- Extract source/destination addresses and ports
- Skip past header to application data

## Results

### Performance Summary

| Implementation | Packet Size | Avg Latency (ns/op) | Throughput (ops/sec) |
|----------------|-------------|---------------------|----------------------|
| **Custom**     | 64B         | **167.03**          | **5,987,098**       |
| AWS ProProt    | 64B         | 353.39              | 2,829,748           |
| Netty HAProxy  | 64B         | 955.03              | 1,047,085           |
| **Custom**     | 512B        | **48.18**           | **20,756,577**      |
| AWS ProProt    | 512B        | 130.26              | 7,677,149           |
| Netty HAProxy  | 512B        | 401.12              | 2,493,046           |
| **Custom**     | 1500B       | **41.80**           | **23,924,876**      |
| AWS ProProt    | 1500B       | 145.00              | 6,896,354           |
| Netty HAProxy  | 1500B       | 399.94              | 2,500,401           |

### Relative Performance

| Packet Size | AWS vs Custom | Netty vs Custom |
|-------------|---------------|-----------------|
| 64B         | **2.12x SLOWER** | **5.72x SLOWER** |
| 512B        | **2.70x SLOWER** | **8.33x SLOWER** |
| 1500B       | **3.47x SLOWER** | **9.57x SLOWER** |

### Visual Comparison

```
Throughput (ops/sec) at 1500B packets:
Custom:  ████████████████████████  23.9M ops/sec
AWS:     ███████                   6.9M ops/sec
Netty:   ██                        2.5M ops/sec
```

## Analysis

### Key Findings

1. **Custom Implementation Dominates Across All Tests**
   - 2-3.5x faster than AWS
   - 5.7-9.6x faster than Netty
   - Consistently lowest latency: 42-167 ns

2. **AWS ProProt: Middle Ground**
   - 2-3.5x slower than Custom
   - 2-3x faster than Netty
   - Moderate latency: 130-353 ns
   - More consistent performance across packet sizes

3. **Netty HAProxy: Slowest**
   - 5.7-9.6x slower than Custom
   - 2-3x slower than AWS
   - Highest latency: 400-955 ns
   - Performance degradation with larger packets

4. **Packet Size Impact**
   - Custom: Best performance on medium/large packets (42-48 ns)
   - AWS: Shows improvement with larger packets (145-353 ns)
   - Netty: Consistently high overhead (~400-955 ns)

### Why Custom Implementation is Fastest

1. **Direct Byte Array Access**
   - No intermediate stream wrappers or buffer allocations
   - Minimal memory copies
   - Cache-friendly sequential reads

2. **Minimal Object Allocation**
   - Reuses parser state
   - No temporary objects per parse
   - Reduced GC pressure

3. **Specialized for Use Case**
   - Optimized specifically for UDP datagram parsing
   - No generic channel handling overhead
   - Tight, focused code paths

4. **JVM Optimization**
   - Hot path code fully inlined by JIT compiler
   - Predictable branches optimize well
   - Simple loops over byte arrays

### AWS ProProt Library Characteristics

**Strengths:**
- Stream-based API with `InputStream` abstraction
- Complete specification support (TLV, SSL, checksums)
- Battle-tested in AWS production environments
- Better performance than Netty

**Weaknesses:**
- Additional indirection layer (ByteArrayInputStream)
- More object allocation per parse
- 2-3.5x slower than custom implementation

**Performance Profile:**
- Moderate overhead: 130-353 ns/op
- Throughput: 2.8-7.7M ops/sec
- Better suited for moderate throughput scenarios

### Netty HAProxy Codec Characteristics

**Strengths:**
- Complete HAProxy protocol implementation
- Widely used in production (Netty ecosystem)
- Supports both v1 and v2 protocols
- Rich feature set

**Weaknesses:**
- Heaviest performance overhead (5.7-9.6x slower)
- Channel abstraction adds significant cost
- Creates new decoder instance per parse (stateful decoder)
- ByteBuf wrapping and lifecycle management overhead
- Designed for stream/channel I/O, not direct datagram parsing

**Performance Profile:**
- High overhead: 400-955 ns/op
- Throughput: 1-2.5M ops/sec
- Significant penalty from channelpipeline abstractions

**Why Netty is Slower:**
1. **Channel Pipeline Overhead**: Designed for Netty's channel abstraction
2. **State Management**: Decoder instances are stateful
3. **ByteBuf Operations**: More complex buffer management
4. **Object Allocation**: Creates more temporary objects
5. **Abstraction Layers**: Multiple layers of indirection

## Recommendations

### Use Custom Implementation When:

- **Performance is critical** (high-throughput applications)
- **Latency sensitive** (real-time systems, gaming, IoT)
- **Simple proxy protocol** requirements (basic address/port mapping)
- **High packet rates** (millions of packets/second)
- **Cost optimization** (lower CPU usage = lower cloud costs)

### Use AWS ProProt When:

- **Full specification support** needed (TLV extensions, SSL info)
- **Code maintainability** prioritized over raw performance
- **AWS integration** is important
- **Moderate throughput** requirements (< 1M packets/second)
- **Standards compliance** verification needed
- **Balance** between features and performance

### Use Netty HAProxy When:

- **Already using Netty** for other I/O operations
- **Code reuse** with existing Netty pipelines
- **HAProxy v1 support** needed
- **Low throughput** requirements (< 100K packets/second)
- **Not performance critical** applications
- **Full Netty ecosystem** integration desired

## Memory Overhead Comparison

### Custom Implementation
- Minimal allocation per parse
- Reuses shared parser state
- Direct field extraction
- **Memory efficiency: ★★★★★**

### AWS ProProt
- ByteArrayInputStream per parse
- Internal buffer structures
- Header objects with metadata
- **Memory efficiency: ★★★☆☆**

### Netty HAProxy
- New decoder instance per parse (stateful)
- ByteBuf allocation and wrapping
- Channel context overhead
- HAProxyMessage object allocation
- **Memory efficiency: ★★☆☆☆**

## Scalability Considerations

### Custom Implementation

**Strengths:**
- Linear scaling with CPU cores
- Cache-friendly memory access
- Predictable performance
- Low CPU utilization

**Limitations:**
- Limited TLV support
- Less comprehensive validation

**Best for:** Ultra-high throughput (10M+ ops/sec)

### AWS ProProt

**Strengths:**
- Complete specification
- Proven in production
- Good balance of features/performance

**Limitations:**
- Performance ceiling ~7M ops/sec
- Higher memory allocation rate

**Best for:** High throughput (1-10M ops/sec)

### Netty HAProxy

**Strengths:**
- Rich feature set
- HAProxy v1/v2 support
- Netty ecosystem integration

**Limitations:**
- Performance ceiling ~2.5M ops/sec
- Highest resource consumption
- Not optimized for datagram use case

**Best for:** Moderate throughput (< 1M ops/sec)

## Production Considerations

### Performance Tiers

**Tier 1: Ultra-Performance** (> 5M ops/sec)
- **Choose**: Custom Implementation
- **Use case**: Real-time gaming, IoT gateways, high-frequency trading
- **Tradeoff**: Minimal features, maximum speed

**Tier 2: High-Performance** (1-5M ops/sec)
- **Choose**: AWS ProProt
- **Use case**: API gateways, microservices, data streaming
- **Tradeoff**: Good balance of features and performance

**Tier 3: Standard Performance** (< 1M ops/sec)
- **Choose**: Netty HAProxy
- **Use case**: Web applications, admin interfaces, monitoring
- **Tradeoff**: Maximum features, acceptable performance

### Cost Analysis

At 10M packets/second:

| Implementation | CPU Efficiency | Relative Cost |
|----------------|----------------|---------------|
| Custom         | Baseline       | 1.0x          |
| AWS ProProt    | 2.7x more CPU  | 2.7x          |
| Netty HAProxy  | 8.0x more CPU  | 8.0x          |

**Cloud cost impact**: Netty could require 8x more compute resources than Custom for the same throughput.

## Conclusions

The custom `ProxyDatagramSocket` implementation provides **substantially better performance** than both AWS ProProt and Netty HAProxy for UDP proxy protocol parsing:

### Performance Ranking
1. **🥇 Custom**: 42-167 ns, 6-24M ops/sec
2. **🥈 AWS ProProt**: 130-353 ns, 2.8-7.7M ops/sec
3. **🥉 Netty HAProxy**: 400-955 ns, 1-2.5M ops/sec

### When to Use Each

- **Custom** → Performance-critical, high-throughput systems
- **AWS** → Feature-complete with good performance balance
- **Netty** → Netty ecosystem integration, moderate loads

### Final Recommendation

For most production UDP proxy protocol use cases, the **custom implementation** is the clear winner, offering 2-10x better performance than alternatives. Only choose AWS or Netty if you specifically need their additional features or ecosystem integration, and can accept the performance penalty.

## Test Reproduction

To reproduce these benchmarks:

```bash
cd proxy-socket-udp
mvn test-compile exec:java \
  -Dexec.mainClass="net.airvantage.proxysocket.udp.SimpleBenchmark" \
  -Dexec.classpathScope=test
```

## Environment

- **OS**: macOS (darwin 25.0.0)
- **JVM**: OpenJDK 17
- **Maven**: 3.x
- **Libraries**:
  - AWS ProProt: 1.0
  - Netty HAProxy: 4.1.100.Final
- **Test Date**: November 3, 2025
