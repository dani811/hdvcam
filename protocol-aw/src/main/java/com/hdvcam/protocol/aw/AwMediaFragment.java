package com.hdvcam.protocol.aw;

import java.util.Arrays;

/** One parsed AW UDP media fragment based on the observed native socket header. */
public final class AwMediaFragment {
    public static final int HEADER_LENGTH = 8;
    /** Native UdpClient::Recv_Local receives into a 0x800-byte buffer. */
    public static final int MAX_DATAGRAM_BYTES = 2048;
    public static final int MAGIC = AwFrameHeader.MAGIC;
    public static final int VIDEO_START = 2;
    public static final int VIDEO_CONTINUATION = 1;
    public static final int AUDIO_START = 4;
    public static final int AUDIO_CONTINUATION = 3;
    public static final int HEARTBEAT = 5;
    public static final int VIDEO_FRAGMENT_LIMIT = 1000;
    public static final int AUDIO_FRAGMENT_LIMIT = 256;

    private final int sequence;
    private final int fragmentLength;
    private final int magic;
    private final int sessionIndex;
    private final int marker;
    private final byte[] payload;

    private AwMediaFragment(int magic, int sequence, int fragmentLength, int sessionIndex,
                            int marker, byte[] payload) {
        this.magic = magic;
        this.sequence = sequence;
        this.fragmentLength = fragmentLength;
        this.sessionIndex = sessionIndex;
        this.marker = marker;
        this.payload = payload;
    }

    public static AwMediaFragment parse(byte[] datagram) {
        if (datagram == null || datagram.length < HEADER_LENGTH) throw new IllegalArgumentException("Fragmento AW truncado.");
        if (datagram.length > MAX_DATAGRAM_BYTES) throw new IllegalArgumentException("Datagrama multimedia AW demasiado largo.");
        AwFrameHeader header = AwFrameHeader.parse(datagram);
        int sequence = header.sequence();
        int size = header.payloadLength();
        byte[] payload = Arrays.copyOfRange(datagram, HEADER_LENGTH, datagram.length);
        int marker = header.marker();
        int limit = marker == VIDEO_START || marker == VIDEO_CONTINUATION ? VIDEO_FRAGMENT_LIMIT
                : marker == AUDIO_START || marker == AUDIO_CONTINUATION ? AUDIO_FRAGMENT_LIMIT : Integer.MAX_VALUE;
        if (size > limit) throw new IllegalArgumentException("Tamaño de fragmento multimedia AW inválido.");
        return new AwMediaFragment(header.magic(), sequence, size, header.sessionIndex(), marker, payload);
    }

    public int magic() { return magic; }
    public int sequence() { return sequence; }
    public int fragmentLength() { return fragmentLength; }
    /** Native sender copies UdpClient's session index (SID) into byte 6. */
    public int sessionIndex() { return sessionIndex; }
    public int marker() { return marker; }
    public boolean isVideo() { return marker == VIDEO_START || marker == VIDEO_CONTINUATION; }
    public boolean isAudio() { return marker == AUDIO_START || marker == AUDIO_CONTINUATION; }
    public boolean isHeartbeat() { return marker == HEARTBEAT; }
    public boolean startsFrame() { return marker == VIDEO_START || marker == AUDIO_START; }
    public boolean isContinuation() { return marker == VIDEO_CONTINUATION || marker == AUDIO_CONTINUATION; }
    public byte[] payload() { return payload.clone(); }

}
