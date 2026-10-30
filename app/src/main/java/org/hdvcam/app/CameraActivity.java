package org.hdvcam.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Network;
import android.net.ConnectivityManager;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.FrameLayout;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.ui.PlayerView;

import com.hdvcam.core.CameraSetting;
import com.hdvcam.core.Evidence;
import com.hdvcam.core.TraceRecorder;
import com.hdvcam.protocol.aw.AnnexB;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Camera control screen shaped from activity_cam_con_atv.xml and the two mode layouts. */
public final class CameraActivity extends Activity {
    private static final int INK = 0xFF010B3A;
    private String[] videoNames;
    private String[] photoNames;
    private final String[] videoIcons = {"mode0_rec", "mode1_slow_rec", "mode2_loop_rec", "mode3_timelapse_rec"};
    private final String[] photoIcons = {"mode4_pic", "mode5_auto_pic", "mode6_continuous_pic", "mode7_time_pic"};
    private String family;
    private String selectedMode = "Grabación";
    private ImageView action;
    private FrameLayout player;
    private PlayerView playerView;
    private TextView streamStatus;
    private ExoPlayer exoPlayer;
    private ExecutorService streamResolver;
    private Network cameraNetwork;
    private String streamUri;
    private int streamMaxCachedDurationMs = 3000;
    private boolean recording;
    private int streamAttempt;
    private boolean activityDestroyed;
    private ConnectivityManager.NetworkCallback cameraNetworkCallback;
    private final Handler streamHandler = new Handler(Looper.getMainLooper());
    private final Runnable rtspWatchdog = new Runnable() {
        @Override public void run() {
            if (activityDestroyed || awMode || exoPlayer == null) return;
            long now = android.os.SystemClock.elapsedRealtime();
            long reference = lastRtspFrameAt;
            boolean stalled = reference == 0 ? now - rtspStartedAt > 12_000L : now - reference > 3_000L;
            if (stalled) {
                String reason = reference == 0 ? "no-first-frame-timeout" : "render-gap-gt-3000ms";
                traceStream("rtsp-watchdog", reason + "; gapMs=" + (now - (reference == 0 ? rtspStartedAt : reference)), Evidence.CONFIRMED);
                closePlaybackWindow(reason);
                ExoPlayer failed = exoPlayer;
                exoPlayer = null;
                if (failed != null) failed.release();
                playerView.setPlayer(null);
                scheduleStreamRetry();
                return;
            }
            streamHandler.postDelayed(this, 1000L);
        }
    };
    private final ImageView[] modeImages = new ImageView[8];
    private final TraceRecorder streamTrace = new TraceRecorder();
    private long streamAttemptStartedAt;
    private long rtspStartedAt;
    private volatile long lastRtspFrameAt;
    private boolean firstFrameObserved;
    private long droppedFrameCount;
    private long renderedFirstFrames;
    private long playbackReadySince;
    private File traceFile;
    private AwTcpSession awSession;
    private ScheduledExecutorService awStatsExecutor;
    private java.util.concurrent.ExecutorService awControlExecutor;
    private volatile boolean awSessionLossReported;
    private long lastLoggedVideoPackets;
    private long lastLoggedVideoFrames;
    private long lastLoggedVideoAccessUnits;
    private long lastLoggedRejectedVideoFrames;
    private long lastLoggedMediaSequenceGaps;
    private long lastLoggedAwHeartbeats;
    private long lastLoggedAwRenderedBuffers;
    private AwH264Decoder awDecoder;
    private SurfaceView awSurfaceView;
    private AwTcpSession.VideoListener awVideoListener;
    private boolean awMode;
    private final Handler ptzHandler = new Handler(Looper.getMainLooper());
    private ExecutorService ptzExecutor;
    private final AtomicBoolean ptzRequestInFlight = new AtomicBoolean();
    private int activePtzDirection = -1;
    private boolean ptzUnsupportedNoticeShown;
    private final Runnable repeatPtzCommand = new Runnable() {
        @Override public void run() {
            if (activityDestroyed || activePtzDirection < 0) return;
            dispatchPtz(activePtzDirection);
            ptzHandler.postDelayed(this, 100L);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        family = getIntent().getStringExtra("family");
        if (family == null) family = "MStar";
        cameraNetwork = getIntent().getParcelableExtra("camera_network");
        awMode = getIntent().getBooleanExtra("aw_mode", false);
        awSession = MainActivity.consumeAwSession();
        traceFile = new File(getFilesDir(), "traces/stream-" + System.currentTimeMillis() + ".log");
        videoNames = new String[]{getString(R.string.mode_video), getString(R.string.mode_slow), getString(R.string.mode_loop), getString(R.string.mode_timelapse)};
        photoNames = new String[]{getString(R.string.mode_photo), getString(R.string.mode_auto_photo), getString(R.string.mode_burst), getString(R.string.mode_timer_photo)};
        selectedMode = videoNames[0];
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        render();
        if (awMode) setupAwPreview();
        else {
            monitorCameraNetwork();
            resolveAndStartStream();
        }
    }

    private void setupAwPreview() {
        if (awSession == null) {
            streamStatus.setText("No hay sesión AW activa; vuelve a buscar la cámara.");
            return;
        }
        streamStatus.setText("Sesión AW negociada con " + awSession.address() + "; preparando vídeo…");
        traceStream("aw-session", "uid=" + awSession.uid() + "; address=" + awSession.address()
                + "; localUdp=" + awSession.localMediaPort() + "; ackUdpSelector=" + awSession.udpSelector()
                + "; remoteUdp=" + awSession.udpPeerPort()
                + "; udpReceiveBufferBytes=" + awSession.mediaReceiveBufferBytes()
                + "; startRequested=" + awSession.isVideoStartRequested(), Evidence.PARTIAL);
        player.removeView(playerView);
        awSurfaceView = new SurfaceView(this);
        player.addView(awSurfaceView, 0, new FrameLayout.LayoutParams(-1, -1));
        awVideoListener = frame -> {
            if (!looksLikeH264(frame)) return;
            AwH264Decoder decoder = awDecoder;
            if (decoder != null) decoder.queue(frame);
        };
        awControlExecutor = Executors.newSingleThreadExecutor();
        awSurfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                awDecoder = new AwH264Decoder(holder.getSurface(), status -> runOnUiThread(() -> {
                    if (!activityDestroyed && streamStatus != null) streamStatus.setText(status);
                }));
                awDecoder.start();
                if (awVideoListener != null) awSession.addVideoListener(awVideoListener);
                awControlExecutor.execute(() -> {
                    try {
                        awSession.startVideo();
                        runOnUiThread(() -> {
                            if (!activityDestroyed) {
                                streamStatus.setText("Solicitud de vídeo AW enviada; esperando datos de cámara…");
                                traceStream("aw-start-video", "Surface creada y listener H.264 registrado antes de IOControl 0x1ff", Evidence.PARTIAL);
                            }
                        });
                    } catch (IOException error) {
                        runOnUiThread(() -> {
                            if (!activityDestroyed) streamStatus.setText("No se pudo iniciar vídeo AW: " + error.getClass().getSimpleName());
                        });
                    }
                });
            }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }
            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                if (awVideoListener != null && awSession != null) awSession.removeVideoListener(awVideoListener);
                if (awDecoder != null) { awDecoder.close(); awDecoder = null; }
                if (awControlExecutor != null && awSession != null) awControlExecutor.execute(() -> {
                    try { awSession.stopVideo(); }
                    catch (IOException error) { android.util.Log.w("HDVCamAW", "No se pudo detener vídeo AW al perder Surface", error); }
                });
            }
        });
        awStatsExecutor = Executors.newSingleThreadScheduledExecutor();
        awStatsExecutor.scheduleAtFixedRate(() -> {
            AwTcpSession session = awSession;
            if (session == null || activityDestroyed) return;
            if (!session.isConnected()) {
                if (!awSessionLossReported) {
                    awSessionLossReported = true;
                    traceStream("aw-session-lost", "la conexión TCP de control está cerrada", Evidence.CONFIRMED);
                    runOnUiThread(() -> {
                        if (!activityDestroyed && streamStatus != null) {
                            streamStatus.setText("Sesión AW perdida. Vuelve a buscar y conectar la cámara.");
                            if (awDecoder != null) { awDecoder.close(); awDecoder = null; }
                        }
                    });
                }
                return;
            }
            long packets = session.receivedVideoPackets();
            long frames = session.receivedVideoFrames();
            long candidatePayloads = session.deliveredVideoPayloads();
            long rejectedFrames = session.rejectedVideoFrames();
            long packetDelta = packets - lastLoggedVideoPackets;
            long frameDelta = frames - lastLoggedVideoFrames;
            long accessUnitDelta = candidatePayloads - lastLoggedVideoAccessUnits;
            long rejectedFrameDelta = rejectedFrames - lastLoggedRejectedVideoFrames;
            lastLoggedVideoPackets = packets;
            lastLoggedVideoFrames = frames;
            lastLoggedVideoAccessUnits = candidatePayloads;
            lastLoggedRejectedVideoFrames = rejectedFrames;
            long audioPackets = session.receivedAudioPackets();
            long audioFrames = session.receivedAudioFrames();
            long mediaSequenceGaps = session.mediaSequenceGaps();
            long mediaGapDelta = mediaSequenceGaps - lastLoggedMediaSequenceGaps;
            lastLoggedMediaSequenceGaps = mediaSequenceGaps;
            long heartbeats = session.receivedHeartbeats();
            long heartbeatDelta = heartbeats - lastLoggedAwHeartbeats;
            lastLoggedAwHeartbeats = heartbeats;
            AwH264Decoder decoder = awDecoder;
            long renderedBuffers = decoder == null ? 0 : decoder.renderedOutputBuffers();
            long lastRenderedAt = decoder == null ? 0 : decoder.lastRenderedOutputAtMs();
            long renderedGapMs = lastRenderedAt == 0 ? -1
                    : android.os.SystemClock.elapsedRealtime() - lastRenderedAt;
            long renderedDelta = renderedBuffers - lastLoggedAwRenderedBuffers;
            lastLoggedAwRenderedBuffers = renderedBuffers;
            String pipelineState = packets == 0 ? "no-video-datagrams"
                    : frames == 0 ? "datagrams-without-complete-frame"
                    : candidatePayloads == 0 ? "frames-rejected-before-h264-delivery"
                    : decoder == null ? "h264-candidate-without-decoder"
                    : !decoder.hasSps() ? "waiting-for-sps"
                    : !decoder.hasPps() ? "waiting-for-pps"
                    : !decoder.isConfigured() ? "decoder-not-configured"
                    : decoder.isWaitingForIdr() ? "waiting-for-idr"
                    : renderedBuffers == 0 ? "decoder-input-without-surface-output"
                    : renderedGapMs > 3000 ? "surface-output-stalled" : "decoder-output-active";
            if (awVideoListener != null && "decoder-output-active".equals(pipelineState)) {
                runOnUiThread(() -> {
                    if (!activityDestroyed && streamStatus != null) streamStatus.setText("Vídeo AW activo · "
                            + decoder.outputWidth() + "×" + decoder.outputHeight());
                });
            } else if (awVideoListener != null && "surface-output-stalled".equals(pipelineState)) {
                runOnUiThread(() -> {
                    if (!activityDestroyed && streamStatus != null) streamStatus.setText("Vídeo AW detenido: sin salida del decoder hace "
                            + (renderedGapMs / 1000) + " s.");
                });
            }
            traceStream("aw-media-stats", "pipelineState=" + pipelineState
                        + "; videoPackets=" + packets + "; videoPacketsPerInterval=" + packetDelta
                        + "; assembledTransportFrames=" + frames + "; transportFramesPerInterval=" + frameDelta
                        + "; candidateH264Payloads=" + candidatePayloads + "; candidatePayloadsPerInterval=" + accessUnitDelta
                        + "; rejectedFrames=" + rejectedFrames + "; rejectedFramesPerInterval=" + rejectedFrameDelta
                        + "; mediaSequenceGaps=" + mediaSequenceGaps + "; mediaSequenceGapsPerInterval=" + mediaGapDelta
                        + "; lastFrameBytes=" + session.lastVideoFrameBytes()
                        + "; audioPackets=" + audioPackets
                        + "; assembledAudioFrames=" + audioFrames
                        + "; heartbeats=" + heartbeats + "; heartbeatsPerInterval=" + heartbeatDelta
                        + "; lastAudioFrameBytes=" + session.lastAudioFrameBytes()
                        + "; queuedDecoderInputs=" + (decoder == null ? 0 : decoder.queuedInputBuffers())
                        + "; renderedOutputBuffers=" + renderedBuffers
                        + "; renderedBuffersPerInterval=" + renderedDelta
                        + "; lastRenderedOutputAgeMs=" + renderedGapMs
                        + "; decoderHasSps=" + (decoder != null && decoder.hasSps())
                        + "; decoderHasPps=" + (decoder != null && decoder.hasPps())
                        + "; decoderConfigured=" + (decoder != null && decoder.isConfigured())
                        + "; decoderWaitingForIdr=" + (decoder != null && decoder.isWaitingForIdr())
                        + "; decoderErrors=" + (decoder == null ? 0 : decoder.decoderErrors())
                        + "; decoderQueueOverflows=" + (decoder == null ? 0 : decoder.queueOverflows())
                        + "; framesDroppedWaitingForIdr=" + (decoder == null ? 0 : decoder.framesDroppedWaitingForIdr())
                        + "; decoderSize=" + (decoder == null ? 0 : decoder.outputWidth()) + "x"
                        + (decoder == null ? 0 : decoder.outputHeight())
                        + "; unknownPackets=" + session.receivedUnknownMediaPackets()
                        + "; lastPacketAgeMs=" + session.mediaPacketAgeMs(), Evidence.PARTIAL);
        }, 2, 2, TimeUnit.SECONDS);
    }

    private static boolean looksLikeH264(byte[] bytes) {
        return AnnexB.containsPlausibleNal(bytes);
    }

    private void monitorCameraNetwork() {
        if (cameraNetwork == null) return;
        ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (manager == null) return;
        cameraNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onLost(Network network) {
                if (cameraNetwork.equals(network) && !activityDestroyed) runOnUiThread(() -> {
                    if (exoPlayer != null) { exoPlayer.release(); exoPlayer = null; }
                    streamStatus.setText("Wi-Fi de cámara desconectada. Esperando red…");
                });
            }

            @Override public void onAvailable(Network network) {
                if (cameraNetwork.equals(network) && !activityDestroyed) runOnUiThread(() -> {
                    streamAttempt = 0;
                    scheduleStreamRetry();
                });
            }
        };
        try { manager.registerNetworkCallback(new android.net.NetworkRequest.Builder()
                .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build(), cameraNetworkCallback); }
        catch (RuntimeException error) { cameraNetworkCallback = null; }
    }

    private void render() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF6F6F6);

        FrameLayout header = new FrameLayout(this);
        header.setBackgroundColor(Color.WHITE);
        TextView back = text("‹", 34, INK);
        back.setGravity(Gravity.CENTER);
        header.addView(back, new FrameLayout.LayoutParams(dp(55), -1, Gravity.LEFT));
        back.setOnClickListener(v -> finish());
        TextView title = text(getString(R.string.live_preview), 20, INK);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new FrameLayout.LayoutParams(-1, -1));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(56)));
        View divider = new View(this); divider.setBackgroundColor(0xFFCED5DC);
        root.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));

        int widthDp = (int)(getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density);
        LinearLayout liveArea = new LinearLayout(this);
        liveArea.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams liveLp = new LinearLayout.LayoutParams(-1, dp((int)(widthDp * .75f)));
        root.addView(liveArea, liveLp);
        player = new FrameLayout(this);
        player.setBackgroundColor(Color.BLACK);
        liveArea.addView(player, new LinearLayout.LayoutParams(-1, dp((int)(widthDp * 9f / 16f))));
        playerView = new PlayerView(this);
        playerView.setUseController(false);
        player.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
        streamStatus = text("Buscando vídeo…", 12, Color.WHITE);
        streamStatus.setGravity(Gravity.CENTER);
        streamStatus.setBackground(round(0x66000000, dp(6)));
        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        statusLp.topMargin = dp(8);
        player.addView(streamStatus, statusLp);
        TextView zoom = text("1.0×", 11, Color.WHITE);
        zoom.setGravity(Gravity.CENTER);
        zoom.setBackground(round(0x66000000, dp(8)));
        FrameLayout.LayoutParams zp = new FrameLayout.LayoutParams(dp(40), dp(22), Gravity.BOTTOM | Gravity.LEFT);
        zp.setMargins(dp(10), 0, 0, dp(8)); player.addView(zoom, zp);
        ImageView full = new ImageView(this); full.setImageResource(mip("icon_full"));
        full.setColorFilter(Color.WHITE); full.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams fullp = new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.BOTTOM | Gravity.RIGHT);
        fullp.setMargins(0, 0, dp(8), dp(2)); player.addView(full, fullp);
        full.setOnClickListener(v -> Toast.makeText(this, "Pantalla completa: transición pendiente", Toast.LENGTH_SHORT).show());
        LinearLayout status = new LinearLayout(this);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(dp(10), 0, dp(10), 0);
        status.setBackgroundColor(0xFFECECEC);
        TextView resolution = text("4K 60FPS", 14, Color.BLACK);
        status.addView(resolution, new LinearLayout.LayoutParams(0, -1, 1));
        TextView battery = text("▰  --%", 13, Color.BLACK);
        battery.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        status.addView(battery);
        liveArea.addView(status, new LinearLayout.LayoutParams(-1, dp(30)));

        root.addView(ptzControls(), new LinearLayout.LayoutParams(-1, dp(148)));

        addModeRow(root, photoNames, photoIcons, 4, true);
        addModeRow(root, videoNames, videoIcons, 0, false);
        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1));

        FrameLayout bottom = new FrameLayout(this);
        LinearLayout.LayoutParams bottomLp = new LinearLayout.LayoutParams(-1, dp(130));
        root.addView(bottom, bottomLp);
        action = new ImageView(this);
        action.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(dp(80), dp(80), Gravity.CENTER);
        ap.bottomMargin = dp(26);
        bottom.addView(action, ap);
        action.setOnClickListener(v -> cameraAction());

        ImageView settings = new ImageView(this);
        settings.setImageResource(mip("cam_set"));
        FrameLayout.LayoutParams setp = new FrameLayout.LayoutParams(dp(50), dp(50), Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        setp.rightMargin = dp(20); setp.bottomMargin = dp(26); bottom.addView(settings, setp);
        settings.setOnClickListener(v -> settings());
        ImageView files = new ImageView(this);
        files.setImageResource(mip("cam_alb"));
        files.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams filep = new FrameLayout.LayoutParams(dp(50), dp(50), Gravity.CENTER_VERTICAL | Gravity.LEFT);
        filep.leftMargin = dp(20); filep.bottomMargin = dp(26); bottom.addView(files, filep);
        files.setOnClickListener(v -> Toast.makeText(this, "Archivos de cámara: lista/descarga pendiente", Toast.LENGTH_SHORT).show());

        setContentView(root);
        selectMode(selectedMode);
    }

    private View ptzControls() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        TextView title = text("Control PTZ · Mantén pulsado para mover", 12, INK);
        title.setGravity(Gravity.CENTER);
        panel.addView(title, new LinearLayout.LayoutParams(-1, dp(25)));
        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        pad.setGravity(Gravity.CENTER);
        pad.addView(ptzButton("↑", 5), new LinearLayout.LayoutParams(dp(52), dp(38)));
        LinearLayout middle = new LinearLayout(this);
        middle.setGravity(Gravity.CENTER);
        middle.addView(ptzButton("←", 0), new LinearLayout.LayoutParams(dp(52), dp(38)));
        TextView center = text("PTZ", 10, 0xFF68717B);
        center.setGravity(Gravity.CENTER);
        middle.addView(center, new LinearLayout.LayoutParams(dp(42), dp(38)));
        middle.addView(ptzButton("→", 1), new LinearLayout.LayoutParams(dp(52), dp(38)));
        pad.addView(middle);
        pad.addView(ptzButton("↓", 4), new LinearLayout.LayoutParams(dp(52), dp(38)));
        panel.addView(pad);
        return panel;
    }

    private TextView ptzButton(String label, int direction) {
        TextView button = text(label, 22, Color.WHITE);
        button.setGravity(Gravity.CENTER);
        button.setBackground(round(0xFF343A40, dp(8)));
        button.setEnabled(true);
        button.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    ptzHandler.removeCallbacks(repeatPtzCommand);
                    activePtzDirection = direction;
                    dispatchPtz(direction);
                    ptzHandler.postDelayed(repeatPtzCommand, 100L);
                    view.setAlpha(0.65f);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    ptzHandler.removeCallbacks(repeatPtzCommand);
                    activePtzDirection = -1;
                    view.setAlpha(1.0f);
                    return true;
                default:
                    return true;
            }
        });
        return button;
    }

    private void dispatchPtz(int direction) {
        if (!"QZ".equalsIgnoreCase(family)) {
            if (!ptzUnsupportedNoticeShown) {
                ptzUnsupportedNoticeShown = true;
                Toast.makeText(this, "El comando PTZ recuperado corresponde a QZ; este firmware requiere localizar su protocolo.", Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (!ptzRequestInFlight.compareAndSet(false, true)) return;
        if (ptzExecutor == null || ptzExecutor.isShutdown()) ptzExecutor = Executors.newSingleThreadExecutor();
        ptzExecutor.execute(() -> {
            try {
                int response = CameraCommandDispatcher.movePtz(family, cameraNetwork, direction);
                if (response < 200 || response >= 300) android.util.Log.w("HDVCamPTZ", "QZ cmd 4005 respondió HTTP " + response);
            } catch (Exception error) {
                android.util.Log.w("HDVCamPTZ", "No se pudo enviar QZ PTZ par=" + direction, error);
            } finally {
                ptzRequestInFlight.set(false);
            }
        });
    }

    private void addModeRow(LinearLayout root, String[] labels, String[] icons, int offset, boolean photo) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowp = new LinearLayout.LayoutParams(-1, -2);
        rowp.topMargin = dp(15);
        root.addView(row, rowp);
        for (int i = 0; i < labels.length; i++) {
            final String label = labels[i];
            LinearLayout item = new LinearLayout(this);
            item.setGravity(Gravity.CENTER);
            item.setOrientation(LinearLayout.VERTICAL);
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            modeImages[offset + i] = image;
            item.addView(image, new LinearLayout.LayoutParams(dp(40), dp(40)));
            TextView caption = text(labels[i], 12, Color.BLACK);
            caption.setGravity(Gravity.CENTER);
            caption.setMaxLines(2);
            caption.setPadding(dp(2), 0, dp(2), 0);
            item.addView(caption, new LinearLayout.LayoutParams(-1, -2));
            item.setOnClickListener(v -> selectMode(label));
            row.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
        }
    }

    private void selectMode(String value) {
        selectedMode = value;
        for (int i = 0; i < videoNames.length; i++) {
            if (modeImages[i] != null) modeImages[i].setImageResource(mip(videoIcons[i] + (selectedMode.equals(videoNames[i]) ? "_blue_dhf" : "_black_sds")));
        }
        String[] videoSuffix = {"_blue_dhf", "_blue_rsdsd", "_blue_fgx", "_blue_ugiea"};
        String[] videoOff = {"_black_sds", "_black_aasd", "_black_aasd", "_black_rdf"};
        for (int i = 0; i < 4; i++) if (modeImages[i] != null)
            modeImages[i].setImageResource(mip(videoIcons[i] + (selectedMode.equals(videoNames[i]) ? videoSuffix[i] : videoOff[i])));
        String[] photoSuffix = {"_blue_sej", "_blue_seih", "_blue_drd", "_blue"};
        String[] photoOff = {"_black_huad", "_black_shuf", "_black_eguie", "_black"};
        for (int i = 0; i < 4; i++) if (modeImages[4 + i] != null)
            modeImages[4 + i].setImageResource(mip(photoIcons[i] + (selectedMode.equals(photoNames[i]) ? photoSuffix[i] : photoOff[i])));
        if (action != null) action.setImageResource(mip(isVideoMode(selectedMode)
                ? (recording ? "stop_rec_stop" : "rcd_stop_ac") : "start_pic_start"));
        updatePlayerRatio();
    }

    private boolean isVideoMode(String mode) {
        return mode.equals(videoNames[0]) || mode.equals(videoNames[1])
                || mode.equals(videoNames[2]) || mode.equals(videoNames[3]);
    }

    private void updatePlayerRatio() {
        if (player == null) return;
        int widthDp = (int)(getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density);
        int ratioHeight = selectedMode.equals(photoNames[0]) || selectedMode.equals(photoNames[1])
                || selectedMode.equals(photoNames[2]) || selectedMode.equals(photoNames[3])
                ? widthDp * 3 / 4 : widthDp * 9 / 16;
        player.getLayoutParams().height = dp(ratioHeight);
        player.requestLayout();
    }

    private void cameraAction() {
        if (!isVideoMode(selectedMode)) {
            if (!"MStar".equalsIgnoreCase(family)) {
                String detail = "QZ".equalsIgnoreCase(family)
                        ? "El APK usa cmd_id 4016 por un canal de control aún no reproducido."
                        : "El comando de captura YZ aún no está identificado.";
                Toast.makeText(this, detail, Toast.LENGTH_LONG).show();
                return;
            }
            if (streamResolver == null || streamResolver.isShutdown()) streamResolver = Executors.newSingleThreadExecutor();
            streamStatus.setText("Enviando captura…");
            streamResolver.execute(() -> {
                try {
                    int response = CameraCommandDispatcher.capturePhoto(family, cameraNetwork);
                    runOnUiThread(() -> {
                        if (response >= 200 && response < 300) {
                            streamStatus.setText("Captura solicitada");
                            Toast.makeText(this, "La cámara aceptó la orden de captura.", Toast.LENGTH_SHORT).show();
                        } else {
                            streamStatus.setText("La cámara rechazó la captura.");
                            Toast.makeText(this, "Respuesta HTTP " + response + ".", Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (Exception error) {
                    runOnUiThread(() -> {
                        streamStatus.setText("No se pudo solicitar la captura.");
                        Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            });
            return;
        }
        boolean start = !recording;
        if (streamResolver == null || streamResolver.isShutdown()) streamResolver = Executors.newSingleThreadExecutor();
        streamStatus.setText(start ? "Iniciando grabación…" : "Deteniendo grabación…");
        streamResolver.execute(() -> {
            try {
                int response = CameraCommandDispatcher.setRecording(family, cameraNetwork, start);
                runOnUiThread(() -> {
                    if (response >= 200 && response < 300) {
                        recording = start;
                        selectMode(selectedMode);
                        streamStatus.setText("En directo");
                        Toast.makeText(this, start ? "Comando de grabación aceptado por HTTP." : "Comando para detener grabación aceptado por HTTP.", Toast.LENGTH_SHORT).show();
                    } else {
                        streamStatus.setText("La cámara rechazó la orden de grabación.");
                        Toast.makeText(this, "Respuesta HTTP " + response + ".", Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    streamStatus.setText("No se pudo enviar la orden de grabación.");
                    Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void resolveAndStartStream() {
        if (activityDestroyed) return;
        if (streamResolver != null && !streamResolver.isShutdown()) streamResolver.shutdownNow();
        streamAttemptStartedAt = android.os.SystemClock.elapsedRealtime();
        traceStream("resolve", "Intento " + (streamAttempt + 1) + "; familia=" + family, Evidence.CONFIRMED);
        streamStatus.setText("Resolviendo la dirección RTSP…");
        streamResolver = Executors.newSingleThreadExecutor();
        streamResolver.execute(() -> {
            try {
                CameraStreamResolver.ResolvedStream resolved = CameraStreamResolver.resolveStream(family, cameraNetwork);
                String uri = resolved.rtspUri;
                String status = CameraStreamResolver.probeRtsp(uri, cameraNetwork, 2500);
                traceStream("rtsp-options", "family=" + family + "; " + status, Evidence.CONFIRMED);
                android.util.Log.i("HDVCamStream", "RTSP OPTIONS " + status + " family=" + family);
                android.net.Uri parsed = android.net.Uri.parse(uri);
                int resolvedPort = parsed.getPort() >= 0 ? parsed.getPort() : 554;
                traceStream("endpoint", "host=" + parsed.getHost() + "; port=" + resolvedPort
                        + "; credentials=" + (parsed.getUserInfo() == null ? "none" : "redacted"), Evidence.PARTIAL);
                runOnUiThread(() -> {
                    if (activityDestroyed) return;
                    streamMaxCachedDurationMs = resolved.maxCachedDurationMs;
                    traceStream("buffer-profile", "maxCachedDurationMs=" + streamMaxCachedDurationMs
                            + "; profile=" + (streamMaxCachedDurationMs == 100 ? "8K" : "normal"), Evidence.PARTIAL);
                    startRtsp(uri);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (activityDestroyed) return;
                    traceStream("resolve-error", e.getClass().getSimpleName() + ": " + safeMessage(e), Evidence.CONFIRMED);
                    streamStatus.setText("Búsqueda de vídeo " + (streamAttempt + 1) + " fallida: " + safeMessage(e));
                    scheduleStreamRetry();
                });
            }
        });
    }

    private void scheduleStreamRetry() {
        if (activityDestroyed) return;
        int exponent = Math.min(streamAttempt, 5);
        int delay = Math.min(1000 << exponent, 30_000);
        if (streamAttempt < Integer.MAX_VALUE) streamAttempt++;
        traceStream("retry-scheduled", "delayMs=" + delay + "; attempt=" + streamAttempt, Evidence.CONFIRMED);
        streamStatus.setText("Reintentando vídeo en " + (delay / 1000.0) + " s…");
        streamHandler.removeCallbacksAndMessages(null);
        streamHandler.postDelayed(this::resolveAndStartStream, delay);
    }

    private void startRtsp(String uri) {
        streamUri = uri;
        rtspStartedAt = android.os.SystemClock.elapsedRealtime();
        lastRtspFrameAt = 0;
        firstFrameObserved = false;
        droppedFrameCount = 0;
        renderedFirstFrames = 0;
        playbackReadySince = 0;
        traceStream("player-start", "family=" + family + "; transport=RTP/TCP; RTSP source resolved", Evidence.CONFIRMED);
        streamStatus.setText("Conectando con la cámara…");
        RtspMediaSource.Factory sourceFactory = new RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .setTimeoutMs(6000);
        if (cameraNetwork != null) sourceFactory.setSocketFactory(cameraNetwork.getSocketFactory());
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMsForStreaming(100, streamMaxCachedDurationMs, 100, 100)
                .build();
        exoPlayer = new ExoPlayer.Builder(this).setLoadControl(loadControl).build();
        playerView.setPlayer(exoPlayer);
        exoPlayer.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) {
                    streamStatus.setText(firstFrameObserved ? "Vídeo en búfer…" : "Esperando primer frame…");
                    traceStream(firstFrameObserved ? "buffering" : "waiting-first-frame", "elapsedMs=" + elapsedAttemptMs(), Evidence.CONFIRMED);
                } else if (state == Player.STATE_READY) {
                    streamStatus.setText("En directo");
                    if (playbackReadySince == 0) playbackReadySince = android.os.SystemClock.elapsedRealtime();
                    traceStream("playback-ready", "startupMs=" + elapsedAttemptMs() + "; playbackState=READY", Evidence.PARTIAL);
                } else if (state == Player.STATE_IDLE) {
                    closePlaybackWindow("idle");
                    streamStatus.setText("Vídeo detenido");
                    traceStream("player-idle", "playbackState=IDLE", Evidence.CONFIRMED);
                }
            }

            @Override public void onRenderedFirstFrame() {
                firstFrameObserved = true;
                lastRtspFrameAt = android.os.SystemClock.elapsedRealtime();
                renderedFirstFrames++;
                traceStream("first-frame-rendered", "startupMs=" + elapsedAttemptMs(), Evidence.CONFIRMED);
            }

            @Override public void onVideoSizeChanged(androidx.media3.common.VideoSize size) {
                traceStream("video-size", "width=" + size.width + "; height=" + size.height
                        + "; unappliedRotationDegrees=" + size.unappliedRotationDegrees
                        + "; pixelRatio=" + size.pixelWidthHeightRatio, Evidence.CONFIRMED);
            }

            @Override public void onPlayerError(PlaybackException error) {
                closePlaybackWindow("player-error");
                traceStream("player-error", error.getErrorCodeName() + "; cause=" + error.getClass().getSimpleName()
                        + "; afterFirstFrame=" + firstFrameObserved, Evidence.CONFIRMED);
                streamStatus.setText("Error de vídeo: " + error.getErrorCodeName() + ". Reintentando…");
                if (exoPlayer != null) {
                    exoPlayer.release();
                    exoPlayer = null;
                }
                scheduleStreamRetry();
            }
        });
        exoPlayer.addAnalyticsListener(new AnalyticsListener() {
            @Override public void onDroppedVideoFrames(EventTime eventTime, int count, long elapsedMs) {
                droppedFrameCount += count;
                traceStream("dropped-frames", "count=" + count + "; elapsedMs=" + elapsedMs
                        + "; cumulative=" + droppedFrameCount, Evidence.CONFIRMED);
            }

            @Override public void onVideoFrameProcessingOffset(EventTime eventTime, long totalProcessingOffsetUs, int frameCount) {
                if (frameCount > 0) lastRtspFrameAt = android.os.SystemClock.elapsedRealtime();
                traceStream("frame-processing", "frames=" + frameCount + "; totalOffsetUs=" + totalProcessingOffsetUs,
                        Evidence.CONFIRMED);
            }
        });
        exoPlayer.setMediaSource(sourceFactory.createMediaSource(MediaItem.fromUri(uri)));
        exoPlayer.prepare();
        exoPlayer.play();
        streamHandler.removeCallbacks(rtspWatchdog);
        streamHandler.postDelayed(rtspWatchdog, 1000L);
    }

    private long elapsedAttemptMs() {
        return Math.max(0, android.os.SystemClock.elapsedRealtime() - streamAttemptStartedAt);
    }

    private void closePlaybackWindow(String reason) {
        if (playbackReadySince == 0) return;
        long now = android.os.SystemClock.elapsedRealtime();
        traceStream("playback-window", "reason=" + reason + "; durationMs=" + (now - playbackReadySince)
                + "; firstFrameCallbacks=" + renderedFirstFrames + "; droppedFrames=" + droppedFrameCount
                + "; playbackTransport=RTSP/RTP-TCP", Evidence.PARTIAL);
        if (now - playbackReadySince >= 60_000L) streamAttempt = 0;
        playbackReadySince = 0;
        renderedFirstFrames = 0;
    }

    private void traceStream(String operation, String detail, Evidence evidence) {
        String line = streamTrace.add("stream/" + family, operation, evidence, detail).format();
        android.util.Log.i("HDVCamTrace", line);
        persistTrace(line);
    }

    private synchronized void persistTrace(String line) {
        if (traceFile == null) return;
        try {
            File parent = traceFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) return;
            long maxBytes = 1024L * 1024L;
            if (traceFile.exists() && traceFile.length() > maxBytes) {
                File previous = new File(traceFile.getParentFile(), "stream-previous.log");
                if (previous.exists()) previous.delete();
                if (!traceFile.renameTo(previous)) traceFile.delete();
            }
            try (FileOutputStream output = new FileOutputStream(traceFile, true)) {
                output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException error) {
            android.util.Log.w("HDVCamTrace", "No se pudo guardar la traza local: " + error.getClass().getSimpleName());
        }
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null) return "sin detalle";
        return message.replaceAll("(?i)(rtsp://)[^/@\\s]+@", "$1[redacted]@");
    }

    @Override protected void onDestroy() {
        activityDestroyed = true;
        streamHandler.removeCallbacksAndMessages(null);
        ptzHandler.removeCallbacksAndMessages(null);
        activePtzDirection = -1;
        if (ptzExecutor != null) { ptzExecutor.shutdownNow(); ptzExecutor = null; }
        if (exoPlayer != null) {
            exoPlayer.release();
            exoPlayer = null;
        }
        if (awSession != null) {
            if (awVideoListener != null) awSession.removeVideoListener(awVideoListener);
            try { awSession.close(); } catch (IOException ignored) { }
            awSession = null;
        }
        if (awControlExecutor != null) { awControlExecutor.shutdownNow(); awControlExecutor = null; }
        if (awStatsExecutor != null) { awStatsExecutor.shutdownNow(); awStatsExecutor = null; }
        if (awDecoder != null) { awDecoder.close(); awDecoder = null; }
        if (streamResolver != null) streamResolver.shutdownNow();
        if (cameraNetworkCallback != null) {
            ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (manager != null) try { manager.unregisterNetworkCallback(cameraNetworkCallback); } catch (RuntimeException ignored) { }
        }
        super.onDestroy();
    }

    private void settings() {
        CameraSetting[] settings = CameraSetting.values();
        String[] labels = new String[settings.length];
        for (int i = 0; i < settings.length; i++) labels[i] = settings[i].label();
        new AlertDialog.Builder(this).setTitle("Ajustes").setItems(labels, (d, which) ->
                new AlertDialog.Builder(this).setTitle(labels[which])
                        .setMessage("Las opciones se cargan desde el firmware de la cámara.\n\n" + settings[which].dispatch() + "\n\nSin conexión: no se cambió ningún ajuste.")
                        .setPositiveButton(android.R.string.ok, null).show())
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); return t;
    }
    private GradientDrawable round(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(radius); return d; }
    private int mip(String name) { return getResources().getIdentifier(name, "mipmap", getPackageName()); }
    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density + .5f); }
}
