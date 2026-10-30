package com.hdvcam.core;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** A local, redaction-safe event used to make protocol experiments reproducible. */
public final class TraceEvent {
    private final long timestampMillis;
    private final String component;
    private final String operation;
    private final Evidence evidence;
    private final String detail;

    public TraceEvent(long timestampMillis, String component, String operation,
                      Evidence evidence, String detail) {
        this.timestampMillis = timestampMillis;
        this.component = component;
        this.operation = operation;
        this.evidence = evidence;
        this.detail = detail;
    }

    public long timestampMillis() { return timestampMillis; }
    public String component() { return component; }
    public String operation() { return operation; }
    public Evidence evidence() { return evidence; }
    public String detail() { return detail; }

    public String format() {
        String time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
                .format(new Date(timestampMillis));
        return time + "  [" + evidence.label() + "] " + component + " / "
                + operation + "\n" + detail;
    }
}
