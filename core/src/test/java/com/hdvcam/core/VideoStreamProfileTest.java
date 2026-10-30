package com.hdvcam.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class VideoStreamProfileTest {
    @Test public void selectsNormalCacheDurationWithoutExact8kMarker() {
        assertEquals(3000, VideoStreamProfile.maxCachedDurationMs(null));
        assertEquals(3000, VideoStreamProfile.maxCachedDurationMs("CAMERA 8k"));
    }

    @Test public void selectsObserved8kCacheDuration() {
        assertEquals(100, VideoStreamProfile.maxCachedDurationMs("MSTAR 8K CAMERA"));
    }
}
