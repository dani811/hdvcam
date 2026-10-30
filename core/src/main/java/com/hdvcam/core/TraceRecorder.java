package com.hdvcam.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Thread-safe in-memory trace. Secrets and Wi-Fi credentials must never be recorded here. */
public final class TraceRecorder {
    private final List<TraceEvent> events = new ArrayList<>();

    public synchronized TraceEvent add(String component, String operation,
                                       Evidence evidence, String detail) {
        TraceEvent event = new TraceEvent(System.currentTimeMillis(), component,
                operation, evidence, detail);
        events.add(event);
        return event;
    }

    public synchronized List<TraceEvent> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(events));
    }

    public synchronized String exportText() {
        StringBuilder out = new StringBuilder();
        for (TraceEvent event : events) out.append(event.format()).append('\n');
        return out.toString();
    }

    public synchronized void clear() { events.clear(); }
}
