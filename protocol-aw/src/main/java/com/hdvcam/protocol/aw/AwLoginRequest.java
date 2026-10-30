package com.hdvcam.protocol.aw;

import java.nio.charset.StandardCharsets;

/** Serializer for the statically observed first AW TCP login frame. */
public final class AwLoginRequest {
    public static final int TCP_PORT = 6666;
    public static final int LOGIN_TYPE = 0x110;
    public static final int BODY_LENGTH = 129;
    public static final int FRAME_LENGTH = 8 + BODY_LENGTH;
    public static final int USERNAME_OFFSET = 0;
    public static final int PASSWORD_OFFSET = 64;
    public static final int SESSION_INDEX_OFFSET = 128;

    // These are literals embedded in the observed APK native library. Never log them.
    private static final byte[] USERNAME = "admin\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PASSWORD = "12345\0".getBytes(StandardCharsets.US_ASCII);

    private AwLoginRequest() { }

    /** sendLogin writes the low byte of the addConInfo session index into body[128]. */
    public static byte[] create(int sessionIndex) {
        if (sessionIndex < 0 || sessionIndex > 255) throw new IllegalArgumentException("Índice de sesión AW fuera de rango.");
        byte[] frame = new byte[FRAME_LENGTH];
        putU16(frame, 0, AwDiscoveryRequest.MAGIC);
        putU16(frame, 2, BODY_LENGTH);
        putU32(frame, 4, LOGIN_TYPE);
        System.arraycopy(USERNAME, 0, frame, 8 + USERNAME_OFFSET, USERNAME.length);
        System.arraycopy(PASSWORD, 0, frame, 8 + PASSWORD_OFFSET, PASSWORD.length);
        frame[8 + SESSION_INDEX_OFFSET] = (byte) sessionIndex;
        return frame;
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
