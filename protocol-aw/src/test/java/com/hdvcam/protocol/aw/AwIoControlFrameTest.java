package com.hdvcam.protocol.aw;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class AwIoControlFrameTest {
    @Test public void roundTripsNativeControlEnvelope() {
        byte[] payload = new byte[]{1, 2, 3, 4};
        byte[] wire = AwIoControlFrame.encode(AwIoControlFrame.TYPE_IO_REQUEST, payload);
        assertEquals(12, wire.length);
        assertEquals(0xab, wire[0] & 0xff);
        assertEquals(4, wire[3] & 0xff);
        assertEquals(0x01, wire[6] & 0xff);
        assertEquals(0x12, wire[7] & 0xff);
        AwIoControlFrame parsed = AwIoControlFrame.parse(wire);
        assertEquals(AwIoControlFrame.TYPE_IO_REQUEST, parsed.type());
        assertArrayEquals(payload, parsed.payload());
    }

    @Test public void requiresExactFrameLengthAndMagic() {
        assertThrows(IllegalArgumentException.class, () -> AwIoControlFrame.parse(new byte[7]));
        assertThrows(IllegalArgumentException.class, () -> AwIoControlFrame.parse(new byte[8]));
        assertThrows(IllegalArgumentException.class, () -> AwIoControlFrame.encode(0x112, null));
    }
}
