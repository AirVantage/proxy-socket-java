/*
 * MIT License
 * Copyright (c) 2025 Semtech
 *
 * Helper class to encode PROXY protocol v2 headers using AWS ProProt library.
 */
package net.airvantage.proxysocket.core.v2;

import com.amazonaws.proprot.Header;
import com.amazonaws.proprot.ProxyProtocol;
import com.amazonaws.proprot.ProxyProtocolSpec;
import com.amazonaws.proprot.TlvRaw;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * Helper class to encode PROXY protocol v2 headers using AWS ProProt library.
 * This is used in tests to validate our decoder against a known working encoder.
 */
public final class AwsProxyEncoderHelper {
    private ProxyProtocolSpec.Command command = ProxyProtocolSpec.Command.PROXY;
    private ProxyProtocolSpec.AddressFamily family = ProxyProtocolSpec.AddressFamily.AF_INET;
    private ProxyProtocolSpec.TransportProtocol protocol = ProxyProtocolSpec.TransportProtocol.STREAM;
    private InetSocketAddress source;
    private InetSocketAddress destination;
    private final Header header = new Header();

    public AwsProxyEncoderHelper command(ProxyHeader.Command cmd) {
        this.command = cmd == ProxyHeader.Command.LOCAL
                ? ProxyProtocolSpec.Command.LOCAL
                : ProxyProtocolSpec.Command.PROXY;
        return this;
    }

    public AwsProxyEncoderHelper family(ProxyHeader.AddressFamily fam) {
        this.family = switch (fam) {
            case UNSPEC -> ProxyProtocolSpec.AddressFamily.AF_UNSPEC;
            case INET4 -> ProxyProtocolSpec.AddressFamily.AF_INET;
            case INET6 -> ProxyProtocolSpec.AddressFamily.AF_INET6;
            case UNIX -> ProxyProtocolSpec.AddressFamily.AF_UNIX;
        };
        return this;
    }

    public AwsProxyEncoderHelper socket(ProxyHeader.TransportProtocol proto) {
        this.protocol = switch (proto) {
            case UNSPEC -> ProxyProtocolSpec.TransportProtocol.UNSPEC;
            case STREAM -> ProxyProtocolSpec.TransportProtocol.STREAM;
            case DGRAM -> ProxyProtocolSpec.TransportProtocol.DGRAM;
        };
        return this;
    }

    public AwsProxyEncoderHelper source(InetSocketAddress src) {
        this.source = src;
        return this;
    }

    public AwsProxyEncoderHelper destination(InetSocketAddress dst) {
        this.destination = dst;
        return this;
    }

    public AwsProxyEncoderHelper addTlv(int type, byte[] value) {
        TlvRaw tlv = new TlvRaw();
        tlv.setType(type);
        tlv.setValue(value);
        header.addTlv(tlv);
        return this;
    }

    public byte[] build() throws IOException {
        header.setCommand(command);
        header.setAddressFamily(family);
        header.setTransportProtocol(protocol);

        // AWS ProProt validates addresses even for LOCAL command, set dummy values
        if (command == ProxyProtocolSpec.Command.LOCAL && source == null) {
            header.setSrcAddress(new byte[]{0, 0, 0, 0});
            header.setDstAddress(new byte[]{0, 0, 0, 0});
            header.setSrcPort(0);
            header.setDstPort(0);
        } else {
            if (source != null) {
                header.setSrcAddress(source.getAddress().getAddress());
                header.setSrcPort(source.getPort());
            }

            if (destination != null) {
                header.setDstAddress(destination.getAddress().getAddress());
                header.setDstPort(destination.getPort());
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ProxyProtocol proxyProtocol = new ProxyProtocol();
        proxyProtocol.write(header, out);
        return out.toByteArray();
    }
}

