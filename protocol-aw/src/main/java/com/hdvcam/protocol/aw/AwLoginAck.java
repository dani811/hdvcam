package com.hdvcam.protocol.aw;

/** Parsed AW TCP login acknowledgement and negotiated UDP peer port. */
public final class AwLoginAck {
    public static final int ACK_TYPE = 0x111;
    public static final int UDP_PORT_BASE = 6669;

    private final int udpSelector;

    private AwLoginAck(int udpSelector) { this.udpSelector = udpSelector; }

    /** Parse one complete 8-byte-header plus 129-byte-payload AW TCP frame. */
    public static AwLoginAck parse(byte[] frame) {
        if (frame == null || frame.length != AwLoginRequest.FRAME_LENGTH) {
            throw new IllegalArgumentException("ACK AW ausente o de longitud inesperada.");
        }
        if (u16(frame, 0) != AwDiscoveryRequest.MAGIC) throw new IllegalArgumentException("Magic AW no válido.");
        if (u16(frame, 2) != AwLoginRequest.BODY_LENGTH) throw new IllegalArgumentException("Longitud de ACK AW no válida.");
        if (u32(frame, 4) != ACK_TYPE) throw new IllegalArgumentException("No es un ACK AW 0x111.");
        int selector = frame[8 + AwLoginRequest.SESSION_INDEX_OFFSET] & 0xff;
        return new AwLoginAck(selector);
    }

    public int udpSelector() { return udpSelector; }

    /** The native CheckLogin path builds the UDP peer port as 6669 + body[128]. */
    public int udpPeerPort() { return UDP_PORT_BASE + udpSelector; }

    private static int u16(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    private static long u32(byte[] bytes, int offset) {
        return ((long) (bytes[offset] & 0xff) << 24)
                | ((long) (bytes[offset + 1] & 0xff) << 16)
                | ((long) (bytes[offset + 2] & 0xff) << 8)
                | (long) (bytes[offset + 3] & 0xff);
    }
}
