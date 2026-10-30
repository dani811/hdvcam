package com.hdvcam.protocol.aw;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

/** Parses the observed AW LAN search result key (UID plus source IPv4). */
public final class AwDiscoveryResponse {
    public static final int UID_OFFSET = 0x7c;
    public static final int UID_LENGTH = 64;
    public static final int MINIMUM_LENGTH = UID_OFFSET + UID_LENGTH;
    public static final int DISCOVERY_REPLY_TYPE = 0x00000115;

    private final String sourceAddress;
    private final String uid;
    private final byte[] datagram;

    private AwDiscoveryResponse(String sourceAddress, String uid, byte[] datagram) {
        this.sourceAddress = sourceAddress;
        this.uid = uid;
        this.datagram = datagram;
    }

    public static AwDiscoveryResponse parse(byte[] datagram, byte[] sourceAddress) {
        if (datagram == null || sourceAddress == null) throw new IllegalArgumentException("Datagrama o dirección de origen ausente.");
        if (datagram.length < MINIMUM_LENGTH) throw new IllegalArgumentException("Respuesta AW sin el campo UID completo.");
        if (u16(datagram, 0) != 0xABCD) throw new IllegalArgumentException("Magic AW no válido.");
        if (u16(datagram, 2) != 0x0048) throw new IllegalArgumentException("Longitud/tipo de comando AW no reconocido.");
        if (u32(datagram, 4) != DISCOVERY_REPLY_TYPE) throw new IllegalArgumentException("No es una respuesta AW 0x115.");
        final String address;
        try { address = InetAddress.getByAddress(sourceAddress).getHostAddress(); }
        catch (UnknownHostException invalid) { throw new IllegalArgumentException("IP de origen no válida.", invalid); }
        int end = UID_OFFSET;
        while (end < MINIMUM_LENGTH && datagram[end] != 0) end++;
        String uid = new String(datagram, UID_OFFSET, end - UID_OFFSET, StandardCharsets.UTF_8).trim();
        if (uid.isEmpty()) throw new IllegalArgumentException("UID vacío en respuesta AW.");
        return new AwDiscoveryResponse(address, uid, datagram.clone());
    }

    public String sourceAddress() { return sourceAddress; }
    public String uid() { return uid; }
    public int trailingLength() { return datagram.length - MINIMUM_LENGTH; }
    public byte[] datagram() { return datagram.clone(); }

    private static int u16(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    private static long u32(byte[] bytes, int offset) {
        return ((long)(bytes[offset] & 0xff) << 24)
                | ((long)(bytes[offset + 1] & 0xff) << 16)
                | ((long)(bytes[offset + 2] & 0xff) << 8)
                | (long)(bytes[offset + 3] & 0xff);
    }
}
