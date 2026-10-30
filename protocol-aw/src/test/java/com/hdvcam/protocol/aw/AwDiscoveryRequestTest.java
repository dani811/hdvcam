package com.hdvcam.protocol.aw;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AwDiscoveryRequestTest {
    @Test public void createsObservedHeaderAndFixedLength() {
        byte[] packet = AwDiscoveryRequest.create();
        assertEquals(80, packet.length);
        assertEquals("AB CD 00 48 00 00 01 14", AwDiscoveryRequest.hex(
                java.util.Arrays.copyOf(packet, 8)));
        for (int i = 8; i < packet.length; i++) assertEquals(0, packet[i]);
    }

    @Test public void exposesObservedPorts() {
        assertEquals(21600, AwDiscoveryRequest.UDP_DESTINATION_PORT);
        assertEquals(21601, AwDiscoveryRequest.UDP_LISTEN_PORT);
    }
}
