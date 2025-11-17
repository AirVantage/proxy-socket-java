/*
 * MIT License
 * Copyright (c) 2025 Semtech
 */
package net.airvantage.proxysocket.core.v2;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;

public final class ProxyHeader {
    public enum Command { LOCAL, PROXY }
    public enum AddressFamily { AF_UNSPEC, AF_INET, AF_INET6, AF_UNIX }
    public enum TransportProtocol { UNSPEC, STREAM, DGRAM }

    private final Command command;
    private final AddressFamily family;
    private final TransportProtocol socket;
    private final InetSocketAddress sourceAddress;
    private final InetSocketAddress destinationAddress;
    private final List<Tlv> tlvs;
    private final int headerLength;

    public ProxyHeader(Command command,
                       AddressFamily family,
                       TransportProtocol socket,
                       InetSocketAddress sourceAddress,
                       InetSocketAddress destinationAddress,
                       List<Tlv> tlvs,
                       int headerLength) {
        this.command = command;
        this.family = family;
        this.socket = socket;
        this.sourceAddress = sourceAddress;
        this.destinationAddress = destinationAddress;
        this.tlvs = tlvs == null ? List.of() : List.copyOf(tlvs);
        this.headerLength = headerLength;
    }

    public Command getCommand() { return command; }
    public AddressFamily getFamily() { return family; }
    public TransportProtocol getProtocol() { return socket; }
    public InetSocketAddress getSourceAddress() { return sourceAddress; }
    public InetSocketAddress getDestinationAddress() { return destinationAddress; }
    public List<Tlv> getTlvs() { return Collections.unmodifiableList(tlvs); }
    public int getHeaderLength() { return headerLength; }

    public boolean isLocal() { return command == Command.LOCAL; }
    public boolean isProxy() { return command == Command.PROXY; }
}
