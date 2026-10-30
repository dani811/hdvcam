package org.hdvcam.app;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;

/** Feeds complete H.264 access units from the AW UDP reassembler into Android's decoder. */
final class AwH264Decoder {
    interface Listener { void onStatus(String status); }
    private static final String TAG = "HDVCamAWDecoder";
    private final Surface surface;
    private final Listener listener;
    private final Object lock = new Object();
    private final AwH264AccessUnitQueue pendingFrames = new AwH264AccessUnitQueue();
    private MediaCodec codec;
    private MediaFormat mediaFormat;
    private Thread decodeThread;
    private byte[] sps;
    private byte[] pps;
    private boolean released;
    private boolean configured;
    private boolean awaitingIdr = true;
    private long queuedFrames;
    private volatile long renderedOutputBuffers;
    private volatile long lastRenderedOutputAtMs;
    private volatile long queuedInputBuffers;
    private volatile long decoderErrors;
    private volatile int lastOutputWidth;
    private volatile int lastOutputHeight;

    AwH264Decoder(Surface surface, Listener listener) {
        this.surface = surface;
        this.listener = listener;
    }

    void start() {
        synchronized (lock) {
            if (released || codec != null || !surface.isValid()) return;
            mediaFormat = new MediaFormat();
            mediaFormat.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_AVC);
            decodeThread = new Thread(this::decodeQueuedFrames, "hdvcam-aw-h264-decoder");
            decodeThread.setDaemon(true);
            decodeThread.start();
            listener.onStatus("Decoder H.264 preparado; esperando SPS/PPS en banda…");
        }
    }

    void queue(byte[] accessUnit) {
        if (accessUnit == null || accessUnit.length == 0 || released) return;
        if (!pendingFrames.offer(accessUnit)) {
            listener.onStatus("Decoder retrasado; cola reiniciada y esperando frame IDR…");
        }
    }

    private void decodeQueuedFrames() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
        while (!released) {
            byte[] accessUnit;
            try { accessUnit = pendingFrames.take(); }
            catch (InterruptedException stopped) { return; }
            if (accessUnit == null) return;
            if (pendingFrames.discardUntilIdr(accessUnit)) continue;
            synchronized (lock) {
                if (released || mediaFormat == null) return;
                decodeAccessUnit(accessUnit);
            }
        }
    }

    private void decodeAccessUnit(byte[] accessUnit) {
        try {
            boolean containsIdr = inspectNals(accessUnit);
            if (sps == null || pps == null) {
                listener.onStatus("Recibido H.264; esperando SPS y PPS (" + accessUnit.length + " bytes)…");
                return;
            }
            if (!configured) configureCodec();
            if (codec == null || !configured) return;
            if (awaitingIdr && !containsIdr) {
                listener.onStatus("Parámetros H.264 recibidos; esperando frame IDR…");
                return;
            }
            if (containsIdr) awaitingIdr = false;
            int input = codec.dequeueInputBuffer(0);
            if (input < 0) {
                drainOutput();
                return;
            }
            ByteBuffer buffer = codec.getInputBuffer(input);
            if (buffer == null || accessUnit.length > buffer.capacity()) {
                codec.queueInputBuffer(input, 0, 0, 0, 0);
                awaitingIdr = true;
                pendingFrames.requireIdr();
                listener.onStatus("Access unit mayor que el buffer del decoder; descartada.");
                return;
            }
            buffer.clear();
            buffer.put(accessUnit);
            long ptsUs = android.os.SystemClock.elapsedRealtimeNanos() / 1000L;
            codec.queueInputBuffer(input, 0, accessUnit.length, ptsUs, 0);
            queuedFrames++;
            queuedInputBuffers++;
            drainOutput();
            if (renderedOutputBuffers == 0) listener.onStatus("H.264 enviado al decoder; esperando salida renderizada…");
        } catch (Exception error) {
            Log.w(TAG, "Fallo alimentando el decoder", error);
            decoderErrors++;
            awaitingIdr = true;
            pendingFrames.requireIdr();
            listener.onStatus("Error decodificando H.264: " + error.getClass().getSimpleName());
            releaseCodec();
        }
    }

    private void drainOutput() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int output;
        while ((output = codec.dequeueOutputBuffer(info, 0)) >= 0) {
            boolean render = info.size > 0;
            codec.releaseOutputBuffer(output, true);
            if (render) {
                renderedOutputBuffers++;
                lastRenderedOutputAtMs = android.os.SystemClock.elapsedRealtime();
                listener.onStatus(renderedOutputBuffers == 1
                        ? "Primer buffer H.264 liberado para Surface (" + info.size + " bytes)."
                        : "Vídeo H.264 activo: " + renderedOutputBuffers + " buffers liberados para Surface.");
            }
        }
        if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            MediaFormat format = codec.getOutputFormat();
            lastOutputWidth = format.getInteger(MediaFormat.KEY_WIDTH);
            lastOutputHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
            listener.onStatus("Formato H.264 detectado: " + lastOutputWidth + "×" + lastOutputHeight);
        }
    }

    long renderedOutputBuffers() { return renderedOutputBuffers; }
    long lastRenderedOutputAtMs() { return lastRenderedOutputAtMs; }
    long queuedInputBuffers() { return queuedInputBuffers; }
    long decoderErrors() { return decoderErrors; }
    long queueOverflows() { return pendingFrames.overflowCount(); }
    long framesDroppedWaitingForIdr() { return pendingFrames.discardedUntilIdrCount(); }
    boolean hasSps() { return sps != null; }
    boolean hasPps() { return pps != null; }
    boolean isConfigured() { return configured; }
    boolean isWaitingForIdr() { return awaitingIdr || pendingFrames.isWaitingForIdr(); }
    int outputWidth() { return lastOutputWidth; }
    int outputHeight() { return lastOutputHeight; }

    private boolean inspectNals(byte[] bytes) {
        boolean containsIdr = false;
        for (int i = 0; i + 3 < bytes.length; i++) {
            int header = bytes[i] == 0 && bytes[i + 1] == 0 && bytes[i + 2] == 1 ? i + 3
                    : i + 4 < bytes.length && bytes[i] == 0 && bytes[i + 1] == 0
                    && bytes[i + 2] == 0 && bytes[i + 3] == 1 ? i + 4 : -1;
            if (header >= 0 && header < bytes.length) {
                int type = bytes[header] & 0x1f;
                int start = i;
                if (type == 7 || type == 8) {
                    int end = findNextStartCode(bytes, header + 1);
                    if (end < 0) end = bytes.length;
                    byte[] nal = java.util.Arrays.copyOfRange(bytes, start, end);
                    if (type == 7) sps = nal;
                    else pps = nal;
                }
                containsIdr |= type == 5;
                i = header;
            }
        }
        return containsIdr;
    }

    private static int findNextStartCode(byte[] bytes, int from) {
        for (int i = from; i + 2 < bytes.length; i++) {
            if (bytes[i] == 0 && bytes[i + 1] == 0
                    && (bytes[i + 2] == 1 || (i + 3 < bytes.length && bytes[i + 2] == 0 && bytes[i + 3] == 1))) return i;
        }
        return -1;
    }

    private void configureCodec() {
        try {
            mediaFormat.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
            mediaFormat.setByteBuffer("csd-1", ByteBuffer.wrap(pps));
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            codec.configure(mediaFormat, surface, null, 0);
            codec.start();
            configured = true;
            listener.onStatus("Decoder H.264 listo con SPS/PPS; esperando frame IDR…");
        } catch (Exception error) {
            releaseCodec();
            listener.onStatus("No se pudo configurar el decoder H.264: " + error.getClass().getSimpleName());
        }
    }

    void close() {
        released = true;
        pendingFrames.close();
        if (decodeThread != null) decodeThread.interrupt();
        synchronized (lock) {
            releaseCodec();
        }
    }

    private void releaseCodec() {
        if (codec == null) return;
        try { codec.stop(); } catch (RuntimeException ignored) { }
        try { codec.release(); } catch (RuntimeException ignored) { }
        codec = null;
        configured = false;
    }
}
