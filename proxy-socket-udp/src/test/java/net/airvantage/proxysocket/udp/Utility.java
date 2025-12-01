/**
 * BSD-3-Clause License.
 * Copyright (c) 2025 Semtech
 */
package net.airvantage.proxysocket.udp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;

public final class Utility {
    private Utility() {}

    /**
     * Creates a packet by combining proxy header and payload.
     */
    public static byte[] createPacket(byte[] proxyHeader, byte[] payload) {
        byte[] packet = new byte[proxyHeader.length + payload.length];
        System.arraycopy(proxyHeader, 0, packet, 0, proxyHeader.length);
        System.arraycopy(payload, 0, packet, proxyHeader.length, payload.length);
        return packet;
    }

    /**
     * Sends a packet to the specified destination using an ephemeral DatagramSocket.
     */
    public static void sendPacket(byte[] packet, InetSocketAddress destination) throws IOException {
        try (DatagramSocket sender = new DatagramSocket()) {
            sender.send(new DatagramPacket(packet, packet.length, destination));
        }
    }

    /**
     * Sends a packet to the specified destination using a DatagramSocket bound to the specified source address.
     */
    public static void sendPacket(byte[] packet, InetSocketAddress source, InetSocketAddress destination) throws IOException {
        try (DatagramSocket sender = new DatagramSocket(source)) {
            sender.send(new DatagramPacket(packet, packet.length, destination));
        }
    }

    /**
     * Returns a hexdump of the specified data for debugging purposes.
     * @param data The data to dump.
     * @param offset The offset into the data to start dumping.
     * @param length The length of the data to dump.
     * @return A string containing the hexdump.
     */
    public static String hexdump(byte[] data, int offset, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = offset; i < offset + length; i += 16) {
            // Offset
            sb.append(String.format("%08x  ", i));

            // Hex bytes (first 8)
            for (int j = 0; j < 8 && i + j < offset + length; j++) {
                sb.append(String.format("%02x ", data[i + j]));
            }
            sb.append(" ");

            // Hex bytes (second 8)
            for (int j = 8; j < 16 && i + j < offset + length; j++) {
                sb.append(String.format("%02x ", data[i + j]));
            }

            // Padding if last line is incomplete
            int remaining = 16 - Math.min(16, offset + length - i);
            sb.append("   ".repeat(Math.max(0, remaining)));

            // ASCII representation
            sb.append(" |");
            for (int j = 0; j < 16 && i + j < offset + length; j++) {
                byte b = data[i + j];
                sb.append((b >= 32 && b < 127) ? (char) b : '.');
            }
            sb.append("|\n");
        }
        return sb.toString();
    }
}