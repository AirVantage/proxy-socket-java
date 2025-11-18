/*
 * MIT License
 * Copyright (c) 2025 Semtech

 * Validation of ProxyProtocolV2Decoder using AWS ProProt library
 */
package net.airvantage.proxysocket.core.v2;

import net.airvantage.proxysocket.core.ProxyProtocolParseException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ProxyProtocolV2Test {
    @Test
    void parseIPv4Tcp() throws Exception {
        var header = new AwsProxyEncoderHelper()
                .family(ProxyHeader.AddressFamily.AF_INET)
                .socket(ProxyHeader.TransportProtocol.STREAM)
                .source(new InetSocketAddress("127.0.0.1", 12345))
                .destination(new InetSocketAddress("127.0.0.2", 443))
                .build();

        ProxyHeader parsed = ProxyProtocolV2Decoder.parse(header, 0, header.length);
        assertEquals(ProxyHeader.Command.PROXY, parsed.getCommand());
        assertEquals(ProxyHeader.AddressFamily.AF_INET, parsed.getFamily());
        assertEquals(ProxyHeader.TransportProtocol.STREAM, parsed.getProtocol());
        assertEquals(12345, parsed.getSourceAddress().getPort());
        assertEquals(443, parsed.getDestinationAddress().getPort());
    }

    @Test
    void parseIPv6UdpWithTlv() throws Exception {
        var header = new AwsProxyEncoderHelper()
                .family(ProxyHeader.AddressFamily.AF_INET6)
                .socket(ProxyHeader.TransportProtocol.DGRAM)
                .source(new InetSocketAddress("::1", 1000))
                .destination(new InetSocketAddress("::2", 2000))
                .addTlv(0xEA, new byte[]{0x41, 0x42})  // Use non-reserved TLV type
                .build();

        ProxyHeader parsed = ProxyProtocolV2Decoder.parse(header, 0, header.length, true);
        assertEquals(ProxyHeader.AddressFamily.AF_INET6, parsed.getFamily());
        assertEquals(ProxyHeader.TransportProtocol.DGRAM, parsed.getProtocol());
        // AWS ProProt adds extra TLVs, find our custom TLV
        assertTrue(parsed.getTlvs().size() >= 1);
        boolean foundCustomTlv = parsed.getTlvs().stream()
                .anyMatch(tlv -> tlv.getType() == 0xEA && java.util.Arrays.equals(tlv.getValue(), new byte[]{0x41, 0x42}));
        assertTrue(foundCustomTlv, "Should find custom TLV with type 0xEA");
    }

    @Test
    void parseLocal() throws Exception {
        var header = new AwsProxyEncoderHelper()
                .command(ProxyHeader.Command.LOCAL)
                .build();

        ProxyHeader parsed = ProxyProtocolV2Decoder.parse(header, 0, header.length);
        assertTrue(parsed.isLocal());
        assertNull(parsed.getSourceAddress());
    }

    @Test
    void parseIPv4Udp() throws Exception {
        var header = new AwsProxyEncoderHelper()
                .family(ProxyHeader.AddressFamily.AF_INET)
                .socket(ProxyHeader.TransportProtocol.DGRAM)
                .source(new InetSocketAddress("127.0.0.1", 1111))
                .destination(new InetSocketAddress("127.0.0.2", 2222))
                .build();


        ProxyHeader parsed = ProxyProtocolV2Decoder.parse(header, 0, header.length);
        assertEquals(ProxyHeader.TransportProtocol.DGRAM, parsed.getProtocol());
        assertEquals(1111, parsed.getSourceAddress().getPort());
        assertEquals(2222, parsed.getDestinationAddress().getPort());
    }


    @Test
    void proxyUnspecWithTlvOnly() throws Exception {
        var header = new AwsProxyEncoderHelper()
                .family(ProxyHeader.AddressFamily.AF_UNSPEC)
                .socket(ProxyHeader.TransportProtocol.UNSPEC)
                .addTlv(0xEE, new byte[]{0x10, 0x20, 0x30})
                .build();

        ProxyHeader parsed = ProxyProtocolV2Decoder.parse(header, 0, header.length, true);
        assertEquals(ProxyHeader.AddressFamily.AF_UNSPEC, parsed.getFamily());
        // AWS ProProt adds extra TLVs, find our custom TLV
        assertTrue(parsed.getTlvs().size() >= 1);
        boolean foundCustomTlv = parsed.getTlvs().stream()
                .anyMatch(tlv -> tlv.getType() == 0xEE && java.util.Arrays.equals(tlv.getValue(), new byte[]{0x10, 0x20, 0x30}));
        assertTrue(foundCustomTlv, "Should find custom TLV with type 0xEE");
    }
}


