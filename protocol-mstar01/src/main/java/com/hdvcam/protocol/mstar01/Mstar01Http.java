package com.hdvcam.protocol.mstar01;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Builds observed MSTAR01 Config.cgi requests. Does not perform network I/O. */
public final class Mstar01Http {
    public static final String BASE_URL = "http://192.168.1.1/cgi-bin/Config.cgi";

    private Mstar01Http() { }

    public static String get(String property) {
        return BASE_URL + "?action=get&property=" + encode(property);
    }

    public static String set(String property, String value) {
        return BASE_URL + "?action=set&property=" + encode(property)
                + "&value=" + encode(value);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
