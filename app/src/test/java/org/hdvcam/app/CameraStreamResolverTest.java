package org.hdvcam.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public final class CameraStreamResolverTest {
    @Test public void qzProbeRecognizesRtspStatusLinesIncludingAuthChallenge() {
        assertTrue(CameraStreamResolver.isRtspStatusLine("RTSP/1.0 200 OK"));
        assertTrue(CameraStreamResolver.isRtspStatusLine("RTSP/1.0 401 Unauthorized"));
        assertTrue(CameraStreamResolver.isRtspAuthenticationRequired("RTSP/1.0 401 Unauthorized"));
        assertFalse(CameraStreamResolver.isRtspAuthenticationRequired("RTSP/1.0 200 OK"));
    }

    @Test public void qzProbeRejectsGenericHttpAndMalformedResponses() {
        assertFalse(CameraStreamResolver.isRtspStatusLine("HTTP/1.1 200 OK"));
        assertFalse(CameraStreamResolver.isRtspStatusLine("RTSP hello"));
        assertFalse(CameraStreamResolver.isRtspStatusLine(null));
    }

    @Test public void familyDetectionPrioritizesKnownGatewayThenTriesEveryFamily() {
        assertEquals(Arrays.asList("YZ", "MStar", "QZ"), CameraStreamResolver.detectionOrder("192.168.169.1"));
        assertEquals(Arrays.asList("QZ", "MStar", "YZ"), CameraStreamResolver.detectionOrder("192.168.10.1"));
        assertEquals(Arrays.asList("MStar", "QZ", "YZ"), CameraStreamResolver.detectionOrder("192.168.1.1"));
        assertEquals(Arrays.asList("MStar", "QZ", "YZ"), CameraStreamResolver.detectionOrder("10.0.0.1"));
    }

    @Test public void familyDetectionRecognizesOnlyObservedCameraGateways() {
        assertEquals("MStar", CameraStreamResolver.familyForRoute("192.168.1.1"));
        assertEquals("QZ", CameraStreamResolver.familyForRoute("192.168.10.1"));
        assertEquals("YZ", CameraStreamResolver.familyForRoute("192.168.169.1"));
        assertEquals(null, CameraStreamResolver.familyForRoute("10.0.0.1"));
    }

    @Test public void mstarRtspUsesRtspSuffixFromPreviewConfiguration() {
        assertEquals("rtsp://192.168.1.1/liveRTSP/av0", CameraStreamResolver.buildMstarRtspUri("192.168.1.1", "0"));
        assertEquals("rtsp://192.168.1.1/liveRTSP/av/live", CameraStreamResolver.buildMstarRtspUri("192.168.1.1", "/live"));
    }

    @Test public void mstarRtspRejectsMissingSuffixAndMalformedHost() {
        assertInvalidMstarUri("192.168.1.1", "");
        assertInvalidMstarUri("rtsp://evil", "0");
    }

    private static void assertInvalidMstarUri(String host, String suffix) {
        try {
            CameraStreamResolver.buildMstarRtspUri(host, suffix);
            throw new AssertionError("Invalid MStar RTSP components should be rejected.");
        } catch (IllegalArgumentException expected) {
            // Expected validation result.
        }
    }
}
