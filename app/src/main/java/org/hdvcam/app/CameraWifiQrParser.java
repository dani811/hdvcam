package org.hdvcam.app;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the reference app's bracket fields and standard WIFI QR payloads. */
final class CameraWifiQrParser {
    static final class Connection {
        final String ssid;
        final String password;

        private Connection(String ssid, String password) {
            this.ssid = ssid;
            this.password = password == null ? "" : password;
        }
    }

    private CameraWifiQrParser() { }

    static Connection parse(String content) {
        if (content == null) return null;
        String ssid = bracketField(content, "ssid");
        if (ssid != null) return new Connection(ssid.trim(), bracketField(content, "pass"));
        if (!content.regionMatches(true, 0, "WIFI:", 0, 5)) return null;
        ssid = wifiField(content, "S");
        return ssid == null ? null : new Connection(ssid, wifiField(content, "P"));
    }

    private static String bracketField(String content, String name) {
        Matcher matcher = Pattern.compile("(?:^|\\s)" + name + ":\\[(.*?)\\s*\\]",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(content);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** Reads WIFI:T:WPA;S:...;P:...;;, honoring backslash-escaped separators. */
    private static String wifiField(String content, String wantedKey) {
        int cursor = 5;
        while (cursor < content.length()) {
            while (cursor < content.length() && content.charAt(cursor) == ';') cursor++;
            int keyStart = cursor;
            while (cursor < content.length() && content.charAt(cursor) != ':' && content.charAt(cursor) != ';') cursor++;
            if (cursor >= content.length() || content.charAt(cursor) != ':') {
                while (cursor < content.length() && content.charAt(cursor) != ';') cursor++;
                continue;
            }
            String key = content.substring(keyStart, cursor++);
            StringBuilder value = new StringBuilder();
            boolean escaped = false;
            while (cursor < content.length()) {
                char current = content.charAt(cursor++);
                if (escaped) {
                    value.append(current);
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == ';') {
                    break;
                } else {
                    value.append(current);
                }
            }
            if (escaped) value.append('\\');
            if (wantedKey.equalsIgnoreCase(key)) return value.toString();
        }
        return null;
    }
}
