package com.hdvcam.core;

/** Firmware family and native selector observed in the original coordinator. */
public enum DeviceFamily {
    V3("V3", 0, "AW / IOTC", Evidence.CONFIRMED),
    V316("V316", 1, "TUTK", Evidence.CONFIRMED),
    MSTAR01("MSTAR01", 2, "HTTP local / RTSP", Evidence.PARTIAL);

    private final String label;
    private final int selector;
    private final String transport;
    private final Evidence evidence;

    DeviceFamily(String label, int selector, String transport, Evidence evidence) {
        this.label = label;
        this.selector = selector;
        this.transport = transport;
        this.evidence = evidence;
    }

    public String label() { return label; }
    public int selector() { return selector; }
    public String transport() { return transport; }
    public Evidence evidence() { return evidence; }

    @Override public String toString() { return label; }
}
