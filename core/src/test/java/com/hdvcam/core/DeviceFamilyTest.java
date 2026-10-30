package com.hdvcam.core;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class DeviceFamilyTest {
    @Test public void mapsFirmwareFamiliesToObservedSelectors() {
        assertEquals(0, DeviceFamily.V3.selector());
        assertEquals(1, DeviceFamily.V316.selector());
        assertEquals(2, DeviceFamily.MSTAR01.selector());
    }
}
