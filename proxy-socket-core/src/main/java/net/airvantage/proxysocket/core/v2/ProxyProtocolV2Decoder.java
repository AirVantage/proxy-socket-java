/**
 * BSD-3-Clause License.
 * Copyright (c) 2025 Semtech
 */
package net.airvantage.proxysocket.core.v2;

import net.airvantage.proxysocket.core.ProxyProtocolParseException;
import net.airvantage.proxysocket.core.v2.ProxyHeader.TransportProtocol;
import net.airvantage.proxysocket.core.v2.ProxyHeader.AddressFamily;
import net.airvantage.proxysocket.core.v2.ProxyHeader.Command;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Dependency-free Proxy Protocol v2 utilities (validate/parse/build).
 */
public final class ProxyProtocolV2Decoder {
    private ProxyProtocolV2Decoder() {}

    private static final byte[] PROTOCOL_SIGNATURE = new byte[] {0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A};
    private static final int PROTOCOL_SIGNATURE_FIXED_LENGTH = PROTOCOL_SIGNATURE.length + 4;
    private static final int IPV4_ADDR_LEN = 4;
    private static final int IPV6_ADDR_LEN = 16;
    private static final int UNIX_ADDR_LEN = 216;
    private static final int PORT_LEN = 2;
    private static final int TLV_HEADER_LEN = 3;

    private record AddressPair(InetSocketAddress src, InetSocketAddress dst, int bytesConsumed) {}

    public static ProxyHeader parse(byte[] data, int offset, int length) throws ProxyProtocolParseException, IllegalArgumentException {
        return parse(data, offset, length, false);
    }

    public static ProxyHeader parse(byte[] data, int offset, int length, boolean parseTlvs) throws ProxyProtocolParseException, IllegalArgumentException {
        if (data == null || offset < 0 || length < 0) {
            throw new IllegalArgumentException("Invalid arguments");
        }

        if ((length+offset) > data.length) {
            throw new IllegalArgumentException("Invalid offset/length combination with data length");
        }

        if (PROTOCOL_SIGNATURE_FIXED_LENGTH > length) {
            throw new ProxyProtocolParseException("Insufficient data for header");
        }

        for (int i = 0; i < PROTOCOL_SIGNATURE.length; i++) {
            if (data[offset + i] != PROTOCOL_SIGNATURE[i]) {
                throw new ProxyProtocolParseException("Invalid signature");
            }
        }

        int pos = offset + PROTOCOL_SIGNATURE.length;

        // Byte 13: version/command
        int verCmd = data[pos++] & 0xFF;
        int version = (verCmd >> 4) & 0x0F;
        int cmd = verCmd & 0x0F;

        if (version != 2) {
            throw new ProxyProtocolParseException("Invalid version");
        }

        Command command = switch (cmd) {
            case 0x00 -> Command.LOCAL;
            case 0x01 -> Command.PROXY;
            default -> throw new ProxyProtocolParseException("Invalid command");
        };

        // Byte 14: address family and protocol
        int famProto = data[pos++] & 0xFF;
        int fam = famProto & 0xF0;
        int proto = famProto & 0x0F;

        AddressFamily af = parseAddressFamily(fam);
        TransportProtocol tp = parseTransportProtocol(proto);

        // Byte 15, 16: Length of address part of the header, including TLVs
        int variableLength = ((data[pos++] & 0xFF) << 8) | (data[pos++] & 0xFF);

        // Check if we have enough data for the header
        int headerLen = PROTOCOL_SIGNATURE_FIXED_LENGTH + variableLength;
        if (headerLen > length) {
            throw new ProxyProtocolParseException("Insufficient data for header");
        }

        AddressPair addresses = null;
        if (af != AddressFamily.AF_UNSPEC) {
            addresses = switch (af) {
                case AF_INET -> parseIPv4Addresses(data, pos, variableLength);
                case AF_INET6 -> parseIPv6Addresses(data, pos, variableLength);
                case AF_UNIX -> parseUnixAddresses(data, pos, variableLength);
                default -> throw new ProxyProtocolParseException("Invalid address family");
            };
            pos += addresses.bytesConsumed;
        }

        List<Tlv> tlvs = null;
        if (parseTlvs) {
            int tlvLen = Math.max(0, variableLength - (addresses != null ? addresses.bytesConsumed : 0));
            tlvs = parseTlvs(data, pos, tlvLen);
        }

        InetSocketAddress src = addresses != null ? addresses.src : null;
        InetSocketAddress dst = addresses != null ? addresses.dst : null;

        return new ProxyHeader(command, af, tp, src, dst, tlvs, headerLen);
    }

