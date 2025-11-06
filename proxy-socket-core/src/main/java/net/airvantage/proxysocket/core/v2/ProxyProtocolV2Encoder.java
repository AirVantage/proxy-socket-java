/*
 * MIT License
 * Copyright (c) 2025 Semtech
 */
package net.airvantage.proxysocket.core.v2;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal builder for generating Proxy Protocol v2 headers for tests.
 */
public final class ProxyProtocolV2Encoder {
    private static final byte[] SIG = "\r\n\r\n\0\r\nQUIT\n".getBytes(StandardCharsets.ISO_8859_1);

    private ProxyHeader.Command command = ProxyHeader.Command.PROXY;
    private ProxyHeader.AddressFamily family = ProxyHeader.AddressFamily.INET4;
    private ProxyHeader.TransportProtocol socket = ProxyHeader.TransportProtocol.STREAM;
    private InetSocketAddress source;
    private InetSocketAddress destination;
    private final List<Tlv> tlvs = new ArrayList<>();

    public ProxyProtocolV2Encoder command(ProxyHeader.Command c) { this.command = c; return this; }
    public ProxyProtocolV2Encoder family(ProxyHeader.AddressFamily f) { this.family = f; return this; }
    public ProxyProtocolV2Encoder socket(ProxyHeader.TransportProtocol p) { this.socket = p; return this; }
    public ProxyProtocolV2Encoder source(InetSocketAddress s) { this.source = s; return this; }
    public ProxyProtocolV2Encoder destination(InetSocketAddress d) { this.destination = d; return this; }
    public ProxyProtocolV2Encoder addTlv(int type, byte[] value) { this.tlvs.add(new Tlv(type, value, 0, value.length)); return this; }

    public byte[] build() {
        byte verCmd = (byte) ((2 << 4) | (command == ProxyHeader.Command.PROXY ? 0x01 : 0x00));
        int fam = switch (family) { case INET4 -> 0x10; case INET6 -> 0x20; case UNIX -> 0x30; default -> 0x00; };
        int proto = switch (socket) { case STREAM -> 0x01; case DGRAM -> 0x02; default -> 0x00; };
        byte famProto = (byte) (fam | proto);

        byte[] addr = buildAddr();
        byte[] tlvBytes = buildTlvs();
        int addrLen = addr.length + tlvBytes.length;
        byte[] out = new byte[16 + addrLen];
        int p = 0;
        System.arraycopy(SIG, 0, out, p, SIG.length); p += SIG.length;
        out[p++] = verCmd;
        out[p++] = famProto;
        out[p++] = (byte) ((addrLen >>> 8) & 0xFF);
        out[p++] = (byte) (addrLen & 0xFF);
        System.arraycopy(addr, 0, out, p, addr.length); p += addr.length;
        System.arraycopy(tlvBytes, 0, out, p, tlvBytes.length);
        return out;
    }

    private byte[] buildAddr() {
        if (command == ProxyHeader.Command.LOCAL) return new byte[0];
        if (family == ProxyHeader.AddressFamily.INET4 && (socket == ProxyHeader.TransportProtocol.STREAM || socket == ProxyHeader.TransportProtocol.DGRAM)) {
            byte[] b = new byte[12];
            writeIPv4PortPair(b);
            return b;
        }
        if (family == ProxyHeader.AddressFamily.INET6 && (socket == ProxyHeader.TransportProtocol.STREAM || socket == ProxyHeader.TransportProtocol.DGRAM)) {
            byte[] b = new byte[36];
            writeIPv6PortPair(b);
            return b;
        }
        return new byte[0];
    }

    private void writeIPv4PortPair(byte[] b) {
        byte[] src = source == null ? new byte[4] : source.getAddress().getAddress();
        byte[] dst = destination == null ? new byte[4] : destination.getAddress().getAddress();
        System.arraycopy(src, 0, b, 0, 4);
        System.arraycopy(dst, 0, b, 4, 4);
        int sp = source == null ? 0 : source.getPort();
        int dp = destination == null ? 0 : destination.getPort();
        b[8] = (byte) ((sp >>> 8) & 0xFF); b[9] = (byte) (sp & 0xFF);
        b[10] = (byte) ((dp >>> 8) & 0xFF); b[11] = (byte) (dp & 0xFF);
    }

    private void writeIPv6PortPair(byte[] b) {
        byte[] src = source == null ? new byte[16] : toIPv6Bytes(source.getAddress());
        byte[] dst = destination == null ? new byte[16] : toIPv6Bytes(destination.getAddress());
        System.arraycopy(src, 0, b, 0, 16);
        System.arraycopy(dst, 0, b, 16, 16);
        int sp = source == null ? 0 : source.getPort();
        int dp = destination == null ? 0 : destination.getPort();
        b[32] = (byte) ((sp >>> 8) & 0xFF); b[33] = (byte) (sp & 0xFF);
        b[34] = (byte) ((dp >>> 8) & 0xFF); b[35] = (byte) (dp & 0xFF);
    }

    private static byte[] toIPv6Bytes(InetAddress addr) {
        byte[] a = addr.getAddress();
        if (a.length == 16) return a;
        // IPv4-mapped IPv6 ::ffff:a.b.c.d
        byte[] v6 = new byte[16];
        v6[10] = (byte) 0xFF; v6[11] = (byte) 0xFF;
        System.arraycopy(a, 0, v6, 12, 4);
        return v6;
    }

    private byte[] buildTlvs() {
        int total = 0;
        for (Tlv t : tlvs) total += 3 + (t.getValue() == null ? 0 : t.getValue().length);
        byte[] buf = new byte[total];
        int p = 0;
        for (Tlv t : tlvs) {
            byte[] v = t.getValue();
            int len = v == null ? 0 : v.length;
            buf[p++] = (byte) (t.getType() & 0xFF);
            buf[p++] = (byte) ((len >>> 8) & 0xFF);
            buf[p++] = (byte) (len & 0xFF);
            if (len > 0) {
                System.arraycopy(v, 0, buf, p, len);
                p += len;
            }
        }
        return buf;
    }
}
