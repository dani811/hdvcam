package com.hdvcam.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class NativeDiscoveryRecordTest {
    @Test public void parsesV3LayoutAndLittleEndianCallbackPort() {
        byte[] payload = new byte[82];
        put(payload, 0, 64, "v3-device");
        put(payload, 64, 16, "192.168.1.90");
        payload[80] = 0x34; payload[81] = 0x12;
        NativeDiscoveryRecord record = NativeDiscoveryRecord.parse("V3", payload);
        assertEquals("v3-device", record.uid());
        assertEquals("192.168.1.90", record.address());
        assertEquals(0x1234, record.port());
        assertEquals(0, record.firmwareSelector());
    }

    @Test public void parsesV316Layout() {
        byte[] payload = new byte[39];
        put(payload, 0, 21, "v316-device");
        put(payload, 21, 16, "192.168.10.50");
        payload[37] = (byte) 0xd2; payload[38] = 0x04;
        NativeDiscoveryRecord record = NativeDiscoveryRecord.parse("V316", payload);
        assertEquals("v316-device", record.uid());
        assertEquals("192.168.10.50", record.address());
        assertEquals(1234, record.port());
        assertEquals(1, record.firmwareSelector());
    }

    @Test public void rejectsUnsupportedFamilyAndTruncatedCallback() {
        assertThrows(IllegalArgumentException.class, () -> NativeDiscoveryRecord.parse("MSTAR01", new byte[100]));
        assertThrows(IllegalArgumentException.class, () -> NativeDiscoveryRecord.parse("V3", new byte[20]));
    }

    private static void put(byte[] target, int offset, int width, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, target, offset, Math.min(width, bytes.length));
    }
}
