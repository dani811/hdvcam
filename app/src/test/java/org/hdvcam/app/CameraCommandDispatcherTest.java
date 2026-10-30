package org.hdvcam.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class CameraCommandDispatcherTest {
    @Test public void qzPtzDirectionIdsMatchRecoveredSdkEnum() {
        assertEquals("http://192.168.10.1:8082/api/setdeviceinfo/?custom=1&cmd=4005&par=0", CameraCommandDispatcher.qzPtzUrl(0));
        assertEquals("http://192.168.10.1:8082/api/setdeviceinfo/?custom=1&cmd=4005&par=1", CameraCommandDispatcher.qzPtzUrl(1));
        assertEquals("http://192.168.10.1:8082/api/setdeviceinfo/?custom=1&cmd=4005&par=4", CameraCommandDispatcher.qzPtzUrl(4));
        assertEquals("http://192.168.10.1:8082/api/setdeviceinfo/?custom=1&cmd=4005&par=5", CameraCommandDispatcher.qzPtzUrl(5));
    }

    @Test public void qzPtzRejectsUnknownDirectionIds() {
        assertThrows(IllegalArgumentException.class, () -> CameraCommandDispatcher.qzPtzUrl(-1));
        assertThrows(IllegalArgumentException.class, () -> CameraCommandDispatcher.qzPtzUrl(6));
    }
}
