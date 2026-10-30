package com.hdvcam.core;

/** Static media-player facts from E3.d; the 8K profile is selected only for p063m3.G. */
public final class VideoStreamProfile {
    public static final int NORMAL_MAX_CACHED_DURATION_MS = 3000;
    public static final int PROFILE_8K_MAX_CACHED_DURATION_MS = 100;

    private VideoStreamProfile() { }

    /** The original 8K override is applied only to the MStar controller and is case-sensitive. */
    public static int maxCachedDurationMs(String mstarDeviceName) {
        return mstarDeviceName != null && mstarDeviceName.contains("8K")
                ? PROFILE_8K_MAX_CACHED_DURATION_MS : NORMAL_MAX_CACHED_DURATION_MS;
    }

    public static final String NORMAL_OPTIONS =
            "rtsp_transport=tcp; fflags=nobuffer; analyzemaxduration=5000; flush_packets=1; " +
            "start-on-prepared=1; packet-buffering=0; infbuf=1; framedrop=30; fps=30; fast=1; " +
            "no_delay=1; flags=low_delay; skip_loop_filter=0; mediacodec=0; " +
            "mediacodec-auto-rotate=0; mediacodec-handle-resolution-change=0; " +
            "dns_cache_clear=1; http-detect-range-support=0; min-frames=3; max_cached_duration=3000";

    public static final String PROFILE_8K_OPTIONS =
            "rtsp_transport=tcp; fflags=nobuffer; packet-buffering=0; framedrop=3; " +
            "max_cached_duration=100; infbuf=0; an=1; render-wait-start=0; find_stream_info=0; " +
            "fast=1; flush_packets=1; reorder_queue_size=0; max_delay=100000; " +
            "analyzeduration=500000; fpsprobesize=0; buffer_size=262144; flags=low_delay; skip_loop_filter=0";

    public static final long STALL_RESTART_THRESHOLD_MS = 3000;
    public static final String LOCAL_CHANNEL_TEMPLATE = "rtsp://<route-ip>:8554/ch00";
    public static final String LIVE_AV_TEMPLATE = "rtsp://<host>/liveRTSP/av + Camera.Preview.RTSP.av";
}
