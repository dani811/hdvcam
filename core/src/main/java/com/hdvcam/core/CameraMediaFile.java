package com.hdvcam.core;

import java.util.Locale;

/**
 * Clean-room media row mapped from the positional fields consumed by JADX's K2.e mapper.
 * This models mapped rows, not a full SQLite schema or camera DB client.
 */
public final class CameraMediaFile {
    public static final String PHOTO = "photo";
    public static final String VIDEO = "video";

    private final String name;
    private final String type;
    private final String url;
    private final Long sizeBytes;
    private final Long time;
    private final String secureVideoUrl;
    private final String thumbnailUrl;

    private CameraMediaFile(String name, String type, String url, Long sizeBytes, Long time,
                            String secureVideoUrl, String thumbnailUrl) {
        this.name = name;
        this.type = type;
        this.url = url;
        this.sizeBytes = sizeBytes;
        this.time = time;
        this.secureVideoUrl = secureVideoUrl;
        this.thumbnailUrl = thumbnailUrl;
    }

    /** MSTAR01 K2.e.b: SELECT * FROM DCF; observed cursor positions 1,2,3,7. */
    public static CameraMediaFile fromMstarDcfRow(String[] columns) {
        requireColumns(columns, 8, "DCF");
        String path = value(columns, 1);
        String extensionProbe = path.toUpperCase(Locale.ROOT);
        String type = extensionProbe.contains("JPG") ? PHOTO : extensionProbe.contains("MP4") ? VIDEO : "";
        String secure = type.equals(VIDEO) ? join("http://192.168.1.1", value(columns, 2)) : null;
        String thumbnail = emptyToNull(join("http://192.168.1.1", value(columns, 3)));
        return new CameraMediaFile(basename(path), type, join("http://192.168.1.1", path),
                parseLong(value(columns, 7)), null, emptyToNull(secure), thumbnail);
    }

    /** V316 K2.e.c: SELECT * FROM MediaFile100; the JSON mapper reads cursor position 2. */
    public static CameraMediaFile fromV316MediaFile100Row(String[] columns) {
        requireColumns(columns, 3, "MediaFile100");
        String path = value(columns, 2);
        String lower = path.toLowerCase(Locale.ROOT);
        String type = lower.contains("jpg") ? PHOTO : VIDEO;
        String thumbPath = type.equals(VIDEO) ? path.replace(".mp4", ".thm") : path.replace(".jpg", ".thm");
        String securePath = type.equals(VIDEO) ? path.replace(".mp4", ".sec") : "";
        String base = "http://192.168.10.1:8082/file/media/";
        return new CameraMediaFile(basename(path), type, base + path, null, null,
                securePath.isEmpty() ? null : base + securePath,
                thumbPath.isEmpty() ? null : base + thumbPath);
    }

    /** V3 K2.e.d: SELECT * FROM CdrFile; observed cursor positions 0,1,2. */
    public static CameraMediaFile fromV3CdrFileRow(String[] columns) {
        requireColumns(columns, 3, "CdrFile");
        String path = value(columns, 0);
        Long time = parseLong(value(columns, 1));
        String type = value(columns, 2);
        return new CameraMediaFile(basename(path), type,
                "http://192.168.100.1:80/" + path, null, time, null, null);
    }

    public String name() { return name; }
    public String type() { return type; }
    public String url() { return url; }
    public Long sizeBytes() { return sizeBytes; }
    public Long time() { return time; }
    public String secureVideoUrl() { return secureVideoUrl; }
    public String thumbnailUrl() { return thumbnailUrl; }

    private static void requireColumns(String[] columns, int min, String table) {
        if (columns == null || columns.length < min) {
            throw new IllegalArgumentException(table + " mapper requires at least " + min + " cursor columns.");
        }
    }

    private static String value(String[] columns, int index) {
        return columns[index] == null ? "" : columns[index];
    }

    private static String basename(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static Long parseLong(String value) {
        if (value == null || value.isEmpty()) return null;
        try { return Long.parseLong(value); }
        catch (NumberFormatException invalid) { return null; }
    }

    private static String join(String base, String path) {
        if (path.isEmpty()) return "";
        return base + path;
    }

    private static String emptyToNull(String value) { return value == null || value.isEmpty() ? null : value; }
}
