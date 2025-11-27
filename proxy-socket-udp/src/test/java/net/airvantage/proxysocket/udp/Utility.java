/*
 * MIT License
 * Copyright (c) 2025 Semtech
 */
package net.airvantage.proxysocket.udp;

public final class Utility {
    private Utility() {}

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