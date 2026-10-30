package com.hdvcam.protocol.aw;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;

public class AwLoginFrameTest {
    @Test public void createsObservedLoginEnvelopeAndFields() {
        byte[] request = AwLoginRequest.create(0);
        assertEquals(AwLoginRequest.FRAME_LENGTH, request.length);
        assertArrayEquals(new byte[]{(byte) 0xab, (byte) 0xcd, 0, (byte) 0x81, 0, 0, 1, 0x10},
                Arrays.copyOf(request, 8));
        assertEquals("admin", asciiField(request, AwLoginRequest.USERNAME_OFFSET));
        assertEquals("12345", asciiField(request, AwLoginRequest.PASSWORD_OFFSET));
        assertEquals(0, request[8 + AwLoginRequest.SESSION_INDEX_OFFSET] & 0xff);
    }

    @Test public void parsesNegotiatedUdpSelectorFromAckType111() {
        byte[] response = new byte[AwLoginRequest.FRAME_LENGTH];
        response[0] = (byte) 0xab; response[1] = (byte) 0xcd;
        response[2] = 0; response[3] = (byte) 0x81;
        response[4] = 0; response[5] = 0; response[6] = 1; response[7] = 0x11;
        response[8 + AwLoginRequest.SESSION_INDEX_OFFSET] = 7;
        AwLoginAck ack = AwLoginAck.parse(response);
        assertEquals(7, ack.udpSelector());
        assertEquals(6676, ack.udpPeerPort());
    }

    @Test public void rejectsNonAckFramesAndInvalidPorts() {
        assertThrows(IllegalArgumentException.class, () -> AwLoginRequest.create(256));
        assertThrows(IllegalArgumentException.class, () -> AwLoginAck.parse(new byte[8]));
        byte[] response = AwLoginRequest.create(0);
        assertThrows(IllegalArgumentException.class, () -> AwLoginAck.parse(response));
        byte[] ackFrame = new byte[AwLoginRequest.FRAME_LENGTH];
        ackFrame[0] = (byte) 0xab; ackFrame[1] = (byte) 0xcd;
        ackFrame[2] = 0; ackFrame[3] = (byte) 0x81;
        ackFrame[6] = 1;
        ackFrame[7] = 0x11;
        assertEquals(0, AwLoginAck.parse(ackFrame).udpSelector());
    }

    private static String asciiField(byte[] frame, int bodyOffset) {
        int offset = 8 + bodyOffset;
        int end = offset;
        while (end < frame.length && frame[end] != 0) end++;
        return new String(frame, offset, end - offset, StandardCharsets.US_ASCII);
    }
}
