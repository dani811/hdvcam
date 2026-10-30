package com.hdvcam.protocol.aw;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class AwMediaSequenceTrackerTest {
    @Test public void tracksOneDatagramSequenceAcrossVideoAudioAndShortFrames() {
        AwMediaSequenceTracker tracker = new AwMediaSequenceTracker();
        tracker.observe(fragment(10, AwMediaFragment.VIDEO_START));
        tracker.observe(fragment(11, AwMediaFragment.VIDEO_CONTINUATION));
        tracker.observe(fragment(13, AwMediaFragment.AUDIO_START));
        tracker.observe(fragment(14, AwMediaFragment.VIDEO_START));
        assertEquals(1L, tracker.gaps());
    }

    @Test public void heartbeatDoesNotAdvanceNativeMediaSequenceBaseline() {
        AwMediaSequenceTracker tracker = new AwMediaSequenceTracker();
        tracker.observe(fragment(40, AwMediaFragment.VIDEO_START));
        tracker.observe(fragment(300, AwMediaFragment.HEARTBEAT));
        tracker.observe(fragment(41, AwMediaFragment.AUDIO_START));
        assertEquals(0L, tracker.gaps());
    }

    @Test public void countsUnsignedSequenceWrapAsContiguous() {
        AwMediaSequenceTracker tracker = new AwMediaSequenceTracker();
        tracker.observe(fragment(0xfffe, AwMediaFragment.VIDEO_START));
        tracker.observe(fragment(0xffff, AwMediaFragment.VIDEO_CONTINUATION));
        tracker.observe(fragment(0, AwMediaFragment.AUDIO_CONTINUATION));
        assertEquals(0L, tracker.gaps());
    }

    private static AwMediaFragment fragment(int sequence, int marker) {
        byte[] packet = new byte[AwMediaFragment.HEADER_LENGTH + 1];
        packet[0] = (byte) 0xbc; packet[1] = (byte) 0xde;
        packet[2] = 0; packet[3] = 1;
        packet[4] = (byte) (sequence >>> 8); packet[5] = (byte) sequence;
        packet[6] = 0; packet[7] = (byte) marker; packet[8] = 1;
        return AwMediaFragment.parse(packet);
    }
}
