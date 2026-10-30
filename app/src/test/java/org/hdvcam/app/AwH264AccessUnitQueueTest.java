package org.hdvcam.app;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class AwH264AccessUnitQueueTest {
    @Test public void overflowKeepsLatestAndWaitsForNextIdr() throws Exception {
        AwH264AccessUnitQueue queue = new AwH264AccessUnitQueue(2);
        byte[] first = {0, 0, 1, 0x41, 1};
        byte[] second = {0, 0, 0, 1, 0x41, 2};
        byte[] overflow = {0, 0, 1, 0x41, 3};

        assertTrue(queue.offer(first));
        assertTrue(queue.offer(second));
        assertFalse(queue.offer(overflow));
        assertArrayEquals(overflow, queue.take());
        assertTrue(queue.discardUntilIdr(first));
        assertFalse(queue.discardUntilIdr(new byte[]{0, 0, 0, 1, 0x65, 4}));
        assertFalse(queue.discardUntilIdr(first));
        assertEquals(1, queue.overflowCount());
        assertEquals(1, queue.discardedUntilIdrCount());
    }

    @Test public void closeWakesWaitingDecoder() throws Exception {
        AwH264AccessUnitQueue queue = new AwH264AccessUnitQueue(1);
        queue.close();
        assertNull(queue.take());
        assertFalse(queue.offer(new byte[]{0, 0, 1, 0x65}));
    }
}
