package com.hdvcam.protocol.aw;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AwMediaFragmentTest {
    @Test public void parsesAndAssemblesFrameFromNativeFragments() {
        AwFrameAssembler assembler = new AwFrameAssembler(true);
        byte[] full = new byte[AwMediaFragment.VIDEO_FRAGMENT_LIMIT];
        assertNull(assembler.accept(AwMediaFragment.parse(fragment(10, 2, AwMediaFragment.VIDEO_START, full))));
        byte[] tail = new byte[]{3,4};
        byte[] frame = assembler.accept(AwMediaFragment.parse(fragment(11, 2, AwMediaFragment.VIDEO_CONTINUATION, tail)));
        assertEquals(AwMediaFragment.VIDEO_FRAGMENT_LIMIT + 2, frame.length);
        assertArrayEquals(tail, java.util.Arrays.copyOfRange(frame, frame.length - 2, frame.length));
    }

    @Test public void waitsForStartAfterMissingSequenceAndRejectsOversizedPayloads() {
        AwFrameAssembler assembler = new AwFrameAssembler(true);
        byte[] full = new byte[AwMediaFragment.VIDEO_FRAGMENT_LIMIT];
        assembler.accept(AwMediaFragment.parse(fragment(1, 0, AwMediaFragment.VIDEO_START, full)));
        // A gap invalidates the partial frame in the clean-room receiver.
        assertNull(assembler.accept(AwMediaFragment.parse(
                fragment(3, 0, AwMediaFragment.VIDEO_CONTINUATION, new byte[]{3}))));
        assertArrayEquals(new byte[]{4}, assembler.accept(AwMediaFragment.parse(
                fragment(4, 0, AwMediaFragment.VIDEO_START, new byte[]{4}))));
        assertThrows(IllegalArgumentException.class,
                () -> AwMediaFragment.parse(fragment(1, 0, 2, new byte[1001])));
    }

    @Test public void discardsPartialUnitAfterOutOfOrderPacketAndAcceptsNextStart() {
        AwFrameAssembler assembler = new AwFrameAssembler(true);
        byte[] full = new byte[AwMediaFragment.VIDEO_FRAGMENT_LIMIT];
        assertNull(assembler.accept(AwMediaFragment.parse(fragment(100, 0, AwMediaFragment.VIDEO_START, full))));
        assertArrayEquals(new byte[]{7}, assembler.accept(AwMediaFragment.parse(
                fragment(102, 0, AwMediaFragment.VIDEO_START, new byte[]{7}))));
        assertArrayEquals(new byte[]{8}, assembler.accept(AwMediaFragment.parse(
                fragment(103, 0, AwMediaFragment.VIDEO_CONTINUATION, new byte[]{8}))));
    }

    @Test public void enforcesNativeUdpReceiveDatagramLimit() {
        byte[] max = new byte[AwMediaFragment.MAX_DATAGRAM_BYTES];
        max[0] = (byte) (AwFrameHeader.MAGIC >>> 8); max[1] = (byte) AwFrameHeader.MAGIC;
        max[2] = (byte) ((max.length - AwMediaFragment.HEADER_LENGTH) >>> 8);
        max[3] = (byte) (max.length - AwMediaFragment.HEADER_LENGTH);
        max[7] = (byte) 0x7f;
        assertEquals(AwMediaFragment.MAX_DATAGRAM_BYTES, AwMediaFragment.parse(max).payload().length + AwMediaFragment.HEADER_LENGTH);
        byte[] oversized = java.util.Arrays.copyOf(max, max.length + 1);
        assertThrows(IllegalArgumentException.class, () -> AwMediaFragment.parse(oversized));
    }

    @Test public void parsesAwMagicSequenceAndAudioMarker() {
        AwMediaFragment audio = AwMediaFragment.parse(fragment(65535, 7, 4, new byte[]{8}));
        assertEquals(AwMediaFragment.MAGIC, audio.magic());
        assertEquals(65535, audio.sequence());
        assertEquals(7, audio.sessionIndex());
        assertEquals(4, audio.marker());
        assertEquals(1, audio.payload().length);
        byte[] badMagic = fragment(1, 0, 2, new byte[]{1});
        badMagic[0] = 0;
        assertThrows(IllegalArgumentException.class, () -> AwMediaFragment.parse(badMagic));
    }

    @Test public void parsesNativeHeartbeatAndOpaqueMarkersWithoutTreatingThemAsMedia() {
        AwMediaFragment heartbeat = AwMediaFragment.parse(fragment(12, 0, AwMediaFragment.HEARTBEAT, new byte[0]));
        assertTrue(heartbeat.isHeartbeat());
        assertFalse(heartbeat.isVideo());
        assertFalse(heartbeat.isAudio());
        AwMediaFragment other = AwMediaFragment.parse(fragment(13, 0, 0x7f, new byte[]{1, 2}));
        assertFalse(other.isHeartbeat());
        assertFalse(other.isVideo());
        assertFalse(other.isAudio());
    }

    @Test public void parsesRawHeaderLayout() {
        AwFrameHeader header = AwFrameHeader.parse(fragment(0x1234, 9, 2, new byte[]{1, 2}));
        assertEquals(AwFrameHeader.MAGIC, header.magic());
        assertEquals(0x1234, header.sequence());
        assertEquals(2, header.payloadLength());
        assertEquals(9, header.sessionIndex());
        assertEquals(AwMediaFragment.VIDEO_START, header.marker());
        assertThrows(IllegalArgumentException.class, () -> AwFrameHeader.parse(new byte[9]));
    }

    @Test public void shortStartFragmentCompletesFrameImmediately() {
        AwFrameAssembler assembler = new AwFrameAssembler(true);
        assertArrayEquals(new byte[]{1}, assembler.accept(AwMediaFragment.parse(fragment(1, 0, 2, new byte[]{1}))));
        assertArrayEquals(new byte[]{2}, assembler.accept(AwMediaFragment.parse(fragment(2, 0, 2, new byte[]{2}))));
        assertArrayEquals(new byte[]{3}, assembler.accept(AwMediaFragment.parse(fragment(3, 0, 1, new byte[]{3}))));
    }

    @Test public void nativeReceiverCanDeliverShortContinuationWithoutPriorStart() {
        AwFrameAssembler assembler = new AwFrameAssembler(true);
        assertArrayEquals(new byte[]{4, 5}, assembler.accept(AwMediaFragment.parse(
                fragment(7, 6, AwMediaFragment.VIDEO_CONTINUATION, new byte[]{4, 5}))));
    }

    @Test public void discardsPartialFrameWhenSequenceGapPrecedesShortTail() {
        AwFrameAssembler assembler = new AwFrameAssembler(true);
        byte[] full = new byte[AwMediaFragment.VIDEO_FRAGMENT_LIMIT];
        byte[] tail = new byte[]{9, 8};
        assertNull(assembler.accept(AwMediaFragment.parse(fragment(10, 0, 2, full))));
        assertNull(assembler.accept(AwMediaFragment.parse(fragment(12, 0, 1, full))));
        assertNull(assembler.accept(AwMediaFragment.parse(fragment(13, 0, 1, tail))));
        assertArrayEquals(new byte[]{6}, assembler.accept(AwMediaFragment.parse(
                fragment(14, 0, 2, new byte[]{6}))));
    }

    @Test public void assemblesFrameWhenNativeSessionIndexByteChanges() {
        AwFrameAssembler assembler = new AwFrameAssembler(true);
        byte[] full = new byte[AwMediaFragment.VIDEO_FRAGMENT_LIMIT];
        assertNull(assembler.accept(AwMediaFragment.parse(fragment(10, 2, AwMediaFragment.VIDEO_START, full))));
        byte[] middle = new byte[AwMediaFragment.VIDEO_FRAGMENT_LIMIT];
        assertNull(assembler.accept(AwMediaFragment.parse(fragment(11, 3, AwMediaFragment.VIDEO_CONTINUATION, middle))));
        byte[] tail = new byte[]{2};
        assertArrayEquals(concat(concat(full, middle), tail), assembler.accept(AwMediaFragment.parse(
                fragment(12, 9, AwMediaFragment.VIDEO_CONTINUATION, tail))));
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    @Test public void assemblesAudioUntilFragmentIsShorterThan256Bytes() {
        AwFrameAssembler assembler = new AwFrameAssembler(false);
        byte[] full = new byte[AwMediaFragment.AUDIO_FRAGMENT_LIMIT];
        byte[] tail = new byte[]{7};
        assertNull(assembler.accept(AwMediaFragment.parse(fragment(30, 0, AwMediaFragment.AUDIO_START, full))));
        assertNull(assembler.accept(AwMediaFragment.parse(fragment(31, 0, AwMediaFragment.AUDIO_CONTINUATION, full))));
        byte[] audio = assembler.accept(AwMediaFragment.parse(fragment(32, 0, AwMediaFragment.AUDIO_CONTINUATION, tail)));
        assertEquals(2 * AwMediaFragment.AUDIO_FRAGMENT_LIMIT + 1, audio.length);
        assertEquals(7, audio[audio.length - 1]);
    }

    @Test public void shortAudioStartIsACompleteBlock() {
        AwFrameAssembler assembler = new AwFrameAssembler(false);
        byte[] block = new byte[]{5, 6};
        assertArrayEquals(block, assembler.accept(AwMediaFragment.parse(fragment(40, 0, AwMediaFragment.AUDIO_START, block))));
    }

    private static byte[] fragment(int sequence, int sessionIndex, int marker, byte[] payload) {
        byte[] b = new byte[8 + payload.length];
        b[0] = (byte)(AwFrameHeader.MAGIC >>> 8); b[1] = (byte)AwFrameHeader.MAGIC;
        b[2] = (byte)(payload.length >>> 8); b[3] = (byte)payload.length;
        b[4] = (byte)(sequence >>> 8); b[5] = (byte)sequence; b[6] = (byte)sessionIndex; b[7] = (byte)marker;
        System.arraycopy(payload, 0, b, 8, payload.length);
        return b;
    }
}
