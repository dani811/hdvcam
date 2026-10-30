package com.hdvcam.protocol.aw;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class AwNetCoreMediaFrameTest {
    @Test public void stripsFixedNativePrefixFromMediaPayload() {
        byte[] frame = new byte[AwNetCoreMediaFrame.PREFIX_LENGTH + 4];
        frame[AwNetCoreMediaFrame.PREFIX_LENGTH] = 0;
        frame[AwNetCoreMediaFrame.PREFIX_LENGTH + 1] = 0;
        frame[AwNetCoreMediaFrame.PREFIX_LENGTH + 2] = 1;
        frame[AwNetCoreMediaFrame.PREFIX_LENGTH + 3] = 0x65;
        assertArrayEquals(new byte[]{0, 0, 1, 0x65}, AwNetCoreMediaFrame.payload(frame));
    }

    @Test public void rejectsFrameWithoutPayloadAfterNativePrefix() {
        assertThrows(IllegalArgumentException.class,
                () -> AwNetCoreMediaFrame.payload(new byte[AwNetCoreMediaFrame.PREFIX_LENGTH]));
    }
}
