package com.hdvcam.protocol.aw;

import java.util.Arrays;

/** Removes the fixed prefix that the native NetCore callbacks omit before app delivery. */
public final class AwNetCoreMediaFrame {
    public static final int PREFIX_LENGTH = 16;

    private AwNetCoreMediaFrame() { }

    public static byte[] payload(byte[] frame) {
        if (frame == null || frame.length <= PREFIX_LENGTH) {
            throw new IllegalArgumentException("Frame AW no contiene payload tras el prefijo NetCore.");
        }
        return Arrays.copyOfRange(frame, PREFIX_LENGTH, frame.length);
    }
}
