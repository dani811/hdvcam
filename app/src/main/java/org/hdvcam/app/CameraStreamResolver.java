package org.hdvcam.app;

import android.net.Network;

import com.hdvcam.protocol.mstar01.Mstar01Http;
import com.hdvcam.core.VideoStreamProfile;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URI;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Resolves local preview endpoints recovered from the reference app. */
public final class CameraStreamResolver {
    private CameraStreamResolver() { }

    public static String resolve(String family, Network network) throws Exception {
        return resolveStream(family, network).rtspUri;
    }

    public static ResolvedStream resolveStream(String family, Network network) throws Exception {
        String uri;
        String model = "";
        if ("QZ".equalsIgnoreCase(family)) uri = "rtsp://192.168.10.1:8554/ch00";
        else if ("MStar".equalsIgnoreCase(family)) {
            MstarStartup startup = initializeMstar(network);
            uri = startup.rtspUri;
            model = startup.deviceName;
        } else if ("YZ".equalsIgnoreCase(family)) uri = resolveYz(network);
        else throw new IllegalArgumentException("Familia de cámara no reconocida: " + family);
        validateRtsp(uri);
        return new ResolvedStream(uri, model,
                VideoStreamProfile.maxCachedDurationMs(model));
    }

    public static final class ResolvedStream {
        public final String rtspUri;
        public final String deviceName;
        public final int maxCachedDurationMs;

        private ResolvedStream(String rtspUri, String deviceName, int maxCachedDurationMs) {
            this.rtspUri = rtspUri;
            this.deviceName = deviceName;
            this.maxCachedDurationMs = maxCachedDurationMs;
        }
    }

    /** Read-only control-plane probe before opening the camera preview. */
    public static String probeControl(String family, Network network) throws Exception {
        if ("MStar".equalsIgnoreCase(family)) {
            initializeMstar(network);
            return "Control MStar disponible (device_name + configuración RTSP)";
        }
        if ("QZ".equalsIgnoreCase(family)) {
            String status = probeRtsp("rtsp://192.168.10.1:8554/ch00", network, 2000);
            return "RTSP disponible (QZ, " + status + ")";
        }
        if ("YZ".equalsIgnoreCase(family)) {
            YzStartup startup = initializeYz(network);
            return "HTTP control disponible (YZ getproductinfo/getmediainfo; RTSP="
                    + android.net.Uri.parse(startup.rtspUri).getHost() + ")";
        }
        throw new IllegalArgumentException("Familia de cámara no reconocida: " + family);
    }

    /** Identify a supported local camera by its read-only control response. */
    public static String detectFamily(Network network) throws Exception {
        return detectFamily(network, "192.168.1.1");
    }

    static String detectFamily(Network network, String routeAddress) throws Exception {
        java.util.ArrayList<String> failures = new java.util.ArrayList<>();
        for (String family : detectionOrder(routeAddress)) {
            try {
                probeControl(family, network);
                return family;
            } catch (RtspAuthenticationRequiredException authenticationRequired) {
                throw new IllegalStateException("QZ RTSP existe, pero exige autenticación; no se iniciará vídeo sin resolver sus credenciales.", authenticationRequired);
            } catch (Exception error) {
                failures.add(family + "=" + error.getClass().getSimpleName());
            }
        }
        throw new IllegalStateException("No se identificó MStar, QZ o YZ: " + String.join(", ", failures));
    }

    static String familyForRoute(String routeAddress) {
        if ("192.168.1.1".equals(routeAddress)) return "MStar";
        if ("192.168.10.1".equals(routeAddress)) return "QZ";
        if ("192.168.169.1".equals(routeAddress)) return "YZ";
        return null;
    }

