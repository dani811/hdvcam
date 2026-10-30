package com.hdvcam.protocol.mstar01;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class Mstar01ProtocolTest {
    @Test public void buildsObservedConfigRequests() {
        assertEquals("http://192.168.1.1/cgi-bin/Config.cgi?action=get&property=StreamStatus",
                Mstar01Http.get("StreamStatus"));
        assertEquals("http://192.168.1.1/cgi-bin/Config.cgi?action=set&property=StreamStatus&value=ON",
                Mstar01Http.set("StreamStatus", "ON"));
        assertEquals("http://192.168.1.1/cgi-bin/Config.cgi?action=set&property=TimeSettings&value=2026%2410%2430%2412%2405%2400",
                Mstar01Http.set("TimeSettings", "2026$10$30$12$05$00"));
    }

    @Test public void parsesKnownStatusAndTimerEvents() {
        Mstar01StatusEvent status = Mstar01StatusParser.parse(
                "action=rec\nstatus=1\n").orElseThrow();
        assertEquals("rec", status.action());
        assertEquals("status", status.field());
        assertEquals("1", status.value());

        Mstar01StatusEvent timer = Mstar01StatusParser.parse(
                "action=rec_time\ntime=00:01:42").orElseThrow();
        assertEquals("time", timer.field());
        assertEquals("00:01:42", timer.value());
    }

    @Test public void rejectsUnknownOrIncompleteDatagrams() {
        assertFalse(Mstar01StatusParser.parse("status=1").isPresent());
        assertFalse(Mstar01StatusParser.parse("action=unknown\nstatus=1").isPresent());
        assertFalse(Mstar01StatusParser.parse("action=rec").isPresent());
    }
}
