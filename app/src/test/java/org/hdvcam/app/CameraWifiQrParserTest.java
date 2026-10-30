package org.hdvcam.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class CameraWifiQrParserTest {
    @Test public void parsesReferenceBracketFormat() {
        CameraWifiQrParser.Connection connection = CameraWifiQrParser.parse("ssid:[CAMERA_123] pass:[secret]");
        assertEquals("CAMERA_123", connection.ssid);
        assertEquals("secret", connection.password);
    }

    @Test public void preservesEscapedWifiQrSeparators() {
        CameraWifiQrParser.Connection connection = CameraWifiQrParser.parse("WIFI:T:WPA;S:Cam\\;North;P:a\\;b\\\\c;;");
        assertEquals("Cam;North", connection.ssid);
        assertEquals("a;b\\c", connection.password);
    }

    @Test public void acceptsOpenWifiQrWithoutPassword() {
        CameraWifiQrParser.Connection connection = CameraWifiQrParser.parse("WIFI:T:nopass;S:OpenCamera;;");
        assertEquals("OpenCamera", connection.ssid);
        assertEquals("", connection.password);
    }

    @Test public void rejectsPayloadWithoutWifiName() {
        assertNull(CameraWifiQrParser.parse("not a camera QR"));
        assertNull(CameraWifiQrParser.parse("WIFI:T:WPA;P:secret;;"));
    }
}
