package org.hdvcam.app;

import java.util.concurrent.ArrayBlockingQueue;

/** Bounded handoff from the UDP receiver to the decoder, with keyframe resync after overflow. */
final class AwH264AccessUnitQueue {
    static final int DEFAULT_CAPACITY = 8;
    private static final byte[] STOP = new byte[0];

    private final ArrayBlockingQueue<byte[]> queue;
    private boolean waitingForIdr;
    private boolean closed;
    private long overflowCount;
    private long discardedUntilIdrCount;

    AwH264AccessUnitQueue() { this(DEFAULT_CAPACITY); }

    AwH264AccessUnitQueue(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        queue = new ArrayBlockingQueue<>(capacity);
    }

    /** Returns false when overflow forced a queue flush; the current frame is still retained. */
    synchronized boolean offer(byte[] accessUnit) {
        if (closed || accessUnit == null || accessUnit.length == 0) return false;
        if (queue.offer(accessUnit)) return true;
        queue.clear();
        waitingForIdr = true;
        overflowCount++;
        queue.offer(accessUnit);
        return false;
    }

    byte[] take() throws InterruptedException {
        byte[] value = queue.take();
        return value == STOP ? null : value;
    }

    synchronized boolean discardUntilIdr(byte[] accessUnit) {
        if (!waitingForIdr) return false;
        if (!containsIdr(accessUnit)) {
            discardedUntilIdrCount++;
            return true;
        }
        waitingForIdr = false;
        return false;
    }

    synchronized void requireIdr() {
        if (closed) return;
        queue.clear();
        waitingForIdr = true;
    }

    synchronized long overflowCount() { return overflowCount; }
    synchronized long discardedUntilIdrCount() { return discardedUntilIdrCount; }
    synchronized boolean isWaitingForIdr() { return waitingForIdr; }

    synchronized void close() {
        if (closed) return;
        closed = true;
        queue.clear();
        queue.offer(STOP);
    }

    private static boolean containsIdr(byte[] bytes) {
        for (int i = 0; i + 3 < bytes.length; i++) {
            int header = bytes[i] == 0 && bytes[i + 1] == 0 && bytes[i + 2] == 1 ? i + 3
                    : i + 4 < bytes.length && bytes[i] == 0 && bytes[i + 1] == 0
                    && bytes[i + 2] == 0 && bytes[i + 3] == 1 ? i + 4 : -1;
            if (header >= 0 && header < bytes.length && (bytes[header] & 0x1f) == 5) return true;
        }
        return false;
    }
}
