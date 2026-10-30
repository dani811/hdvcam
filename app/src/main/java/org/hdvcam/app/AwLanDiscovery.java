package org.hdvcam.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.wifi.WifiManager;

import com.hdvcam.protocol.aw.AwDiscoveryRequest;
import com.hdvcam.protocol.aw.AwDiscoveryResponse;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Active LAN discovery matching the observed AW broadcast envelope. */
public final class AwLanDiscovery {
    private static final int RECEIVE_WINDOW_MS = 2200;
    private static final int MAX_PACKET_BYTES = 2048;

    private AwLanDiscovery() { }

    public static List<AwDiscoveryResponse> search(Context context, Network network) throws Exception {
        LinkedHashMap<String, AwDiscoveryResponse> devices = new LinkedHashMap<>();
        byte[] query = AwDiscoveryRequest.create();
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock multicastLock = wifi == null ? null : wifi.createMulticastLock("hdvcam-aw-discovery");
        try {
            if (multicastLock != null) multicastLock.acquire();
            try (DatagramSocket socket = new DatagramSocket(null)) {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(AwDiscoveryRequest.UDP_LISTEN_PORT));
                // Network.bindSocket requires an already-bound socket on Android.
                // Bind the listener port first, then pin both its receive path and
                // subsequent broadcast send to the selected camera Wi-Fi.
                if (network != null) network.bindSocket(socket);
                socket.setBroadcast(true);
                socket.setSoTimeout(250);

                InetAddress broadcast = networkBroadcast(context, network);
                socket.send(new DatagramPacket(query, query.length,
                        broadcast, AwDiscoveryRequest.UDP_DESTINATION_PORT));

                long deadline = android.os.SystemClock.elapsedRealtime() + RECEIVE_WINDOW_MS;
                byte[] buffer = new byte[MAX_PACKET_BYTES];
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    try {
                        socket.receive(packet);
                        byte[] payload = new byte[packet.getLength()];
                        System.arraycopy(packet.getData(), packet.getOffset(), payload, 0, packet.getLength());
                        try {
                            AwDiscoveryResponse response = AwDiscoveryResponse.parse(payload,
                                    packet.getAddress().getAddress());
                            devices.putIfAbsent(response.uid(), response);
                        } catch (IllegalArgumentException ignored) {
                            // Other local UDP traffic is expected; only observed AW replies qualify.
                        }
                    } catch (SocketTimeoutException ignored) { }
                }
            }
        } finally {
            if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        }
        return new ArrayList<>(devices.values());
    }

    private static InetAddress networkBroadcast(Context context, Network network) throws Exception {
        if (network == null) return InetAddress.getByName("255.255.255.255");
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        LinkProperties properties = manager == null ? null : manager.getLinkProperties(network);
        if (properties != null) for (LinkAddress address : properties.getLinkAddresses()) {
            if (address.getAddress() instanceof java.net.Inet4Address) {
                InetAddress broadcast = ipv4Broadcast((java.net.Inet4Address) address.getAddress(), address.getPrefixLength());
                if (broadcast != null) return broadcast;
            }
        }
        // Keep discovery usable on unusual Wi-Fi stacks that omit prefix metadata.
        return InetAddress.getByName("255.255.255.255");
    }

    private static InetAddress ipv4Broadcast(java.net.Inet4Address address, int prefixLength) throws Exception {
        if (prefixLength < 0 || prefixLength > 32) return null;
        byte[] ip = address.getAddress();
        int value = ((ip[0] & 0xff) << 24) | ((ip[1] & 0xff) << 16)
                | ((ip[2] & 0xff) << 8) | (ip[3] & 0xff);
        int mask = prefixLength == 0 ? 0 : (int) (0xffffffffL << (32 - prefixLength));
        int broadcast = value | ~mask;
        return InetAddress.getByAddress(new byte[]{(byte) (broadcast >>> 24), (byte) (broadcast >>> 16),
                (byte) (broadcast >>> 8), (byte) broadcast});
    }
}
