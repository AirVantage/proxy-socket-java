/**
 * BSD-3-Clause License.
 * Copyright (c) 2025 Semtech
 *
 * Validation of ProxyProtocolV2Decoder against hardcoded headers for known cases
 */
package net.airvantage.proxysocket.core.v2;
import net.airvantage.proxysocket.core.ProxyProtocolParseException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ProxyProtocolV2DecoderTest {
    private static final byte[] SIG = "\r\n\r\n\0\r\nQUIT\n".getBytes(StandardCharsets.ISO_8859_1);

    @Test
    void validateRejectsNonHeader() {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        assertThrows(ProxyProtocolParseException.class, () -> ProxyProtocolV2Decoder.parse(data, 0, data.length));
    }

    @Test
    void decodeIPv4Tcp() throws Exception {
        byte verCmd = (byte) 0x21; // v2, PROXY
        byte famProto = (byte) 0x11; // INET4 + STREAM
        byte[] payload = new byte[]{
                // len = 12
                0x00, 0x0C,
                // src 127.0.0.1
                0x7F, 0x00, 0x00, 0x01,
                // dst 127.0.0.2
                0x7F, 0x00, 0x00, 0x02,
                // sport 12345 (0x3039)
                0x30, 0x39,
                // dport 443 (0x01BB)
                0x01, (byte) 0xBB
        };
        byte[] h = new byte[SIG.length + 4 + 12];
        int p = 0; System.arraycopy(SIG, 0, h, p, SIG.length); p += SIG.length;
        h[p++] = verCmd; h[p++] = famProto;
        System.arraycopy(payload, 0, h, p, payload.length);

        ProxyHeader parsed = ProxyProtocolV2Decoder.parse(h, 0, h.length);
        assertEquals(ProxyHeader.Command.PROXY, parsed.getCommand());
        assertEquals(ProxyHeader.AddressFamily.AF_INET, parsed.getFamily());
        assertEquals(ProxyHeader.TransportProtocol.STREAM, parsed.getProtocol());
        assertEquals(12345, parsed.getSourceAddress().getPort());
        assertEquals(443, parsed.getDestinationAddress().getPort());
        assertEquals(h.length, parsed.getHeaderLength());
    }

    @Test
    void decodeIPv6Udp() throws Exception {
        byte verCmd = (byte) 0x21; // v2, PROXY
        byte famProto = (byte) 0x22; // INET6 + DGRAM

        byte[] addr = new byte[2 + 36];
        // len = 36
        addr[0] = 0x00; addr[1] = 0x24;
        int q = 2;
        // src ::1
        for (int i = 0; i < 15; i++) addr[q + i] = 0x00; addr[q + 15] = 0x01; q += 16;
        // dst ::2
        for (int i = 0; i < 15; i++) addr[q + i] = 0x00; addr[q + 15] = 0x02; q += 16;
        // sport 1000 (0x03E8)
        addr[q++] = 0x03; addr[q++] = (byte) 0xE8;
        // dport 2000 (0x07D0)
        addr[q++] = 0x07; addr[q++] = (byte) 0xD0;

        byte[] h = new byte[SIG.length + 4 + 36];
        int p = 0; System.arraycopy(SIG, 0, h, p, SIG.length); p += SIG.length;
        h[p++] = verCmd; h[p++] = famProto;
        System.arraycopy(addr, 0, h, p, addr.length);

        ProxyHeader parsed = ProxyProtocolV2Decoder.parse(h, 0, h.length);
        assertEquals(ProxyHeader.AddressFamily.AF_INET6, parsed.getFamily());
        assertEquals(ProxyHeader.TransportProtocol.DGRAM, parsed.getProtocol());
        assertEquals(1000, parsed.getSourceAddress().getPort());
        assertEquals(2000, parsed.getDestinationAddress().getPort());
        assertEquals(h.length, parsed.getHeaderLength());
    }

    @Test
    void decodeLocal() throws Exception {
        byte verCmd = (byte) 0x20; // v2, LOCAL
        byte famProto = (byte) 0x00; // UNSPEC
        byte[] h = new byte[SIG.length + 4];
        int p = 0; System.arraycopy(SIG, 0, h, p, SIG.length); p += SIG.length;
        h[p++] = verCmd; h[p++] = famProto; h[p++] = 0x00; h[p++] = 0x00;

        ProxyHeader parsed = ProxyProtocolV2Decoder.parse(h, 0, h.length);
        assertTrue(parsed.isLocal());
        assertNull(parsed.getSourceAddress());
        assertEquals(16, parsed.getHeaderLength());
    }

    @Test
    void decodeUnspecWithTlvOnly() throws Exception {
        byte verCmd = (byte) 0x21; // v2, PROXY
        byte famProto = (byte) 0x00; // UNSPEC + UNSPEC

        // TLV: type=0xEE, len=3, value=10 20 30
        byte[] tlv = new byte[]{ (byte) 0xEE, 0x00, 0x03, 0x10, 0x20, 0x30 };
        // variable length = 0 (addr) + TLV len (6)
        byte[] h = new byte[SIG.length + 4 + tlv.length];
        int p = 0; System.arraycopy(SIG, 0, h, p, SIG.length); p += SIG.length;
        h[p++] = verCmd; h[p++] = famProto;
        h[p++] = 0x00; h[p++] = (byte) tlv.length;
        System.arraycopy(tlv, 0, h, p, tlv.length);

        ProxyHeader parsed = ProxyProtocolV2Decoder.parse(h, 0, h.length, true);
        assertEquals(ProxyHeader.AddressFamily.AF_UNSPEC, parsed.getFamily());
        assertNotNull(parsed.getTlvs());
        assertEquals(1, parsed.getTlvs().size());
        assertArrayEquals(new byte[]{0x10, 0x20, 0x30}, parsed.getTlvs().get(0).getValue());
    }

    @Test
    void invalidSignature() throws Exception {
        byte[] h = new byte[SIG.length + 4];
        int p = 0; System.arraycopy(SIG, 0, h, p, SIG.length); p += SIG.length;
        h[0] ^= 0x01; // corrupt
        h[p++] = 0x20; h[p++] = 0x00; h[p++] = 0x00; h[p++] = 0x00;
        assertThrows(ProxyProtocolParseException.class, () -> ProxyProtocolV2Decoder.parse(h, 0, h.length));
    }


    @Test
    void invalidVersion() throws Exception {
        byte verCmd = (byte) 0x31; // v3, LOCAL
        byte famProto = (byte) 0x00; // UNSPEC + UNSPEC
        byte[] h = new byte[SIG.length + 4];
        int p = 0; System.arraycopy(SIG, 0, h, p, SIG.length); p += SIG.length;
        h[p++] = verCmd; h[p++] = famProto;
        h[p++] = 0x00; h[p++] = 0x00;

        assertThrows(ProxyProtocolParseException.class, () -> ProxyProtocolV2Decoder.parse(h, 0, h.length));
    }

    @Test
    void localHeaderHasLength16() throws Exception {
        // Hand-crafted LOCAL header with exactly 16 bytes
        byte verCmd = (byte) 0x20; // v2, LOCAL
        byte famProto = (byte) 0x00; // UNSPEC + UNSPEC
        byte[] h = new byte[SIG.length + 4];
        int p = 0; System.arraycopy(SIG, 0, h, p, SIG.length); p += SIG.length;
        h[p++] = verCmd; h[p++] = famProto;
        h[p++] = 0x00; h[p++] = 0x00; // length = 0

        assertEquals(16, h.length);
        ProxyHeader parsed = ProxyProtocolV2Decoder.parse(h, 0, h.length);
        assertEquals(16, parsed.getHeaderLength());
    }

    @Test
    void addressLengthBeyondBufferInvalid() {
        // Hand-crafted header with declared length exceeding actual data
        byte verCmd = (byte) 0x21; // v2, PROXY
        byte famProto = (byte) 0x11; // INET4 + STREAM
        byte[] h = new byte[SIG.length + 4];
        int p = 0; System.arraycopy(SIG, 0, h, p, SIG.length); p += SIG.length;
        h[p++] = verCmd; h[p++] = famProto;
        h[p++] = 0x00; h[p++] = 0x01; // length = 1 (but no data follows)

        assertThrows(ProxyProtocolParseException.class, () -> ProxyProtocolV2Decoder.parse(h, 0, h.length));
    }

    @Test
    void tlvLengthOverrunIgnored() throws Exception {
        // Hand-crafted header with TLV whose length exceeds available buffer
        byte verCmd = (byte) 0x21; // v2, PROXY
        byte famProto = (byte) 0x11; // INET4 + STREAM

        // IPv4 addresses + ports (12 bytes) + TLV (4 bytes)
        byte[] payload = new byte[]{
                // src 127.0.0.1
                0x7F, 0x00, 0x00, 0x01,
                // dst 127.0.0.2
                0x7F, 0x00, 0x00, 0x02,
                // sport 1234, dport 5678
                0x04, (byte) 0xD2, 0x16, 0x2E,
                // TLV: type=0x01, length=0x7FFF (huge, intentionally invalid)
                0x01, 0x7F, (byte) 0xFF, 0x42
        };

        byte[] h = new byte[SIG.length + 4 + payload.length];
        int p = 0; System.arraycopy(SIG, 0, h, p, SIG.length); p += SIG.length;
        h[p++] = verCmd; h[p++] = famProto;
        h[p++] = (byte) ((payload.length >>> 8) & 0xFF);
        h[p++] = (byte) (payload.length & 0xFF);
        System.arraycopy(payload, 0, h, p, payload.length);

        // Parser should handle gracefully without throwing
        assertThrows(ProxyProtocolParseException.class, () -> ProxyProtocolV2Decoder.parse(h, 0, h.length, true));
    }

}


