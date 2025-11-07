/*
 * MIT License
 * Copyright (c) 2025 Semtech
 */
package net.airvantage.proxysocket.core.v2;

import net.airvantage.proxysocket.core.ProxyProtocolParseException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Dependency-free Proxy Protocol v2 utilities (validate/parse/build).
 */
public final class ProxyProtocolV2Decoder {
    private ProxyProtocolV2Decoder() {}

    private static final byte[] SIG = "\r\n\r\n\0\r\nQUIT\n".getBytes(StandardCharsets.ISO_8859_1);
    private static final int IPV4_ADDR_LEN = 4;
    private static final int IPV6_ADDR_LEN = 16;
    private static final int UNIX_ADDR_LEN = 216;
    private static final int PORT_LEN = 2;
    private static final int TLV_HEADER_LEN = 3;

    public static ProxyHeader parse(byte[] data, int offset, int length) throws ProxyProtocolParseException {
        if (data == null || offset < 0 || length < 0) throw new ProxyProtocolParseException("Null data");
        if ((length+offset) > data.length) throw new ProxyProtocolParseException("Invalid offset/length");

        int end = offset + length;
        if (SIG.length + 4 > length) throw new ProxyProtocolParseException("Insufficient data for header");

        for (int i = 0; i < SIG.length; i++) {
            if (data[offset + i] != SIG[i]) {
                throw new ProxyProtocolParseException("Invalid signature");
            }
        }

        int pos = offset + SIG.length;

        int verCmd = data[pos++] & 0xFF; // version/command
        int version = (verCmd >> 4) & 0x0F;
        if (version != 2) throw new ProxyProtocolParseException("Invalid version");
        int cmd = verCmd & 0x0F;
        ProxyHeader.Command command = cmd == 0x00 ? ProxyHeader.Command.LOCAL : ProxyHeader.Command.PROXY;

        int famProto = data[pos++] & 0xFF;
        int fam = famProto & 0xF0;
        int proto = famProto & 0x0F;

        ProxyHeader.AddressFamily af = parseAddressFamily(fam);
        ProxyHeader.TransportProtocol tp = parseTransportProtocol(proto);

        int variableLength = ((data[pos++] & 0xFF) << 8) | (data[pos++] & 0xFF);

        int headerLen = SIG.length + 4 + variableLength;
        if (headerLen > length) throw new ProxyProtocolParseException("Insufficient data for header");

        int addrStart = pos;
        AddressPair addresses = null;

        if (command == ProxyHeader.Command.PROXY) {
            addresses = parseAddresses(data, pos, af, tp, variableLength);
            if (addresses != null) {
                pos = addresses.newPos;
            }
        }

        int consumed = pos - addrStart;
        int tlvLen = Math.max(0, variableLength - consumed);
        List<Tlv> tlvs = parseTlvs(data, pos, tlvLen);

        InetSocketAddress src = addresses != null ? addresses.src : null;
        InetSocketAddress dst = addresses != null ? addresses.dst : null;

        return new ProxyHeader(command, af, tp, src, dst, tlvs, headerLen);
    }

    private static ProxyHeader.AddressFamily parseAddressFamily(int fam) {
        return switch (fam) {
            case 0x10 -> ProxyHeader.AddressFamily.INET4;
            case 0x20 -> ProxyHeader.AddressFamily.INET6;
            case 0x30 -> ProxyHeader.AddressFamily.UNIX;
            default -> ProxyHeader.AddressFamily.UNSPEC;
        };
    }

    private static ProxyHeader.TransportProtocol parseTransportProtocol(int proto) {
        return switch (proto) {
            case 0x01 -> ProxyHeader.TransportProtocol.STREAM;
            case 0x02 -> ProxyHeader.TransportProtocol.DGRAM;
            default -> ProxyHeader.TransportProtocol.UNSPEC;
        };
    }

    private static class AddressPair {
        final InetSocketAddress src;
        final InetSocketAddress dst;
        final int newPos;

        AddressPair(InetSocketAddress src, InetSocketAddress dst, int newPos) {
            this.src = src;
            this.dst = dst;
            this.newPos = newPos;
        }
    }

