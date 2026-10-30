package org.hdvcam.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.DhcpInfo;
import android.net.Network;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.text.InputType;
import android.text.TextUtils;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import com.hdvcam.core.LocalCameraCase;
import com.hdvcam.core.CameraMediaFile;
import com.hdvcam.core.DeviceFamily;
import com.hdvcam.protocol.aw.AwDiscoveryResponse;

import java.util.ArrayList;
import java.util.List;

/** Product-facing replica shell based on the original three-tab navigation. */
public final class MainActivity extends Activity {
    private static final int NAV_DEVICE = 0, NAV_FILES = 1, NAV_MORE = 2;
    private static final int REQUEST_OPEN_LOCAL_MEDIA = 7401;
    private static final int INK = 0xFF010B3A, BG = 0xFFF6F6F6, LINE = 0xFFCED5DC;
    private FrameLayout pageHost;
    private LinearLayout[] navItems;
    private int selectedTab = NAV_DEVICE;
    private boolean waitingForCameraWifi;
    private boolean cameraWifiRequestActive;
    private boolean cameraPreviewOpen;
    private final CameraWifiConnector wifiConnector = new CameraWifiConnector();
    private String pendingSsid;
    private String pendingPassword;
    private AwTcpSession activeAwSession;
    private static AwTcpSession pendingAwSession;
    private final CameraMediaRepository cameraMediaRepository = new CameraMediaRepository();
    private final java.util.concurrent.ExecutorService thumbnailExecutor = java.util.concurrent.Executors.newFixedThreadPool(3);
    private Network activeCameraNetwork;
    private DeviceFamily activeMediaFamily;
    private java.io.File activeMediaDatabase;
    private int cameraMediaOffset;
    private int cameraMediaCount;
    private TextView cameraMediaStatus;
    private LinearLayout cameraMediaRows;
    private Button cameraMediaMore;

    static synchronized AwTcpSession consumeAwSession() {
        AwTcpSession session = pendingAwSession;
        pendingAwSession = null;
        return session;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        renderShell();
        showTab(selectedTab);
    }

