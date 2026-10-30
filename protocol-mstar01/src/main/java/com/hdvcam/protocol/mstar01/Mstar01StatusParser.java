package com.hdvcam.protocol.mstar01;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Parser for the text event envelope recovered from the MSTAR01 UDP worker. */
public final class Mstar01StatusParser {
    private Mstar01StatusParser() { }

    public static Optional<Mstar01StatusEvent> parse(String datagram) {
        if (datagram == null) return Optional.empty();
        Map<String, String> fields = new HashMap<>();
        for (String line : datagram.trim().split("\\R")) {
            int split = line.indexOf('=');
            if (split > 0) {
                fields.put(line.substring(0, split).trim(),
                        line.substring(split + 1).trim());
            }
        }

        String action = fields.get("action");
        if (action == null) return Optional.empty();
        switch (action) {
            case "sd_format":
            case "sd_status":
            case "cap":
            case "rec":
            case "rtsp":
                return event(action, "status", fields.get("status"));
            case "rec_time":
            case "cap_countdown":
                return event(action, "time", fields.get("time"));
            default:
                return Optional.empty();
        }
    }

    private static Optional<Mstar01StatusEvent> event(String action, String field, String value) {
        return value == null ? Optional.empty()
                : Optional.of(new Mstar01StatusEvent(action, field, value));
    }
}
