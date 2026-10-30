package com.hdvcam.protocol.aw;

import java.io.ByteArrayOutputStream;

/** Reassembles transport payloads using native start and short-tail markers. */
public final class AwFrameAssembler {
    private static final int MAX_FRAME_BYTES = 8 * 1024 * 1024;
    private static final int MAX_PENDING_FRAGMENTS = 8192;
    private final boolean video;
    private ByteArrayOutputStream current;
    private int pendingFragments;
    private int previousSequence = -1;
    private boolean awaitingStart;

    public AwFrameAssembler(boolean video) { this.video = video; }

    /** Returns a complete encoded frame/audio block, or null until completion. */
    public synchronized byte[] accept(AwMediaFragment fragment) {
        if (fragment == null || (video ? !fragment.isVideo() : !fragment.isAudio())) return resetAndNull();
        int sequence = fragment.sequence();
        if (previousSequence >= 0 && ((sequence - previousSequence) & 0xffff) != 1) {
            // The native path records a global sequence gap but keeps consuming
            // the current frame. We instead discard the partial unit so a lost
            // UDP fragment cannot prefix the next decoded access unit.
            resetFrame();
            awaitingStart = true;
        }
        previousSequence = sequence;
        if (awaitingStart && !fragment.startsFrame()) return null;
        if (fragment.startsFrame()) {
            awaitingStart = false;
            resetFrame();
            current = new ByteArrayOutputStream(Math.min(fragment.fragmentLength() * 2, 65536));
            pendingFragments = 1;
            append(fragment.payload());
            if (fragment.fragmentLength() < limit()) return finish();
            return null;
        }
        // Native Recv_Local appends a continuation to its current buffer even
        // when no start marker has been seen since initialization/reset.
        if (current == null) current = new ByteArrayOutputStream();
        // Sequence tracking is shared across audio/video in AwTcpSession, like the native UdpClient.
        if (++pendingFragments > MAX_PENDING_FRAGMENTS) return resetAndNull();
        append(fragment.payload());
        if (fragment.fragmentLength() < limit()) return finish();
        return null;
    }

    public synchronized void reset() { resetFrame(); previousSequence = -1; awaitingStart = false; }

    private void resetFrame() { current = null; pendingFragments = 0; }

    private void append(byte[] bytes) {
        if (current.size() + bytes.length > MAX_FRAME_BYTES) { reset(); return; }
        current.write(bytes, 0, bytes.length);
    }

    private byte[] finish() {
        if (current == null) return null;
        byte[] result = current.toByteArray();
        reset();
        return result;
    }

    private byte[] resetAndNull() { reset(); return null; }
    private int limit() { return video ? AwMediaFragment.VIDEO_FRAGMENT_LIMIT : AwMediaFragment.AUDIO_FRAGMENT_LIMIT; }
}
