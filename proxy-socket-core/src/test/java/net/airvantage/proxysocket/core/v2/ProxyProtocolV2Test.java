package net.airvantage.proxysocket.core.v2;

import net.airvantage.proxysocket.core.ProxyProtocolParseException;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ProxyProtocolV2Test {
    @Test
    void validateRejectsNonHeader() {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        assertEquals(0, ProxyProtocolV2.validate(data, 0, data.length));
    }

    @Test
    void parseIPv4Tcp() throws Exception {
        var header = new ProxyProtocolV2Builder()
                .family(ProxyHeader.AddressFamily.INET4)
                .socket(ProxyHeader.TransportProtocol.STREAM)
                .source(new InetSocketAddress("127.0.0.1", 12345))
                .destination(new InetSocketAddress("127.0.0.2", 443))
                .build();
        int len = ProxyProtocolV2.validate(header, 0, header.length);
        assertTrue(len > 0);
        ProxyHeader parsed = ProxyProtocolV2.parse(header, 0, header.length);
        assertEquals(ProxyHeader.Command.PROXY, parsed.getCommand());
        assertEquals(ProxyHeader.AddressFamily.INET4, parsed.getFamily());
        assertEquals(ProxyHeader.TransportProtocol.STREAM, parsed.getProtocol());
        assertEquals(12345, parsed.getSourceAddress().getPort());
        assertEquals(443, parsed.getDestinationAddress().getPort());
    }

    @Test
    void parseIPv6UdpWithTlv() throws Exception {
        var header = new ProxyProtocolV2Builder()
                .family(ProxyHeader.AddressFamily.INET6)
                .socket(ProxyHeader.TransportProtocol.DGRAM)
                .source(new InetSocketAddress("::1", 1000))
                .destination(new InetSocketAddress("::2", 2000))
                .addTlv(0x01, new byte[]{0x41, 0x42})
                .build();
        int len = ProxyProtocolV2.validate(header, 0, header.length);
        assertTrue(len > 0);
        ProxyHeader parsed = ProxyProtocolV2.parse(header, 0, header.length);
        assertEquals(ProxyHeader.AddressFamily.INET6, parsed.getFamily());
        assertEquals(ProxyHeader.TransportProtocol.DGRAM, parsed.getProtocol());
        assertEquals(1, parsed.getTlvs().size());
        assertArrayEquals(new byte[]{0x41, 0x42}, parsed.getTlvs().get(0).getValue());
    }

    @Test
    void parseLocal() throws Exception {
        var header = new ProxyProtocolV2Builder()
                .command(ProxyHeader.Command.LOCAL)
                .build();
        int len = ProxyProtocolV2.validate(header, 0, header.length);
        assertTrue(len > 0);
        ProxyHeader parsed = ProxyProtocolV2.parse(header, 0, header.length);
        assertTrue(parsed.isLocal());
        assertNull(parsed.getSourceAddress());
    }

    @Test
    void invalidVersionThrows() {
        byte[] h = new ProxyProtocolV2Builder().build();
        // corrupt version nibble
        h[12] = (byte) ((1 << 4) | 0x01);
        assertThrows(ProxyProtocolParseException.class, () -> ProxyProtocolV2.parse(h, 0, h.length));
    }

    @Test
    void truncatedHeaderInvalid() {
        byte[] h = new ProxyProtocolV2Builder().build();
        assertEquals(0, ProxyProtocolV2.validate(h, 0, 10));
    }

    @Test
    void tlvLengthOverrunIgnored() throws Exception {
        // Build header then break TLV length to exceed buffer; parser should stop TLV loop gracefully
        var builder = new ProxyProtocolV2Builder()
                .addTlv(0x01, new byte[]{0x01});
        byte[] h = builder.build();
        // Set TLV length to something large
        int tlvStart = h.length - 3 - 1; // type(1) + len(2) + value(1)
        h[tlvStart + 1] = (byte) 0x7F;
        h[tlvStart + 2] = (byte) 0x7F;
        ProxyHeader parsed = ProxyProtocolV2.parse(h, 0, h.length);
        assertNotNull(parsed);
    }

    @Test
    void parseIPv4Udp() throws Exception {
        var header = new ProxyProtocolV2Builder()
                .family(ProxyHeader.AddressFamily.INET4)
                .socket(ProxyHeader.TransportProtocol.DGRAM)
                .source(new InetSocketAddress("127.0.0.1", 1111))
                .destination(new InetSocketAddress("127.0.0.2", 2222))
                .build();
        assertTrue(ProxyProtocolV2.validate(header, 0, header.length) > 0);
        ProxyHeader parsed = ProxyProtocolV2.parse(header, 0, header.length);
        assertEquals(ProxyHeader.TransportProtocol.DGRAM, parsed.getProtocol());
        assertEquals(1111, parsed.getSourceAddress().getPort());
        assertEquals(2222, parsed.getDestinationAddress().getPort());
    }

    @Test
    void localHeaderHasLength16() throws Exception {
        var header = new ProxyProtocolV2Builder()
                .command(ProxyHeader.Command.LOCAL)
                .build();
        assertEquals(16, header.length);
        assertEquals(16, ProxyProtocolV2.validate(header, 0, header.length));
    }

    @Test
    void invalidSignatureRejected() {
        byte[] h = new ProxyProtocolV2Builder().build();
        h[0] ^= 0x01; // corrupt signature
        assertEquals(0, ProxyProtocolV2.validate(h, 0, h.length));
    }

    @Test
    void addressLengthBeyondBufferInvalid() {
        byte[] h = new ProxyProtocolV2Builder().build();
        // Bump declared length by one without providing data
        int lenPos = 14;
        int len = ((h[lenPos] & 0xFF) << 8) | (h[lenPos + 1] & 0xFF);
        len += 1;
        h[lenPos] = (byte) ((len >>> 8) & 0xFF);
        h[lenPos + 1] = (byte) (len & 0xFF);
        assertEquals(0, ProxyProtocolV2.validate(h, 0, h.length));
    }

    @Test
    void proxyUnspecWithTlvOnly() throws Exception {
        var header = new ProxyProtocolV2Builder()
                .family(ProxyHeader.AddressFamily.UNSPEC)
                .socket(ProxyHeader.TransportProtocol.UNSPEC)
                .addTlv(0xEE, new byte[]{0x10, 0x20, 0x30})
                .build();
        assertTrue(ProxyProtocolV2.validate(header, 0, header.length) > 0);
        ProxyHeader parsed = ProxyProtocolV2.parse(header, 0, header.length);
        assertEquals(ProxyHeader.AddressFamily.UNSPEC, parsed.getFamily());
        assertEquals(1, parsed.getTlvs().size());
        assertArrayEquals(new byte[]{0x10, 0x20, 0x30}, parsed.getTlvs().get(0).getValue());
    }
}


