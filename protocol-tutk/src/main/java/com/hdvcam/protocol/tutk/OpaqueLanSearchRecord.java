package com.hdvcam.protocol.tutk;

import java.util.Arrays;

/** Raw 42-byte IOTC_Lan_Search result. Its proprietary field layout is not yet known. */
public final class OpaqueLanSearchRecord {
    public static final int BYTE_LENGTH = TutkDiscoveryContract.OBSERVED_INPUT_RECORD_BYTES;
    private final byte[] bytes;

    private OpaqueLanSearchRecord(byte[] bytes) {
        this.bytes = bytes;
    }

    public static OpaqueLanSearchRecord copyOf(byte[] source, int offset) {
        if (source == null) throw new IllegalArgumentException("source no puede ser null");
        if (offset < 0 || offset > source.length - BYTE_LENGTH) {
            throw new IllegalArgumentException("Registro TUTK truncado");
        }
        return new OpaqueLanSearchRecord(Arrays.copyOfRange(source, offset, offset + BYTE_LENGTH));
    }

    public byte[] toByteArray() {
        return bytes.clone();
    }
}