    private static AddressFamily parseAddressFamily(int fam)
        throws ProxyProtocolParseException {
        return switch (fam) {
            case 0x00 -> AddressFamily.AF_UNSPEC;
            case 0x10 -> AddressFamily.AF_INET;
            case 0x20 -> AddressFamily.AF_INET6;
            case 0x30 -> AddressFamily.AF_UNIX;
            default -> throw new ProxyProtocolParseException("Invalid address family");
        };
    }

    private static TransportProtocol parseTransportProtocol(int proto)
        throws ProxyProtocolParseException {
        return switch (proto) {
            case 0x00 -> TransportProtocol.UNSPEC;
            case 0x01 -> TransportProtocol.STREAM;
            case 0x02 -> TransportProtocol.DGRAM;
            default -> throw new ProxyProtocolParseException("Invalid transport protocol");
        };
    }

    private static final int IPV4_ADDR_PAIR_LEN = 2*(IPV4_ADDR_LEN + PORT_LEN);
    private static AddressPair parseIPv4Addresses(byte[] data, int pos, int variableLength)
            throws ProxyProtocolParseException {
        if (variableLength < IPV4_ADDR_PAIR_LEN) {
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
        int dp = ((data[pos++] & 0xFF) << 8) | (data[pos  ] & 0xFF);

        return new AddressPair(new InetSocketAddress(s, sp), new InetSocketAddress(d, dp), IPV4_ADDR_PAIR_LEN);
    }

    private static final int IPV6_ADDR_PAIR_LEN = 2*(IPV6_ADDR_LEN + PORT_LEN);
    private static AddressPair parseIPv6Addresses(byte[] data, int pos, int variableLength)
            throws ProxyProtocolParseException {
        if (variableLength < IPV6_ADDR_PAIR_LEN) {
            throw new ProxyProtocolParseException("Truncated IPv6 address block in header");
        }

        InetAddress s;
        InetAddress d;
        byte[] sb = new byte[IPV6_ADDR_LEN];
        byte[] db = new byte[IPV6_ADDR_LEN];
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
        int dp = ((data[pos++] & 0xFF) << 8) | (data[pos  ] & 0xFF);

        return new AddressPair(new InetSocketAddress(s, sp), new InetSocketAddress(d, dp), IPV6_ADDR_PAIR_LEN);
    }

    private static final int UNIX_ADDR_PAIR_LEN = 2*UNIX_ADDR_LEN;
    private static AddressPair parseUnixAddresses(byte[] data, int pos, int variableLength)
            throws ProxyProtocolParseException {
        if (variableLength < UNIX_ADDR_PAIR_LEN) {
            throw new ProxyProtocolParseException("Truncated UNIX address block in header");
        }

        // A receiver is not required to implement other ones, provided that it
        // automatically falls back to the UNSPEC mode for the valid combinations above
        // that it does not support.
        return new AddressPair(null, null, UNIX_ADDR_PAIR_LEN);
    }

    private static List<Tlv> parseTlvs(byte[] data, int tlvPos, int tlvLen)
        throws ProxyProtocolParseException {
        List<Tlv> tlvs = new ArrayList<>();

        int tlvEnd = tlvPos + tlvLen;
        while (tlvPos + TLV_HEADER_LEN <= tlvEnd) {
            int type = data[tlvPos++] & 0xFF;
            int len = ((data[tlvPos++] & 0xFF) << 8) | (data[tlvPos++] & 0xFF);

            if (tlvPos + len > tlvEnd) {
                throw new ProxyProtocolParseException("Truncated TLV in header");
            }
            tlvs.add(Tlv.extractTlvFromPacket(type, data, tlvPos, len));
            tlvPos += len;
        }
        return tlvs;
    }
}
