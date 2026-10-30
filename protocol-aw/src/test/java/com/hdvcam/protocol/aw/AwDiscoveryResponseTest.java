package com.hdvcam.protocol.aw;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class AwDiscoveryResponseTest {
    @Test public void validatesEnvelopeAndUsesUdpSourceAddress() {
        byte[] datagram = new byte[AwDiscoveryResponse.MINIMUM_LENGTH + 2];
        datagram[0] = (byte) 0xab; datagram[1] = (byte) 0xcd; datagram[2] = 0; datagram[3] = 0x48;
        datagram[4] = 0; datagram[5] = 0; datagram[6] = 1; datagram[7] = 0x15;
        byte[] uid = "camera-uid".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        System.arraycopy(uid, 0, datagram, AwDiscoveryResponse.UID_OFFSET, uid.length);
        datagram[AwDiscoveryResponse.MINIMUM_LENGTH] = 7;
        AwDiscoveryResponse response = AwDiscoveryResponse.parse(datagram, new byte[]{(byte) 192, (byte) 168, 10, 7});
        assertEquals("192.168.10.7", response.sourceAddress());
        assertEquals("camera-uid", response.uid());
        assertEquals(2, response.trailingLength());
        datagram[AwDiscoveryResponse.UID_OFFSET] = 'x';
        assertEquals('c', response.datagram()[AwDiscoveryResponse.UID_OFFSET]);
    }

    @Test public void rejectsWrongTypeAndTruncatedHeader() {
        assertThrows(IllegalArgumentException.class, () -> AwDiscoveryResponse.parse(new byte[7], new byte[]{1, 2, 3, 4}));
        assertThrows(IllegalArgumentException.class, () -> AwDiscoveryResponse.parse(
                new byte[]{(byte) 0xab, (byte) 0xcd, 0x00, 0x48, 0, 0, 1, 0x14}, new byte[]{1, 2, 3, 4}));
    }
}
