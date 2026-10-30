package org.hdvcam.app;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/** Requests a local camera Wi-Fi network and keeps its route/socket context alive. */
public final class CameraWifiConnector {
    public interface Listener {
        void connected(Network network, String gateway);
        void failed(String reason);
        default void lost() { }
    }

    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback callback;
    private Network activeNetwork;

    public void connect(Activity activity, String ssid, String password, Listener listener) {
        if (Build.VERSION.SDK_INT < 29) {
            listener.failed("Android 10 o posterior es necesario para conectar a la cámara desde la app.");
            return;
        }
        // Android permits one active WifiNetworkSpecifier request per app. Drop
        // an earlier request before registering another to make retries reliable.
        release();
        try {
            connectivity = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
            WifiNetworkSpecifier.Builder specifier = new WifiNetworkSpecifier.Builder()
                    .setSsid(ssid);
            if (password != null && !password.isEmpty()) specifier.setWpa2Passphrase(password);
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                    .setNetworkSpecifier(specifier.build())
                    .build();
            callback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    activeNetwork = network;
                    LinkProperties properties = connectivity.getLinkProperties(network);
                    String gateway = gateway(properties);
                    new Handler(Looper.getMainLooper()).post(() -> listener.connected(network, gateway));
                }

                @Override public void onUnavailable() {
                    release();
                    new Handler(Looper.getMainLooper()).post(() -> listener.failed("No se pudo conectar a esa Wi-Fi. Comprueba la clave y acepta el aviso de Android."));
                }

                @Override public void onLost(Network network) {
                    if (network.equals(activeNetwork)) {
                        activeNetwork = null;
                        new Handler(Looper.getMainLooper()).post(listener::lost);
                    }
                }
            };
            connectivity.requestNetwork(request, callback);
        } catch (SecurityException | IllegalArgumentException e) {
            release();
            listener.failed("Android ha rechazado la solicitud de conexión: " + e.getMessage());
        }
    }

    public void release() {
        if (connectivity == null) return;
        // Preview HTTP/RTSP calls use the returned Network explicitly, so the app
        // does not need a process-wide network binding.
        if (callback != null) {
            try { connectivity.unregisterNetworkCallback(callback); } catch (RuntimeException ignored) { }
        }
        activeNetwork = null;
        callback = null;
        connectivity = null;
    }

    static String gateway(LinkProperties properties) {
        if (properties == null) return "";
        for (RouteInfo route : properties.getRoutes()) {
            if (route.isDefaultRoute() && route.getGateway() != null) {
                return route.getGateway().getHostAddress();
            }
        }
        return "";
    }
}
