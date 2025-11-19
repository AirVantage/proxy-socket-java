/*
 * MIT License
 * Copyright (c) 2025 Semtech
 */
package net.airvantage.proxysocket.core.v2;

import java.util.Arrays;

public final class Tlv {
    private final int type;
    private final byte[] value;

    public Tlv(int type, byte[] data) {
        this.type = type;
        this.value = data;
    }

    public static Tlv extractTlvFromPacket(int type, byte[] packet, int offset, int length) {
        byte[] data = Arrays.copyOfRange(packet, offset, offset + length);
        return new Tlv(type, data);
    }

    public int getType() { return type; }
    public byte[] getValue() { return value.clone(); }

    @Override
    public String toString() {
        int displayLimit = Math.min(value.length, 16);
        byte[] head = Arrays.copyOf(value, displayLimit);
        return "Tlv{" + "type=" + type + ", len=" + value.length + ", head=" + Arrays.toString(head) + (value.length > displayLimit ? ", ..." : "") + '}';
    }
}
