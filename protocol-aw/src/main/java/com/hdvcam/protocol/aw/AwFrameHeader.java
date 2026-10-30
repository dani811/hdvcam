package com.hdvcam.protocol.aw;

/** Raw fields of the native eight-byte AW media fragment header. */
public final class AwFrameHeader {
    public static final int MAGIC = 0xbcde;
    private final int magic;
    private final int sequence;
    private final int payloadLength;
    private final int sessionIndex;
    private final int marker;

    private AwFrameHeader(int magic, int sequence, int payloadLength, int sessionIndex, int marker) {
        this.magic = magic;
        this.sequence = sequence;
        this.payloadLength = payloadLength;
        this.sessionIndex = sessionIndex;
        this.marker = marker;
    }

    public static AwFrameHeader parse(byte[] packet) {
        if (packet == null || packet.length < AwMediaFragment.HEADER_LENGTH) {
            throw new IllegalArgumentException("Header multimedia AW truncado.");
        }
        int size = u16(packet, 2);
        if (packet.length != AwMediaFragment.HEADER_LENGTH + size) {
            throw new IllegalArgumentException("Longitud multimedia AW incoherente.");
        }
        int magic = u16(packet, 0);
        if (magic != MAGIC) throw new IllegalArgumentException("Magic multimedia AW incorrecto.");
        return new AwFrameHeader(magic, u16(packet, 4), size, packet[6] & 0xff, packet[7] & 0xff);
    }

    public int magic() { return magic; }
    public int sequence() { return sequence; }
    public int payloadLength() { return payloadLength; }
    /** Byte 6 echoes the UdpClient session index on packets created by the native sender. */
    public int sessionIndex() { return sessionIndex; }
    public int marker() { return marker; }

    private static int u16(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }
}
