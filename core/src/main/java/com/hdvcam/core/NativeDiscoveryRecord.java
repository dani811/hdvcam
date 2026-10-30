package com.hdvcam.core;

/** Parsed fields in native W18 callback 12322, with family-specific binary layout. */
public final class NativeDiscoveryRecord {
    private final String uid;
    private final String address;
    private final int port;
    private final int firmware;

    private NativeDiscoveryRecord(String uid, String address, int port, int firmware) {
        this.uid = uid;
        this.address = address;
        this.port = port;
        this.firmware = firmware;
    }

    public static NativeDiscoveryRecord parse(String firmwareFamily, byte[] payload) {
        if (firmwareFamily == null || payload == null) throw new IllegalArgumentException("Faltan familia o payload.");
        final int uidLength;
        final int addressLength;
        final int portOffset;
        if ("V3".equalsIgnoreCase(firmwareFamily)) {
            uidLength = 64; addressLength = 16; portOffset = 80;
            if (payload.length < 82) throw new IllegalArgumentException("Callback V3 truncado.");
        } else if ("V316".equalsIgnoreCase(firmwareFamily)) {
            uidLength = 21; addressLength = 16; portOffset = 37;
            if (payload.length < 39) throw new IllegalArgumentException("Callback V316 truncado.");
        } else {
            throw new IllegalArgumentException("El callback 12322 no corresponde a " + firmwareFamily + ".");
        }
        String uid = decode(payload, 0, uidLength);
        String address = decode(payload, uidLength, addressLength);
        // The Java callback parser uses p087r1.f.h(), which reads a little-endian short.
        // Native AW packet fields use a separate network-byte-order helper.
        int port = (payload[portOffset] & 0xff) | ((payload[portOffset + 1] & 0xff) << 8);
        int firmware = "V3".equalsIgnoreCase(firmwareFamily) ? 0 : 1;
        if (uid.isEmpty()) throw new IllegalArgumentException("UID vacío en callback 12322.");
        return new NativeDiscoveryRecord(uid, address, port, firmware);
    }

    private static String decode(byte[] bytes, int offset, int length) {
        int end = offset;
        while (end < offset + length && bytes[end] != 0) end++;
        return new String(bytes, offset, end - offset, java.nio.charset.StandardCharsets.UTF_8).trim();
    }

    public String uid() { return uid; }
    public String address() { return address; }
    public int port() { return port; }
    public int firmwareSelector() { return firmware; }
}
