package com.hdvcam.protocol.aw;

/** TCP/UDP envelope used by the AW native CNetManage message path. */
public final class AwIoControlFrame {
    public static final int HEADER_LENGTH = 8;
    public static final int TYPE_IO_REQUEST = 0x112;
    public static final int TYPE_IO_RESPONSE = 0x113;
    public static final int TYPE_START_VIDEO = 0x1ff;
    public static final int TYPE_STOP_VIDEO = 0x2ff;
    public static final int TYPE_LOGIN = 0x110;
    public static final int TYPE_LOGIN_ACK = 0x111;
    public static final int TYPE_CAMERA_PING = 0x112;
    public static final int TYPE_CAMERA_PING_ACK = 0x113;
    public static final int MAX_BODY_LENGTH = 0xffff;

    private final int type;
    private final byte[] payload;

    private AwIoControlFrame(int type, byte[] payload) {
        this.type = type;
        this.payload = payload;
    }

    public static byte[] encode(int type, byte[] payload) {
        if (type < 0 || type > 0xffff_ffffL) throw new IllegalArgumentException("Tipo AW fuera de rango.");
        if (payload == null) throw new IllegalArgumentException("Payload AW nulo.");
        if (payload.length > MAX_BODY_LENGTH) throw new IllegalArgumentException("Payload AW demasiado grande.");
        byte[] frame = new byte[HEADER_LENGTH + payload.length];
        putU16(frame, 0, AwDiscoveryRequest.MAGIC);
        putU16(frame, 2, payload.length);
        putU32(frame, 4, type);
        System.arraycopy(payload, 0, frame, HEADER_LENGTH, payload.length);
        return frame;
    }

    /** Parses exactly one datagram/frame; callers must not pass trailing bytes. */
    public static AwIoControlFrame parse(byte[] frame) {
        if (frame == null || frame.length < HEADER_LENGTH) throw new IllegalArgumentException("Frame AW truncado.");
        if (u16(frame, 0) != AwDiscoveryRequest.MAGIC) throw new IllegalArgumentException("Magic AW incorrecto.");
        int length = u16(frame, 2);
        if (frame.length != HEADER_LENGTH + length) throw new IllegalArgumentException("Longitud AW incoherente.");
        return new AwIoControlFrame(u32(frame, 4), java.util.Arrays.copyOfRange(frame, HEADER_LENGTH, frame.length));
    }

    public int type() { return type; }
    public byte[] payload() { return payload.clone(); }

    private static int u16(byte[] b, int o) { return ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff); }
    private static int u32(byte[] b, int o) {
        return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16) | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }
    private static void putU16(byte[] b, int o, int v) { b[o] = (byte) (v >>> 8); b[o + 1] = (byte) v; }
    private static void putU32(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 24); b[o + 1] = (byte) (v >>> 16); b[o + 2] = (byte) (v >>> 8); b[o + 3] = (byte) v;
    }
}
