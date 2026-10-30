package org.hdvcam.app;

import android.net.Network;

import com.hdvcam.protocol.mstar01.Mstar01Http;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/** Clean-room recorder commands recovered from each local family controller. */
public final class CameraCommandDispatcher {
    private CameraCommandDispatcher() { }

    public static int setRecording(String family, Network network, boolean start) throws IOException {
        String address;
        String method = "GET";
        if ("MStar".equalsIgnoreCase(family)) {
            address = Mstar01Http.set("Video", start ? "recordon" : "recordoff");
        } else if ("QZ".equalsIgnoreCase(family)) {
            address = "http://192.168.10.1:8082/api/setdeviceinfo/?custom=1&cmd=1100&par=" + (start ? "1" : "0");
            method = "POST";
        } else if ("YZ".equalsIgnoreCase(family)) {
            address = "http://192.168.169.1/app/setparamvalue?param=rec&value=" + (start ? "1" : "0");
        } else {
            throw new IllegalArgumentException("Familia de cámara no reconocida: " + family);
        }

        URL url = new URL(address);
        HttpURLConnection connection = (HttpURLConnection) (network == null ? url.openConnection() : network.openConnection(url));
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestMethod(method);
        if ("POST".equals(method)) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Length", "0");
            connection.getOutputStream().close();
        }
        try {
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    /** QZ pan/tilt command recovered from the original direction joystick callback. */
    public static int movePtz(String family, Network network, int direction) throws IOException {
        if (!"QZ".equalsIgnoreCase(family)) {
            throw new UnsupportedOperationException("El comando PTZ 4005 se ha recuperado para la familia QZ.");
        }
        URL url = new URL(qzPtzUrl(direction));
        HttpURLConnection connection = (HttpURLConnection) (network == null ? url.openConnection() : network.openConnection(url));
        connection.setConnectTimeout(1000);
        connection.setReadTimeout(1000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Length", "0");
        connection.getOutputStream().close();
        try { return connection.getResponseCode(); }
        finally { connection.disconnect(); }
    }

    static String qzPtzUrl(int direction) {
        if (direction < 0 || direction > 5) throw new IllegalArgumentException("Dirección PTZ fuera de rango.");
        return "http://192.168.10.1:8082/api/setdeviceinfo/?custom=1&cmd=4005&par=" + direction;
    }

    /** Fire the static photo command recovered from the MStar01 controller. */
    public static int capturePhoto(String family, Network network) throws Exception {
        if ("MStar".equalsIgnoreCase(family)) {
            URL url = new URL(Mstar01Http.set("Video", "capture"));
            HttpURLConnection connection = (HttpURLConnection) (network == null ? url.openConnection() : network.openConnection(url));
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestMethod("GET");
            try { return connection.getResponseCode(); }
            finally { connection.disconnect(); }
        }
        if ("QZ".equalsIgnoreCase(family)) {
            // QZ's controller sends this JSON over its already-open control channel.
            // The local replica can only target the observed camera control port if a listener is present;
            // don't guess a UDP port here.
            throw new UnsupportedOperationException("QZ: se conoce el payload cmd_id=4016, pero falta confirmar el transporte/puerto de control.");
        }
        if ("YZ".equalsIgnoreCase(family)) {
            throw new UnsupportedOperationException("YZ: el comando de captura no está recuperado; solo consta la consulta de capability.");
        }
        throw new IllegalArgumentException("Familia de cámara no reconocida: " + family);
    }
}
