package com.hdvcam.protocol.mstar01;

public final class Mstar01StatusEvent {
    private final String action;
    private final String value;
    private final String field;

    Mstar01StatusEvent(String action, String field, String value) {
        this.action = action;
        this.field = field;
        this.value = value;
    }

    public String action() { return action; }
    public String field() { return field; }
    public String value() { return value; }
}
