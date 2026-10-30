package com.hdvcam.protocol.tutk;

/** Evidence-only contract: the proprietary IOTC/TUTK implementation is not bundled. */
public final class TutkDiscoveryContract {
    public static final int OBSERVED_TIMEOUT_MS = 1900;
    public static final int OBSERVED_INPUT_RECORD_BYTES = 42;
    public static final int OBSERVED_OUTPUT_SLOT_BYTES = 84;
    public static final int OBSERVED_TEMP_BUFFER_BYTES = 0x1f8;
    public static final int OBSERVED_QUERY_INTERVAL_US = 50_000;
    public static final int OBSERVED_INITIAL_QUERY_LENGTH = 0x34;
    public static final int OBSERVED_INITIAL_QUERY_COMMAND = 0x0601;
    public static final int OBSERVED_REPEAT_QUERY_LENGTH = 0x44;
    public static final int OBSERVED_REPEAT_QUERY_COMMAND = 0x0603;

    /** Static wrapper evidence from libaw_net_client.so; not a TUTK wire specification. */
    public static final int OBSERVED_NATIVE_SESSION_SLOTS = 3;
    public static final int OBSERVED_IO_TYPE_START_AUDIO = 0x00ff;
    public static final int OBSERVED_IO_TYPE_START_VIDEO = 0x01ff;
    public static final int OBSERVED_VIDEO_START_PAYLOAD_BYTES = 8;
    public static final int OBSERVED_VIDEO_RECEIVE_OFFSET_BYTES = 16;
    public static final String OBSERVED_AV_USERNAME = "admin";
    public static final String OBSERVED_AV_PASSWORD = "123456";
    public static final int OBSERVED_AV_CONNECT_TIMEOUT_MS = 20_000;
    public static final int OBSERVED_VIDEO_RECEIVE_BUFFER_BYTES = 0x4b000;
    public static final int OBSERVED_AUDIO_BUFFER_POLL_DELAY_MS = 500;
    public static final int OBSERVED_VIDEO_RETRY_ERROR = -20012;
    public static final int OBSERVED_VIDEO_RETRY_DELAY_MS = 100;

    private TutkDiscoveryContract() { }

    public static int maximumRecordsImpliedByTemporaryBuffer() {
        return OBSERVED_TEMP_BUFFER_BYTES / OBSERVED_INPUT_RECORD_BYTES;
    }

    public static String implementationStatus() {
        return "Evidence-only: opaque discovery and IOTC/AV call contract recovered; clean-room transport pending";
    }
}