    private static AddressPair parseAddresses(byte[] data, int pos, ProxyHeader.AddressFamily af, ProxyHeader.TransportProtocol tp, int variableLength)
            throws ProxyProtocolParseException {
        if (af == ProxyHeader.AddressFamily.INET4 && tp != ProxyHeader.TransportProtocol.UNSPEC) {
            return parseIPv4Addresses(data, pos, variableLength);
        } else if (af == ProxyHeader.AddressFamily.INET6 && tp != ProxyHeader.TransportProtocol.UNSPEC) {
            return parseIPv6Addresses(data, pos, variableLength);
        } else if (af == ProxyHeader.AddressFamily.UNIX) {
            return parseUnixAddresses(data, pos, variableLength);
        }
        return null;
    }

    private static AddressPair parseIPv4Addresses(byte[] data, int pos, int variableLength)
            throws ProxyProtocolParseException {
        if (variableLength < 2*(IPV4_ADDR_LEN + PORT_LEN)) {
            throw new ProxyProtocolParseException("Truncated IPv4 address block in header");
        }

        InetAddress s;
        InetAddress d;
        try {
            s = InetAddress.getByAddress(new byte[]{data[pos++], data[pos++], data[pos++], data[pos++]});
            d = InetAddress.getByAddress(new byte[]{data[pos++], data[pos++], data[pos++], data[pos++]});
        } catch (UnknownHostException e) {
            throw new ProxyProtocolParseException("Invalid IPv4 address in header", e);
        }

        int sp = ((data[pos++] & 0xFF) << 8) | (data[pos++] & 0xFF);
        int dp = ((data[pos++] & 0xFF) << 8) | (data[pos++] & 0xFF);

        return new AddressPair(new InetSocketAddress(s, sp), new InetSocketAddress(d, dp), pos);
    }

    private static AddressPair parseIPv6Addresses(byte[] data, int pos, int variableLength)
            throws ProxyProtocolParseException {
        if (variableLength < 2*(IPV6_ADDR_LEN + PORT_LEN)) {
            throw new ProxyProtocolParseException("Truncated IPv6 address block in header");
        }

        InetAddress s;
        InetAddress d;
        byte[] sb = new byte[16];
        byte[] db = new byte[16];
        System.arraycopy(data, pos, sb, 0, IPV6_ADDR_LEN);
        System.arraycopy(data, pos+IPV6_ADDR_LEN, db, 0, IPV6_ADDR_LEN);
        try {
            s = InetAddress.getByAddress(sb);
            d = InetAddress.getByAddress(db);
        } catch (UnknownHostException e) {
            throw new ProxyProtocolParseException("Invalid IPv6 address in header", e);
        }

        pos += 2*IPV6_ADDR_LEN;
        int sp = ((data[pos++] & 0xFF) << 8) | (data[pos++] & 0xFF);
        int dp = ((data[pos++] & 0xFF) << 8) | (data[pos++] & 0xFF);

        return new AddressPair(new InetSocketAddress(s, sp), new InetSocketAddress(d, dp), pos);
    }

    private static AddressPair parseUnixAddresses(byte[] data, int pos, int variableLength)
            throws ProxyProtocolParseException {
        if (variableLength < 2*UNIX_ADDR_LEN) {
            throw new ProxyProtocolParseException("Truncated UNIX address block in header");
        }
        pos += 2*UNIX_ADDR_LEN;
        throw new ProxyProtocolParseException("UNIX Address Processing not implemented");
    }

    private static List<Tlv> parseTlvs(byte[] data, int pos, int tlvLen) {
        List<Tlv> tlvs = new ArrayList<>();
        int tlvPos = pos;
        int tlvEnd = tlvPos + tlvLen;
        while (tlvPos + TLV_HEADER_LEN <= tlvEnd) {
            int type = data[tlvPos++] & 0xFF;
            int len = ((data[tlvPos++] & 0xFF) << 8) | (data[tlvPos++] & 0xFF);

            if (tlvPos + len > tlvEnd) break;
            tlvs.add(new Tlv(type, data, tlvPos, len));
            tlvPos += len;
        }
        return tlvs;
    }
}
