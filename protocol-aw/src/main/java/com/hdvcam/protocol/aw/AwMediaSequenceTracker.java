package com.hdvcam.protocol.aw;

/** Tracks AW's datagram sequence across video, audio, and other non-heartbeat packets. */
public final class AwMediaSequenceTracker {
    private boolean hasPrevious;
    private int previousSequence;
    private long gaps;

    public synchronized void observe(AwMediaFragment fragment) {
        if (fragment == null || fragment.isHeartbeat()) return;
        int sequence = fragment.sequence();
        if (hasPrevious && ((sequence - previousSequence) & 0xffff) != 1) gaps++;
        previousSequence = sequence;
        hasPrevious = true;
    }

    public synchronized long gaps() { return gaps; }
}