    static java.util.List<String> detectionOrder(String routeAddress) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        String first = familyForRoute(routeAddress);
        if (first != null) result.add(first);
        for (String family : new String[]{"MStar", "QZ", "YZ"}) if (!result.contains(family)) result.add(family);
        return result;
    }

    /** Send RTSP OPTIONS to verify control protocol and port before starting playback. */
    public static String probeRtsp(String rtspUri, Network network, int timeoutMs) throws Exception {
        validateRtsp(rtspUri);
        URI uri = URI.create(rtspUri);
        int port = uri.getPort() >= 0 ? uri.getPort() : 554;
        try (Socket socket = network == null ? new Socket() : network.getSocketFactory().createSocket()) {
            socket.connect(new InetSocketAddress(uri.getHost(), port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            OutputStream output = socket.getOutputStream();
            String request = "OPTIONS " + rtspUri + " RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: HDVCam-Lab\r\n\r\n";
            output.write(request.getBytes(StandardCharsets.US_ASCII));
            output.flush();
            InputStream input = socket.getInputStream();
            StringBuilder response = new StringBuilder();
            while (response.length() < 4096) {
                int value = input.read();
                if (value < 0) break;
                response.append((char) value);
                if (response.indexOf("\r\n\r\n") >= 0) break;
            }
            String status = response.toString().split("\\r?\\n", 2)[0];
            if (!isRtspStatusLine(status)) throw new IllegalStateException("La respuesta no es RTSP: " + status);
            if (isRtspAuthenticationRequired(status)) {
                throw new RtspAuthenticationRequiredException(status);
            }
            return status;
        }
    }

    static boolean isRtspStatusLine(String status) {
        return status != null && status.matches("(?i)^RTSP/\\d+(?:\\.\\d+)?\\s+\\d{3}(?:\\s.*)?$");
    }

    static boolean isRtspAuthenticationRequired(String status) {
        return status != null && status.matches("(?i)^RTSP/\\d+(?:\\.\\d+)?\\s+401(?:\\s.*)?$");
    }

    public static final class RtspAuthenticationRequiredException extends java.io.IOException {
        public final String statusLine;
        RtspAuthenticationRequiredException(String statusLine) {
            super("La cámara respondió " + statusLine + "; falta resolver autenticación RTSP.");
            this.statusLine = statusLine;
        }
    }

    /** Read-only preview bootstrap recovered from the MStar family controller. */
    public static MstarStartup initializeMstar(Network network) throws Exception {
        String preview = get(network, Mstar01Http.get("Camera.Preview.*"));
        String device = get(network, Mstar01Http.get("device_name"));
        String cameraId = "";
        String sourceCount = "1";
        String rtspSuffix = "";
        for (String line : preview.split("\\R")) {
            String[] pair = line.split("=", 2);
            if (pair.length != 2) continue;
            String key = pair[0].trim();
            String value = pair[1].trim();
            if (key.equals("Camera.Preview.RTSP.av")) rtspSuffix = value;
            else if (key.equals("Camera.Preview.Source.1.Camid")) cameraId = value;
            else if (key.equals("Camera.Preview.Source.Totals") && !value.isEmpty()) sourceCount = value;
        }
        if (rtspSuffix.isEmpty()) throw new IllegalStateException("La cámara MStar no devolvió Camera.Preview.RTSP.av.");
        String rtsp = buildMstarRtspUri("192.168.1.1", rtspSuffix);
        validateRtsp(rtsp);
        String deviceName = propertyValue(device, "device_name");
        return new MstarStartup(device, deviceName, cameraId, sourceCount, rtsp);
    }

    static String buildMstarRtspUri(String host, String rtspSuffix) {
        if (host == null || host.isEmpty() || rtspSuffix == null || rtspSuffix.isEmpty()) {
            throw new IllegalArgumentException("El host y Camera.Preview.RTSP.av son obligatorios.");
        }
        if (host.contains("://") || host.contains("/") || host.contains("@")) {
            throw new IllegalArgumentException("Host MStar inválido.");
        }
        return "rtsp://" + host + "/liveRTSP/av" + rtspSuffix;
    }

    public static final class MstarStartup {
        public final String deviceNameResponse;
        public final String deviceName;
        public final String cameraId;
        public final String sourceCount;
        public final String rtspUri;

        private MstarStartup(String deviceNameResponse, String deviceName, String cameraId, String sourceCount, String rtspUri) {
            this.deviceNameResponse = deviceNameResponse;
            this.deviceName = deviceName;
            this.cameraId = cameraId;
            this.sourceCount = sourceCount;
            this.rtspUri = rtspUri;
        }
    }

    private static String propertyValue(String response, String property) {
        for (String line : response.split("\\R")) {
            String[] pair = line.split("=", 2);
            if (pair.length == 2 && property.equals(pair[0].trim())) return pair[1].trim();
        }
        return response.trim();
    }

    /** Read-only YZ startup metadata calls made by the original controller before preview. */
    public static YzStartup initializeYz(Network network) throws Exception {
        String productInfo = get(network, "http://192.168.169.1/app/getproductinfo");
        String streamInfo = get(network, "http://192.168.169.1/app/getmediainfo");
        JSONObject root = new JSONObject(streamInfo);
        JSONObject info = root.optJSONObject("info");
        String rtsp = info == null ? "" : info.optString("rtsp", "");
        validateRtsp(rtsp);
        return new YzStartup(productInfo, rtsp);
    }

    public static final class YzStartup {
        public final String productInfoJson;
        public final String rtspUri;

        private YzStartup(String productInfoJson, String rtspUri) {
            this.productInfoJson = productInfoJson;
            this.rtspUri = rtspUri;
        }
    }

    private static String resolveMstar(Network network) throws Exception {
        return initializeMstar(network).rtspUri;
    }

    private static String resolveYz(Network network) throws Exception {
        String uri = initializeYz(network).rtspUri;
        if (uri.isEmpty()) throw new IllegalStateException("getmediainfo no devolvió el campo info.rtsp.");
        return uri;
    }

    private static void validateRtsp(String uri) {
        try {
            URI parsed = URI.create(uri);
            if (!"rtsp".equalsIgnoreCase(parsed.getScheme()) || parsed.getHost() == null) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("La cámara devolvió una dirección RTSP no válida.", invalid);
        }
    }

    private static String get(Network network, String address) throws Exception {
        URL url = new URL(address);
        HttpURLConnection connection = (HttpURLConnection) (network == null ? url.openConnection() : network.openConnection(url));
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestMethod("GET");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) body.append(line).append('\n');
            return body.toString();
        } finally {
            connection.disconnect();
        }
    }

}
