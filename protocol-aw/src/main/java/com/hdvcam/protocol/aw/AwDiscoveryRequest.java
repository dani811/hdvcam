package com.hdvcam.protocol.aw;

import java.util.Locale;

/**
 * Clean-room encoder of the statically observed 80-byte AW LAN discovery request.
 * It only creates bytes; callers must explicitly choose whether/how to transmit them.
 */
public final class AwDiscoveryRequest {
    public static final int UDP_DESTINATION_PORT = 21600;
    public static final int UDP_LISTEN_PORT = 21601;
    public static final int PACKET_LENGTH = 80;
    public static final int MAGIC = 0xABCD;
    public static final int COMMAND = 0x0048;
    public static final int MESSAGE_TYPE = 0x00000114;

    private AwDiscoveryRequest() { }

    public static byte[] create() {
        byte[] packet = new byte[PACKET_LENGTH];
        putU16(packet, 0, MAGIC);
        putU16(packet, 2, COMMAND);
        putU32(packet, 4, MESSAGE_TYPE);
        return packet;
    }

    public static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) out.append(' ');
            out.append(String.format(Locale.ROOT, "%02X", bytes[i] & 0xff));
        }
        return out.toString();
    }

    private static void putU16(byte[] out, int offset, int value) {
        out[offset] = (byte) (value >>> 8);
        out[offset + 1] = (byte) value;
    }

    private static void putU32(byte[] out, int offset, int value) {
        out[offset] = (byte) (value >>> 24);
        out[offset + 1] = (byte) (value >>> 16);
        out[offset + 2] = (byte) (value >>> 8);
        out[offset + 3] = (byte) value;
    }
}
