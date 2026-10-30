package com.hdvcam.protocol.tutk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.fail;

import org.junit.Test;

public class TutkDiscoveryContractTest {
    @Test public void recordsObservedNativeCallShape() {
        assertEquals(1900, TutkDiscoveryContract.OBSERVED_TIMEOUT_MS);
        assertEquals(12, TutkDiscoveryContract.maximumRecordsImpliedByTemporaryBuffer());
        assertEquals(84, TutkDiscoveryContract.OBSERVED_OUTPUT_SLOT_BYTES);
        assertEquals(50_000, TutkDiscoveryContract.OBSERVED_QUERY_INTERVAL_US);
        assertEquals(0x34, TutkDiscoveryContract.OBSERVED_INITIAL_QUERY_LENGTH);
        assertEquals(0x0601, TutkDiscoveryContract.OBSERVED_INITIAL_QUERY_COMMAND);
        assertEquals(0x44, TutkDiscoveryContract.OBSERVED_REPEAT_QUERY_LENGTH);
        assertEquals(0x0603, TutkDiscoveryContract.OBSERVED_REPEAT_QUERY_COMMAND);
        assertEquals("admin", TutkDiscoveryContract.OBSERVED_AV_USERNAME);
        assertEquals("123456", TutkDiscoveryContract.OBSERVED_AV_PASSWORD);
        assertEquals(20_000, TutkDiscoveryContract.OBSERVED_AV_CONNECT_TIMEOUT_MS);
        assertEquals(0x4b000, TutkDiscoveryContract.OBSERVED_VIDEO_RECEIVE_BUFFER_BYTES);
        assertEquals(16, TutkDiscoveryContract.OBSERVED_VIDEO_RECEIVE_OFFSET_BYTES);
        assertEquals(-20012, TutkDiscoveryContract.OBSERVED_VIDEO_RETRY_ERROR);
        assertEquals(100, TutkDiscoveryContract.OBSERVED_VIDEO_RETRY_DELAY_MS);
        assertEquals(500, TutkDiscoveryContract.OBSERVED_AUDIO_BUFFER_POLL_DELAY_MS);
    }

    @Test public void copiesOpaqueRecordWithoutInterpretingOrAliasingIt() {
        byte[] source = new byte[90];
        for (int i = 0; i < source.length; i++) source[i] = (byte) i;
        OpaqueLanSearchRecord record = OpaqueLanSearchRecord.copyOf(source, 7);
        byte[] result = record.toByteArray();
        assertEquals(42, result.length);
        assertEquals(7, result[0] & 0xff);
        assertEquals(48, result[41] & 0xff);
        assertNotSame(result, record.toByteArray());
        source[7] = 99;
        assertArrayEquals(range(7, 42), record.toByteArray());
    }

    @Test public void rejectsTruncatedOpaqueRecord() {
        try {
            OpaqueLanSearchRecord.copyOf(new byte[41], 0);
            fail("debe rechazar un registro truncado");
        } catch (IllegalArgumentException expected) {
            assertEquals("Registro TUTK truncado", expected.getMessage());
        }
    }

    private static byte[] range(int start, int length) {
        byte[] result = new byte[length];
        for (int i = 0; i < length; i++) result[i] = (byte) (start + i);
        return result;
    }
}
