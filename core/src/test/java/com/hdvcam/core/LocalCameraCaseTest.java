package com.hdvcam.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LocalCameraCaseTest {
    @Test public void mapsObservedLocalRoutesToScreens() {
        assertEquals("CamConAtv", LocalCameraCase.fromRouteAddress("192.168.1.1").orElseThrow().screenAlias());
        assertEquals("CamConQZAtv", LocalCameraCase.fromRouteAddress("192.168.10.1").orElseThrow().screenAlias());
        assertEquals("CamConYZAtv", LocalCameraCase.fromRouteAddress("192.168.169.1").orElseThrow().screenAlias());
    }

    @Test public void rejectsUnknownAndMissingRoutes() {
        assertFalse(LocalCameraCase.fromRouteAddress("192.168.0.1").isPresent());
        assertFalse(LocalCameraCase.fromRouteAddress(null).isPresent());
    }

    @Test public void trimsAddressWhitespace() {
        assertTrue(LocalCameraCase.fromRouteAddress(" 192.168.1.1 ").isPresent());
    }
}