    @Override protected void onResume() {
        super.onResume();
        if (waitingForCameraWifi) {
            LocalCameraCase cameraCase = currentRouteCase();
            if (cameraCase != null) {
                waitingForCameraWifi = false;
                detectAndOpenCamera(cameraNetwork(cameraCase), cameraCase.routeAddress());
            } else {
                ArrayList<Network> candidates = wifiNetworks();
                waitingForCameraWifi = false;
                if (!candidates.isEmpty()) discoverAmongNetworks(candidates, 0, false, true);
                else Toast.makeText(this, "No se encontró una red Wi-Fi de cámara activa.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void renderShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        pageHost = new FrameLayout(this);
        root.addView(pageHost, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout nav = new LinearLayout(this);
        nav.setBackgroundColor(Color.WHITE);
        nav.setElevation(dp(8));
        navItems = new LinearLayout[3];
        String[] titles = {getString(R.string.tab_device), getString(R.string.tab_files), getString(R.string.tab_more)};
        String[] icons = {"ma_dv_select", "ma_alb_select", "ma_mo_select"};
        for (int i = 0; i < 3; i++) {
            final int tab = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, dp(5), 0, dp(7));
            ImageView icon = new ImageView(this);
            icon.setTag(icons[i]);
            icon.setImageResource(mip(icons[i] + "_un"));
            item.addView(icon, new LinearLayout.LayoutParams(dp(25), dp(25)));
            TextView label = text(titles[i], 11, INK);
            label.setGravity(Gravity.CENTER);
            item.addView(label);
            item.setOnClickListener(v -> showTab(tab));
            navItems[i] = item;
            nav.addView(item, new LinearLayout.LayoutParams(0, dp(58), 1));
        }
        root.addView(nav);
        setContentView(root);
    }

    private void showTab(int tab) {
        selectedTab = tab;
        for (int i = 0; i < navItems.length; i++) {
            ImageView icon = (ImageView) navItems[i].getChildAt(0);
            String base = (String) icon.getTag();
            icon.setImageResource(mip(base + "_un"));
            icon.setColorFilter(i == tab ? INK : 0xFF9FA4A8);
            ((TextView) navItems[i].getChildAt(1)).setTextColor(i == tab ? INK : 0xFF9FA4A8);
        }
        pageHost.removeAllViews();
        if (tab == NAV_DEVICE) pageHost.addView(devicePage());
        else if (tab == NAV_FILES) pageHost.addView(filesPage());
        else pageHost.addView(morePage());
    }

    private View devicePage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);
        page.addView(toolbar(getString(R.string.tab_device)));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(Color.WHITE, dp(14), 0, 0));
        card.setClipToOutline(true);
        card.setElevation(dp(2));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, dp(402));
        cp.setMargins(dp(20), dp(20), dp(20), 0);
        page.addView(card, cp);
        ImageView camera = new ImageView(this);
        camera.setImageResource(mip("img_jingtou2"));
        camera.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(camera, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout choices = new LinearLayout(this);
        choices.setGravity(Gravity.CENTER);
        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        choices.addView(connectChoice("icon_scan_con", getString(R.string.scan_connect), this::scanFlow),
                new LinearLayout.LayoutParams(0, dp(50), 1));
        choices.addView(divider, new LinearLayout.LayoutParams(dp(1), dp(28)));
        choices.addView(connectChoice("icon_manual_con", getString(R.string.manual_connect), this::manualFlow),
                new LinearLayout.LayoutParams(0, dp(50), 1));
        card.addView(choices);

        Button enter = new Button(this);
        enter.setText(getString(R.string.enter_preview));
        enter.setTextColor(Color.WHITE);
        enter.setTextSize(16);
        enter.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        enter.setAllCaps(false);
        enter.setBackground(rounded(0xFF222222, dp(25), 0, 0));
        enter.setOnClickListener(v -> beginPreview());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(50));
        bp.setMargins(dp(50), dp(82), dp(50), 0);
        page.addView(enter, bp);
        return page;
    }

    private View connectChoice(String iconName, String title, Runnable action) {
        LinearLayout choice = new LinearLayout(this);
        choice.setGravity(Gravity.CENTER);
        choice.setOrientation(LinearLayout.HORIZONTAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(mip(iconName));
        choice.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView label = text(title, 13, INK);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(5);
        choice.addView(label, lp);
        choice.setOnClickListener(v -> action.run());
        return choice;
    }

    private void scanFlow() {
        GmsBarcodeScannerOptions options = new GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build();
        GmsBarcodeScanning.getClient(this, options).startScan()
                .addOnSuccessListener(barcode -> {
                    CameraWifiQrParser.Connection connection = CameraWifiQrParser.parse(barcode.getRawValue());
                    if (connection == null || TextUtils.isEmpty(connection.ssid)) {
                        Toast.makeText(this, "El QR no contiene el nombre Wi-Fi de la cámara.", Toast.LENGTH_LONG).show();
                        manualFlow();
                        return;
                    }
                    connectToCamera(connection.ssid, connection.password);
                })
                .addOnFailureListener(error -> {
                    Toast.makeText(this, "No se pudo abrir el lector QR; introduce los datos Wi-Fi manualmente.", Toast.LENGTH_LONG).show();
                    manualFlow();
                });
    }

    private void manualFlow() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        EditText ssid = new EditText(this);
        ssid.setSingleLine(true);
        ssid.setHint("Nombre Wi-Fi de la cámara (SSID)");
        form.addView(ssid);
        EditText password = new EditText(this);
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setHint("Contraseña Wi-Fi");
        form.addView(password);
        new AlertDialog.Builder(this).setTitle(getString(R.string.manual_connect)).setView(form)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Conectar", (dialog, which) -> connectToCamera(ssid.getText().toString().trim(), password.getText().toString()))
                .show();
    }

    private void connectToCamera(String ssid, String password) {
        if (TextUtils.isEmpty(ssid)) {
            Toast.makeText(this, "Indica el nombre Wi-Fi de la cámara.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (android.os.Build.VERSION.SDK_INT < 29) {
            waitingForCameraWifi = true;
            Toast.makeText(this, "Selecciona la red de la cámara en los ajustes Wi-Fi y vuelve a HDVCam.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.NEARBY_WIFI_DEVICES) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingSsid = ssid;
            pendingPassword = password;
            requestPermissions(new String[]{android.Manifest.permission.NEARBY_WIFI_DEVICES}, 7301);
            return;
        }
        pendingSsid = null;
        pendingPassword = null;
        Toast.makeText(this, "Solicitando conexión Wi-Fi… acepta el diálogo de Android.", Toast.LENGTH_LONG).show();
        cameraWifiRequestActive = true;
        wifiConnector.connect(this, ssid, password, new CameraWifiConnector.Listener() {
            @Override public void connected(Network network, String gateway) {
                discoveredRouteNetwork = network;
                detectAndOpenCamera(network, gateway);
            }
            @Override public void failed(String reason) {
                cameraWifiRequestActive = false;
                Toast.makeText(MainActivity.this, reason, Toast.LENGTH_LONG).show();
            }
            @Override public void lost() {
                cameraWifiRequestActive = false;
                cameraPreviewOpen = false;
                Toast.makeText(MainActivity.this, "Se perdió la Wi-Fi de la cámara. Vuelve a conectar para recuperar el vídeo.", Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != 7301) return;
        String ssid = pendingSsid;
        String password = pendingPassword;
        pendingSsid = null;
        pendingPassword = null;
        if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            connectToCamera(ssid, password);
            return;
        }
        cameraWifiRequestActive = false;
        String message = shouldShowRequestPermissionRationale(android.Manifest.permission.NEARBY_WIFI_DEVICES)
                ? "Permite Dispositivos cercanos para que Android pueda solicitar la Wi-Fi de la cámara. Vuelve a pulsar Conectar para intentarlo otra vez."
                : "No se concedió Dispositivos cercanos. Actívalo en Ajustes de la aplicación para conectar la Wi-Fi de la cámara.";
        new AlertDialog.Builder(this).setTitle("Permiso necesario").setMessage(message)
                .setPositiveButton("Entendido", null).show();
    }

    private void beginPreview() {
        LocalCameraCase cameraCase = currentRouteCase();
        if (cameraCase != null) {
            detectAndOpenCamera(cameraNetwork(cameraCase), cameraCase.routeAddress());
        } else {
            ArrayList<Network> candidates = wifiNetworks();
            if (!candidates.isEmpty()) {
                discoverAmongNetworks(candidates, 0, true, true);
            } else {
                waitingForCameraWifi = true;
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            }
        }
    }

    private ArrayList<Network> wifiNetworks() {
        ArrayList<Network> result = new ArrayList<>();
        ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (manager == null) return result;
        try {
            for (Network candidate : manager.getAllNetworks()) {
                NetworkCapabilities caps = manager.getNetworkCapabilities(candidate);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) result.add(candidate);
            }
        } catch (SecurityException ignored) { }
        return result;
    }

    private void discoverAmongNetworks(ArrayList<Network> candidates, int index,
                                       boolean openSettingsIfMissing, boolean includeAw) {
        if (index >= candidates.size()) {
            Toast.makeText(this, "No se encontró una cámara compatible en las redes Wi-Fi activas.", Toast.LENGTH_LONG).show();
            if (openSettingsIfMissing) {
                waitingForCameraWifi = true;
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            }
            return;
        }
        Network network = candidates.get(index);
        ConnectivityManager connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        String gateway = CameraWifiConnector.gateway(connectivity == null ? null : connectivity.getLinkProperties(network));
        new Thread(() -> {
            List<AwDiscoveryResponse> awDevices = new ArrayList<>();
            Exception awFailure = null;
            if (includeAw) {
                try { awDevices = AwLanDiscovery.search(this, network); }
                catch (Exception error) {
                    awFailure = error;
                    android.util.Log.w("HDVCamDiscovery", "AW LAN discovery unavailable: " + error.getClass().getSimpleName(), error);
                }
            }
            final Exception awDiscoveryError = awFailure;
            String family = null;
            if (awDevices.isEmpty()) {
                try { family = CameraStreamResolver.detectFamily(network, gateway); }
                catch (Exception error) {
                    android.util.Log.i("HDVCamDiscovery", "No se clasificó Wi-Fi gateway=" + gateway
                            + "; reason=" + error.getMessage());
                }
            }
            final String detected = family;
            final List<AwDiscoveryResponse> awFound = awDevices;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                // A validated AW 0x115 reply is stronger camera evidence than a
                // generic HTTP response from a captive portal or another device.
                if (!awFound.isEmpty()) {
                    discoveredRouteNetwork = network;
                    showAwDevices(awFound);
                } else if (detected == null) discoverAmongNetworks(candidates, index + 1, openSettingsIfMissing, includeAw);
                else {
                    discoveredRouteNetwork = network;
                    openCamera(detected, network);
                }
            });
        }, "hdvcam-network-discovery").start();
    }

    private LocalCameraCase currentRouteCase() {
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wifi == null || !wifi.isWifiEnabled()) return null;
            DhcpInfo dhcp = wifi.getDhcpInfo();
            int address = dhcp.gateway;
            if (address != 0) {
                String route = (address & 255) + "." + ((address >>> 8) & 255) + "." +
                        ((address >>> 16) & 255) + "." + ((address >>> 24) & 255);
                LocalCameraCase result = LocalCameraCase.fromRouteAddress(route).orElse(null);
                if (result != null) return result;
            }
        } catch (SecurityException ignored) {
        }
        ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (manager == null) return null;
        try {
            for (Network network : manager.getAllNetworks()) {
                NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
                if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                LinkProperties properties = manager.getLinkProperties(network);
                if (properties == null) continue;
                for (RouteInfo route : properties.getRoutes()) {
                    if (!route.isDefaultRoute() || route.getGateway() == null) continue;
                    LocalCameraCase found = LocalCameraCase.fromRouteAddress(route.getGateway().getHostAddress()).orElse(null);
                    if (found != null) {
                        discoveredRouteNetwork = network;
                        return found;
                    }
                }
            }
        } catch (SecurityException ignored) { }
        return null;
    }

    private void detectAndOpenCamera(Network network, String gateway) {
        Toast.makeText(this, "Buscando la cámara en la red Wi-Fi…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            List<AwDiscoveryResponse> awDevices = new ArrayList<>();
            Exception awFailure = null;
            try { awDevices = AwLanDiscovery.search(this, network); }
            catch (Exception error) {
                awFailure = error;
                android.util.Log.w("HDVCamDiscovery", "AW LAN discovery unavailable: " + error.getClass().getSimpleName(), error);
            }
            final Exception awDiscoveryError = awFailure;
            String family = null;
            Exception failure = null;
            if (awDevices.isEmpty()) {
                try { family = CameraStreamResolver.detectFamily(network, gateway); }
                catch (Exception error) { failure = error; }
            }
            final String detected = family;
            final Exception lastError = failure;
            final List<AwDiscoveryResponse> awFound = awDevices;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (!awFound.isEmpty()) {
                    cameraWifiRequestActive = false;
                    showAwDevices(awFound);
                    return;
                }
                if (detected == null) {
                    cameraWifiRequestActive = false;
                    if (lastError != null && lastError.getCause() instanceof CameraStreamResolver.RtspAuthenticationRequiredException) {
                        Toast.makeText(this, "Cámara QZ encontrada, pero RTSP exige autenticación: "
                                + lastError.getCause().getMessage(), Toast.LENGTH_LONG).show();
                        wifiConnector.release();
                        return;
                    }
                    String details = lastError == null ? "" : " (sonda: " + lastError.getMessage() + ")";
                    String awDetails = awDiscoveryError == null ? "" : " (AW: " + awDiscoveryError.getClass().getSimpleName()
                            + ": " + awDiscoveryError.getMessage() + ")";
                    Toast.makeText(this, "Wi-Fi conectada, pero no respondió una cámara MStar/QZ/YZ en esa red" + details + awDetails, Toast.LENGTH_LONG).show();
                    wifiConnector.release();
                    return;
                }
                openCamera(detected, network);
            });
        }, "hdvcam-family-detection").start();
    }

    private void showAwDevices(List<AwDiscoveryResponse> cameras) {
        String[] rows = new String[cameras.size()];
        for (int i = 0; i < cameras.size(); i++) {
            AwDiscoveryResponse camera = cameras.get(i);
            rows[i] = camera.uid() + "    " + camera.sourceAddress();
        }
        new AlertDialog.Builder(this)
                .setTitle("Dispositivos AW encontrados")
                .setItems(rows, (dialog, which) -> new AlertDialog.Builder(this)
                        .setTitle("Conectar con el dispositivo")
                        .setMessage(rows[which] + "\n\nSe repetirá la búsqueda AW y se intentará la negociación TCP observada en el APK.")
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton("Conectar", (d, selected) -> connectAwDevice(cameras.get(which)))
                        .show())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void connectAwDevice(AwDiscoveryResponse device) {
        Network network = discoveredRouteNetwork;
        Toast.makeText(this, "Verificando UID y negociando sesión AW…", Toast.LENGTH_LONG).show();
        new Thread(() -> {
            AwTcpSession session = null;
            Exception failure = null;
            try { session = AwTcpSession.connect(getApplicationContext(), network, device.uid()); }
            catch (Exception error) { failure = error; }
            final AwTcpSession connected = session;
            final Exception connectionError = failure;
            runOnUiThread(() -> {
                if (isFinishing()) {
                    if (connected != null) try { connected.close(); } catch (java.io.IOException ignored) { }
                    return;
                }
                if (connected == null) {
                    String detail = connectionError == null ? "" : ": " + connectionError.getMessage();
                    Toast.makeText(this, "No se pudo negociar la sesión AW" + detail, Toast.LENGTH_LONG).show();
                    return;
                }
                if (activeAwSession != null) try { activeAwSession.close(); } catch (java.io.IOException ignored) { }
                activeAwSession = connected;
                activeCameraNetwork = discoveredRouteNetwork;
                activeMediaFamily = DeviceFamily.V3;
                if (connectionError != null) android.util.Log.w("HDVCamAW", "Fallo iniciando vídeo AW", connectionError);
                synchronized (MainActivity.class) { pendingAwSession = connected; }
                Intent preview = new Intent(MainActivity.this, CameraActivity.class);
                preview.putExtra("family", "AW");
                preview.putExtra("aw_mode", true);
                preview.putExtra("camera_network", discoveredRouteNetwork);
                cameraPreviewOpen = true;
                cameraWifiRequestActive = false;
                startActivity(preview);
            });
        }, "hdvcam-aw-connect").start();
    }

    private Network discoveredRouteNetwork;

    private Network cameraNetwork(LocalCameraCase cameraCase) {
        Network network = discoveredRouteNetwork;
        if (network != null) return network;
        ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (manager == null) return null;
        try {
            for (Network candidate : manager.getAllNetworks()) {
                NetworkCapabilities capabilities = manager.getNetworkCapabilities(candidate);
                if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                LinkProperties properties = manager.getLinkProperties(candidate);
                if (properties == null) continue;
                for (RouteInfo route : properties.getRoutes()) {
                    if (route.isDefaultRoute() && route.getGateway() != null
                            && cameraCase.routeAddress().equals(route.getGateway().getHostAddress())) return candidate;
                }
            }
        } catch (SecurityException ignored) { }
        return null;
    }

    private View filesPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);
        page.addView(toolbar(getString(R.string.tab_files)));
        LinearLayout remote = new LinearLayout(this);
        remote.setOrientation(LinearLayout.VERTICAL);
        remote.setPadding(dp(16), dp(8), dp(16), dp(8));
        TextView remoteTitle = text("Tarjeta de la cámara", 16, INK);
        remote.addView(remoteTitle);
        Button cameraRefresh = new Button(this);
        cameraRefresh.setText("Actualizar álbum de cámara");
        cameraRefresh.setEnabled(activeMediaFamily != null && activeCameraNetwork != null);
        cameraRefresh.setOnClickListener(v -> refreshCameraMedia());
        remote.addView(cameraRefresh, new LinearLayout.LayoutParams(-1, dp(48)));
        cameraMediaStatus = text(activeMediaFamily == null
                ? "Conecta una cámara compatible para consultar su tarjeta SD."
                : activeCameraNetwork == null ? "Red de cámara no disponible; vuelve a conectar." : "Pulsa para actualizar el índice remoto.", 13, 0xFF72777D);
        cameraMediaStatus.setPadding(0, dp(4), 0, dp(4));
        remote.addView(cameraMediaStatus);
        cameraMediaRows = new LinearLayout(this);
        cameraMediaRows.setOrientation(LinearLayout.VERTICAL);
        ScrollView cameraMediaScroll = new ScrollView(this);
        cameraMediaScroll.addView(cameraMediaRows);
        remote.addView(cameraMediaScroll, new LinearLayout.LayoutParams(-1, dp(220)));
        cameraMediaMore = new Button(this);
        cameraMediaMore.setText("Cargar más");
        cameraMediaMore.setVisibility(View.GONE);
        cameraMediaMore.setOnClickListener(v -> loadMoreCameraMedia());
        remote.addView(cameraMediaMore);
        page.addView(remote);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(dp(16), dp(10), dp(16), dp(10));
        Button refresh = new Button(this);
        refresh.setText(getString(R.string.refresh_local_files));
        refresh.setOnClickListener(v -> showLocalMediaInventory());
        actions.addView(refresh, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button open = new Button(this);
        open.setText(getString(R.string.open_local_media));
        open.setOnClickListener(v -> openLocalMediaPicker());
        actions.addView(open, new LinearLayout.LayoutParams(0, dp(48), 1));
        page.addView(actions);
        TextView inventory = text(getString(R.string.local_media_initial), 14, 0xFF72777D);
        inventory.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        inventory.setPadding(dp(20), dp(20), dp(20), dp(20));
        page.addView(inventory, new LinearLayout.LayoutParams(-1, 0, 1));
        inventory.setTag("local-media-inventory");
        loadLocalMediaInventory(inventory);
        return page;
    }

    private void refreshCameraMedia() {
        if (activeMediaFamily == null || activeCameraNetwork == null) {
            Toast.makeText(this, "La familia y la red de cámara deben estar identificadas.", Toast.LENGTH_LONG).show();
            return;
        }
        if (cameraMediaStatus == null || cameraMediaRows == null) return;
        cameraMediaStatus.setText("Descargando índice SQLite de la cámara…");
        cameraMediaRows.removeAllViews();
        cameraMediaMore.setVisibility(View.GONE);
        new Thread(() -> {
            java.io.File database = null;
            int count = 0;
            List<CameraMediaFile> firstPage = new ArrayList<>();
            Exception failure = null;
            try {
                database = cameraMediaRepository.refresh(activeCameraNetwork,
                        new java.io.File(getCacheDir(), "camera-media"), activeMediaFamily, null);
                count = cameraMediaRepository.count(database, activeMediaFamily);
                firstPage = cameraMediaRepository.page(database, activeMediaFamily, 0, 50);
            } catch (Exception error) { failure = error; }
            final java.io.File resultDb = database;
            final int resultCount = count;
            final List<CameraMediaFile> resultPage = firstPage;
            final Exception resultFailure = failure;
            runOnUiThread(() -> {
                if (isFinishing() || cameraMediaStatus == null || cameraMediaStatus.getWindowToken() == null) return;
                if (resultFailure != null) {
                    cameraMediaStatus.setText("No se pudo leer el álbum de cámara: " + resultFailure.getMessage());
                    return;
                }
                activeMediaDatabase = resultDb;
                cameraMediaCount = resultCount;
                cameraMediaOffset = resultPage.size();
                cameraMediaStatus.setText(resultCount + " archivos en la tarjeta SD · mostrando " + resultPage.size());
                appendCameraMediaRows(resultPage);
                cameraMediaMore.setVisibility(cameraMediaOffset < cameraMediaCount ? View.VISIBLE : View.GONE);
            });
        }, "hdvcam-camera-media-refresh").start();
    }

    private void loadMoreCameraMedia() {
        if (activeMediaDatabase == null || activeMediaFamily == null || cameraMediaRows == null) return;
        cameraMediaMore.setEnabled(false);
        new Thread(() -> {
            List<CameraMediaFile> rows = new ArrayList<>();
            Exception failure = null;
            try { rows = cameraMediaRepository.page(activeMediaDatabase, activeMediaFamily, cameraMediaOffset, 50); }
            catch (Exception error) { failure = error; }
            final List<CameraMediaFile> page = rows;
            final Exception error = failure;
            runOnUiThread(() -> {
                if (isFinishing() || cameraMediaRows == null || cameraMediaRows.getWindowToken() == null) return;
                cameraMediaMore.setEnabled(true);
                if (error != null) {
                    cameraMediaStatus.setText("Falló la paginación del álbum: " + error.getMessage());
                    return;
                }
                cameraMediaOffset += page.size();
                cameraMediaStatus.setText(cameraMediaCount + " archivos en la tarjeta SD · mostrando " + cameraMediaOffset);
                appendCameraMediaRows(page);
                cameraMediaMore.setVisibility(cameraMediaOffset < cameraMediaCount ? View.VISIBLE : View.GONE);
            });
        }, "hdvcam-camera-media-page").start();
    }

    private void appendCameraMediaRows(List<CameraMediaFile> files) {
        for (CameraMediaFile file : files) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(8), dp(4), dp(8), dp(4));
            ImageView preview = new ImageView(this);
            preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
            preview.setImageResource(CameraMediaFile.VIDEO.equalsIgnoreCase(file.type())
                    ? android.R.drawable.ic_media_play : android.R.drawable.ic_menu_gallery);
            item.addView(preview, new LinearLayout.LayoutParams(dp(40), dp(40)));
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setGravity(Gravity.CENTER_VERTICAL);
            labels.setPadding(dp(10), 0, 0, 0);
            String typeLabel = CameraMediaFile.VIDEO.equalsIgnoreCase(file.type()) ? "Vídeo"
                    : CameraMediaFile.PHOTO.equalsIgnoreCase(file.type()) ? "Foto" : file.type();
            TextView title = text(file.name(), 14, INK);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            labels.addView(title, new LinearLayout.LayoutParams(-1, dp(22)));
            String metadata = typeLabel == null ? "" : typeLabel;
            if (file.sizeBytes() != null) {
                String size = android.text.format.Formatter.formatFileSize(this, file.sizeBytes());
                metadata = metadata.isEmpty() ? size : metadata + " · " + size;
            }
            if (!metadata.isEmpty()) {
                TextView details = text(metadata, 11, 0xFF72777D);
                labels.addView(details, new LinearLayout.LayoutParams(-1, dp(17)));
            }
            item.addView(labels, new LinearLayout.LayoutParams(0, -1, 1));
            cameraMediaRows.addView(item, new LinearLayout.LayoutParams(-1, dp(48)));
            String thumbnailUrl = file.thumbnailUrl();
            Network thumbnailNetwork = activeCameraNetwork;
            if (thumbnailUrl != null && thumbnailNetwork != null) {
                thumbnailExecutor.execute(() -> {
                    Bitmap thumbnail = null;
                    try { thumbnail = cameraMediaRepository.loadThumbnail(thumbnailNetwork, thumbnailUrl); }
                    catch (Exception error) { android.util.Log.d("HDVCamGallery", "Thumbnail no disponible: " + error.getClass().getSimpleName()); }
                    Bitmap result = thumbnail;
                    if (result != null) runOnUiThread(() -> {
                        if (preview.getWindowToken() != null && !isFinishing()) preview.setImageBitmap(result);
                    });
                });
            }
            View divider = new View(this);
            divider.setBackgroundColor(LINE);
            cameraMediaRows.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        }
    }

    private void showLocalMediaInventory() {
        View inventory = pageHost.findViewWithTag("local-media-inventory");
        if (inventory instanceof TextView) loadLocalMediaInventory((TextView) inventory);
    }

    private void loadLocalMediaInventory(TextView target) {
        target.setText(getString(R.string.loading_local_files));
        new Thread(() -> {
            int photos = 0, videos = 0;
            String error = null;
            try {
                photos = countMedia(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                videos = countMedia(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
            } catch (SecurityException denied) {
                error = "permission";
            } catch (RuntimeException failure) {
                error = "unavailable";
            }
            final int photoCount = photos, videoCount = videos;
            final String result = error;
            runOnUiThread(() -> {
                if (isFinishing() || target.getWindowToken() == null) return;
                if ("permission".equals(result)) target.setText(getString(R.string.local_files_permission));
                else if ("unavailable".equals(result)) target.setText(getString(R.string.local_files_unavailable));
                else target.setText(getString(R.string.local_files_count, photoCount, videoCount));
            });
        }, "hdvcam-local-media-index").start();
    }

    private int countMedia(android.net.Uri collection) {
        try (Cursor cursor = getContentResolver().query(collection,
                new String[]{android.provider.MediaStore.MediaColumns._ID}, null, null, null)) {
            return cursor == null ? 0 : cursor.getCount();
        }
    }

    private void openLocalMediaPicker() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.setType("*/*");
        picker.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(picker, REQUEST_OPEN_LOCAL_MEDIA); }
        catch (RuntimeException unavailable) {
            Toast.makeText(this, getString(R.string.local_media_no_viewer), Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_OPEN_LOCAL_MEDIA || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(data.getData(), getContentResolver().getType(data.getData()));
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(view); }
        catch (RuntimeException noViewer) { Toast.makeText(this, getString(R.string.local_media_no_viewer), Toast.LENGTH_LONG).show(); }
    }

    private View morePage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);
        page.addView(toolbar(getString(R.string.tab_more)));
        ImageView logo = new ImageView(this);
        logo.setImageResource(mip("ic_launcher_c"));
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(100), dp(100));
        logoLp.gravity = Gravity.CENTER_HORIZONTAL;
        logoLp.topMargin = dp(42);
        page.addView(logo, logoLp);
        TextView version = text(getString(R.string.version_label), 14, INK);
        version.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-1, dp(40));
        page.addView(version, vp);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(rounded(Color.WHITE, dp(10), 0, 0));
        LinearLayout.LayoutParams listp = new LinearLayout.LayoutParams(-1, -2);
        listp.setMargins(dp(15), dp(16), dp(15), 0);
        page.addView(list, listp);
        addMoreRow(list, "icon_permission_specification", getString(R.string.more_permissions), () -> Toast.makeText(this, getString(R.string.more_permissions), Toast.LENGTH_SHORT).show());
        addMoreRow(list, "icon_feedback", getString(R.string.more_feedback), () -> Toast.makeText(this, getString(R.string.more_feedback), Toast.LENGTH_SHORT).show());
        addMoreRow(list, "icon_manual_con", getString(R.string.privacy_policy), () -> Toast.makeText(this, getString(R.string.privacy_policy), Toast.LENGTH_SHORT).show());
        return page;
    }

    private void addMoreRow(LinearLayout list, String iconName, String title, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(18), 0);
        ImageView icon = new ImageView(this);
        icon.setImageResource(mip(iconName));
        row.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView label = text(title, 15, INK);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1);
        labelParams.leftMargin = dp(12);
        row.addView(label, labelParams);
        ImageView arrow = new ImageView(this);
        arrow.setImageResource(mip("right_back"));
        row.addView(arrow, new LinearLayout.LayoutParams(dp(16), dp(16)));
        list.addView(row, new LinearLayout.LayoutParams(-1, dp(60)));
        View line = new View(this);
        line.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
        lp.leftMargin = dp(40);
        lp.rightMargin = dp(20);
        list.addView(line, lp);
        row.setOnClickListener(v -> action.run());
    }

    private View toolbar(String title) {
        TextView bar = text(title, 20, INK);
        bar.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        bar.setGravity(Gravity.CENTER);
        bar.setBackgroundColor(Color.WHITE);
        bar.setElevation(dp(1));
        bar.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(56)));
        return bar;
    }

    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private GradientDrawable rounded(int color, float radius, int stroke, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        if (stroke > 0) d.setStroke(stroke, strokeColor);
        return d;
    }

    private int mip(String name) { return getResources().getIdentifier(name, "mipmap", getPackageName()); }
    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density + .5f); }
    private static DeviceFamily mediaFamilyForUiFamily(String family) {
        if (family == null) return null;
        if ("V3".equalsIgnoreCase(family) || "AW".equalsIgnoreCase(family)) return DeviceFamily.V3;
        if ("V316".equalsIgnoreCase(family)) return DeviceFamily.V316;
        if ("MStar".equalsIgnoreCase(family) || "MSTAR01".equalsIgnoreCase(family)) return DeviceFamily.MSTAR01;
        return null;
    }

    private void openCamera(String family) {
        Intent i = new Intent(this, CameraActivity.class);
        i.putExtra("family", family);
        startActivity(i);
    }

    private void openCamera(String family, Network network) {
        android.util.Log.i("HDVCamConnect", "Camera control responded; family=" + family);
        activeCameraNetwork = network;
        activeMediaFamily = mediaFamilyForUiFamily(family);
        Intent i = new Intent(this, CameraActivity.class);
        i.putExtra("family", family);
        i.putExtra("camera_network", network);
        cameraPreviewOpen = true;
        cameraWifiRequestActive = false;
        startActivity(i);
    }

    @Override protected void onDestroy() {
        if (isFinishing() && cameraWifiRequestActive && !cameraPreviewOpen) wifiConnector.release();
        if (isFinishing() && activeAwSession != null) {
            try { activeAwSession.close(); } catch (java.io.IOException ignored) { }
            activeAwSession = null;
        }
        if (isFinishing()) thumbnailExecutor.shutdownNow();
        super.onDestroy();
    }
}
