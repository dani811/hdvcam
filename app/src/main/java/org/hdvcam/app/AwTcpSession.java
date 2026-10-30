package org.hdvcam.app;

import android.net.Network;

import com.hdvcam.protocol.aw.AwDiscoveryResponse;
import com.hdvcam.protocol.aw.AwLoginAck;
import com.hdvcam.protocol.aw.AnnexB;
import com.hdvcam.protocol.aw.AwLoginRequest;
import com.hdvcam.protocol.aw.AwIoControlFrame;
import com.hdvcam.protocol.aw.AwFrameAssembler;
import com.hdvcam.protocol.aw.AwMediaFragment;
import com.hdvcam.protocol.aw.AwMediaSequenceTracker;
import com.hdvcam.protocol.aw.AwNetCoreMediaFrame;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Socket;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.net.SocketFactory;

/** Holds the AW TCP control session after the statically observed login exchange. */
public final class AwTcpSession implements Closeable {
    public interface VideoListener { void onVideoFrame(byte[] frame); }
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int LOGIN_TIMEOUT_MS = 5000;
    private static final int MEDIA_RECEIVE_BUFFER_BYTES = 1024 * 1024;
    private final String uid;
    private final String address;
    private final Socket socket;
    private final AwLoginAck loginAck;
    private final DatagramSocket mediaSocket;
    private final AwFrameAssembler videoAssembler = new AwFrameAssembler(true);
    private final AwFrameAssembler audioAssembler = new AwFrameAssembler(false);
    private final AwMediaSequenceTracker mediaSequenceTracker = new AwMediaSequenceTracker();
    private volatile boolean closed;
    private volatile int lastInboundType = -1;
    private volatile boolean videoStartRequested;
    private volatile long receivedVideoFrames;
    private volatile int lastVideoFrameBytes;
    private volatile long receivedAudioPackets;
    private volatile long receivedAudioFrames;
    private volatile int lastAudioFrameBytes;
    private volatile long receivedUnknownMediaPackets;
    private volatile long receivedHeartbeats;
    private volatile long malformedMediaPackets;
    private volatile long lastMediaPacketAt;
    private volatile long receivedVideoPackets;
    private volatile long deliveredVideoPayloads;
    private volatile long rejectedVideoFrames;
    private volatile byte[] lastVideoFrame;
    private final CopyOnWriteArrayList<VideoListener> videoListeners = new CopyOnWriteArrayList<>();

    private AwTcpSession(String uid, String address, Socket socket, AwLoginAck loginAck, DatagramSocket mediaSocket) {
        this.uid = uid;
        this.address = address;
        this.socket = socket;
        this.loginAck = loginAck;
        this.mediaSocket = mediaSocket;
        Thread reader = new Thread(this::drainInboundFrames, "hdvcam-aw-tcp-reader");
        reader.setDaemon(true);
        reader.start();
        Thread mediaReader = new Thread(this::receiveMedia, "hdvcam-aw-media-reader");
        mediaReader.setDaemon(true);
        mediaReader.start();
    }

    /** Repeats LAN discovery to mirror native UID verification, then performs TCP login. */
    public static AwTcpSession connect(android.content.Context context, Network network,
                                       String uid) throws Exception {
        AwDiscoveryResponse device = null;
        for (AwDiscoveryResponse candidate : AwLanDiscovery.search(context, network)) {
            if (uid.equals(candidate.uid())) { device = candidate; break; }
        }
        if (device == null) throw new IOException("La UID AW ya no aparece en la búsqueda de red.");

        SocketFactory factory = network == null ? SocketFactory.getDefault() : network.getSocketFactory();
        Socket socket = factory.createSocket();
        DatagramSocket mediaSocket = new DatagramSocket(null);
        try {
            mediaSocket.setReuseAddress(false);
            mediaSocket.setReceiveBufferSize(MEDIA_RECEIVE_BUFFER_BYTES);
            mediaSocket.bind(new InetSocketAddress(AwLoginAck.UDP_PORT_BASE));
            // Pin after bind: Android requires Network.bindSocket() to receive
            // a socket with a local endpoint, matching the native SID+6669 bind.
            if (network != null) network.bindSocket(mediaSocket);
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(device.sourceAddress(), AwLoginRequest.TCP_PORT), CONNECT_TIMEOUT_MS);
            return completeLogin(uid, device.sourceAddress(), socket, mediaSocket);
        } catch (Exception failure) {
            mediaSocket.close();
            try { socket.close(); } catch (IOException ignored) { }
            throw failure;
        }
    }

    /** Performs the wire login on already-connected sockets; package-visible for loopback protocol tests. */
    static AwTcpSession completeLogin(String uid, String address, Socket socket,
                                      DatagramSocket mediaSocket) throws Exception {
        try {
            // UdpClient::create_UDP_socket sets SO_RCVBUF to 1 MiB before bind.
            mediaSocket.setReceiveBufferSize(MEDIA_RECEIVE_BUFFER_BYTES);
            if (!mediaSocket.isBound()) mediaSocket.bind(new InetSocketAddress(AwLoginAck.UDP_PORT_BASE));
            socket.setSoTimeout(LOGIN_TIMEOUT_MS);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.write(AwLoginRequest.create(0));
            output.flush();

            DataInputStream input = new DataInputStream(socket.getInputStream());
            int magic = input.readUnsignedShort();
            int bodyLength = input.readUnsignedShort();
            int messageType = input.readInt();
            if (magic != 0xabcd || bodyLength != AwLoginRequest.BODY_LENGTH
                    || messageType != AwLoginAck.ACK_TYPE) {
                throw new IOException("La cámara devolvió una respuesta TCP AW inesperada (tipo 0x"
                        + Integer.toHexString(messageType) + ").");
            }
            byte[] frame = new byte[AwLoginRequest.FRAME_LENGTH];
            frame[0] = (byte) (magic >>> 8); frame[1] = (byte) magic;
            frame[2] = (byte) (bodyLength >>> 8); frame[3] = (byte) bodyLength;
            frame[4] = (byte) (messageType >>> 24); frame[5] = (byte) (messageType >>> 16);
            frame[6] = (byte) (messageType >>> 8); frame[7] = (byte) messageType;
            input.readFully(frame, 8, bodyLength);
            AwLoginAck ack = AwLoginAck.parse(frame);
            socket.setSoTimeout(1000);
            mediaSocket.connect(new InetSocketAddress(address, ack.udpPeerPort()));
            mediaSocket.setSoTimeout(1000);
            return new AwTcpSession(uid, address, socket, ack, mediaSocket);
        } catch (Exception failure) {
            mediaSocket.close();
            try { socket.close(); } catch (IOException ignored) { }
            throw failure;
        }
    }

    /** Sends the statically observed AW start-video IOControl over the authenticated TCP session. */
    public synchronized void startVideo() throws IOException {
        if (closed) throw new IOException("La sesión AW está cerrada.");
        if (videoStartRequested) return;
        byte[] command = AwIoControlFrame.encode(AwIoControlFrame.TYPE_START_VIDEO, new byte[8]);
        DataOutputStream output = new DataOutputStream(socket.getOutputStream());
        output.write(command);
        output.flush();
        videoStartRequested = true;
    }

    public synchronized void stopVideo() throws IOException {
        if (!videoStartRequested || closed) return;
        byte[] command = AwIoControlFrame.encode(AwIoControlFrame.TYPE_STOP_VIDEO, new byte[8]);
        DataOutputStream output = new DataOutputStream(socket.getOutputStream());
        output.write(command);
        output.flush();
        videoStartRequested = false;
        videoAssembler.reset();
    }

    private void receiveMedia() {
        byte[] buffer = new byte[2048];
        while (!closed) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                mediaSocket.receive(packet);
                lastMediaPacketAt = System.nanoTime() / 1_000_000L;
                byte[] bytes = java.util.Arrays.copyOfRange(packet.getData(), packet.getOffset(), packet.getOffset() + packet.getLength());
                AwMediaFragment fragment = AwMediaFragment.parse(bytes);
                mediaSequenceTracker.observe(fragment);
                if (fragment.isHeartbeat()) {
                    receivedHeartbeats++;
                    continue;
                }
                if (fragment.isVideo()) {
                    receivedVideoPackets++;
                    byte[] frame = videoAssembler.accept(fragment);
                    if (frame != null) {
                        try {
                            byte[] payload = AwNetCoreMediaFrame.payload(frame);
                            receivedVideoFrames++;
                            if (!AnnexB.containsPlausibleNal(payload)) {
                                rejectedVideoFrames++;
                                malformedMediaPackets++;
                                continue;
                            }
                            deliveredVideoPayloads++;
                            lastVideoFrame = payload;
                            lastVideoFrameBytes = payload.length;
                            for (VideoListener listener : videoListeners) listener.onVideoFrame(payload.clone());
                        } catch (IllegalArgumentException malformedFrame) {
                            rejectedVideoFrames++;
                            malformedMediaPackets++;
                        }
                    }
                    continue;
                }
                if (fragment.isAudio()) {
                    receivedAudioPackets++;
                    byte[] audioFrame = audioAssembler.accept(fragment);
                    if (audioFrame != null) {
                        try {
                            byte[] payload = AwNetCoreMediaFrame.payload(audioFrame);
                            lastAudioFrameBytes = payload.length;
                            receivedAudioFrames++;
                        } catch (IllegalArgumentException malformedFrame) {
                            malformedMediaPackets++;
                        }
                    }
                    continue;
                }
                receivedUnknownMediaPackets++;
            } catch (java.net.SocketTimeoutException timeout) {
                // Check session close state periodically.
            } catch (IllegalArgumentException malformed) {
                malformedMediaPackets++;
                videoAssembler.reset();
                audioAssembler.reset();
            } catch (IOException disconnected) {
                if (!closed) android.util.Log.w("HDVCamAW", "UDP de medios cerrado", disconnected);
            }
        }
    }

    private void drainInboundFrames() {
        try {
            DataInputStream input = new DataInputStream(socket.getInputStream());
            while (!closed) {
                try {
                    int magic = input.readUnsignedShort();
                    int bodyLength = input.readUnsignedShort();
                    int messageType = input.readInt();
                    if (magic != 0xabcd) throw new IOException("Magic AW inválido en el canal TCP.");
                    lastInboundType = messageType;
                    int remaining = bodyLength;
                    byte[] discard = new byte[Math.min(1024, Math.max(1, bodyLength))];
                    while (remaining > 0) {
                        int read = input.read(discard, 0, Math.min(discard.length, remaining));
                        if (read < 0) throw new EOFException("AW cerró la sesión TCP.");
                        remaining -= read;
                    }
                } catch (java.net.SocketTimeoutException timeout) {
                    // Poll close state while leaving the TCP session available for later control work.
                }
            }
        } catch (IOException disconnected) {
            closed = true;
        }
    }

    public String uid() { return uid; }
    public String address() { return address; }
    /** Remote camera media port selected by the 0x111 ACK. */
    public int udpPeerPort() { return loginAck.udpPeerPort(); }
    public int udpSelector() { return loginAck.udpSelector(); }
    public int lastInboundType() { return lastInboundType; }
    public boolean isVideoStartRequested() { return videoStartRequested; }
    public long receivedVideoFrames() { return receivedVideoFrames; }
    /** Candidate H.264 Annex-B payloads delivered to listeners; hardware format remains unverified. */
    public long deliveredVideoPayloads() { return deliveredVideoPayloads; }
    public long rejectedVideoFrames() { return rejectedVideoFrames; }
    public int lastVideoFrameBytes() { return lastVideoFrameBytes; }
    public byte[] lastVideoFrame() {
        byte[] frame = lastVideoFrame;
        return frame == null ? null : frame.clone();
    }
    public long receivedAudioPackets() { return receivedAudioPackets; }
    public long receivedAudioFrames() { return receivedAudioFrames; }
    public int lastAudioFrameBytes() { return lastAudioFrameBytes; }
    public long receivedVideoPackets() { return receivedVideoPackets; }
    public long mediaSequenceGaps() { return mediaSequenceTracker.gaps(); }
    public long receivedUnknownMediaPackets() { return receivedUnknownMediaPackets; }
    public long receivedHeartbeats() { return receivedHeartbeats; }
    public long malformedMediaPackets() { return malformedMediaPackets; }
    public long lastMediaPacketAt() { return lastMediaPacketAt; }
    public long mediaPacketAgeMs() {
        long packetAt = lastMediaPacketAt;
        return packetAt == 0 ? -1 : Math.max(0, System.nanoTime() / 1_000_000L - packetAt);
    }
    public int localMediaPort() { return mediaSocket.getLocalPort(); }
    public int mediaReceiveBufferBytes() {
        try { return mediaSocket.getReceiveBufferSize(); }
        catch (java.net.SocketException unavailable) { return -1; }
    }
    public void addVideoListener(VideoListener listener) {
        if (listener == null) return;
        videoListeners.addIfAbsent(listener);
        byte[] latest = lastVideoFrame;
        if (latest != null) listener.onVideoFrame(latest.clone());
    }
    public void removeVideoListener(VideoListener listener) { videoListeners.remove(listener); }
    public boolean isConnected() { return !closed && socket.isConnected() && !socket.isClosed(); }

    @Override public void close() throws IOException {
        try { stopVideo(); } catch (IOException ignored) { }
        closed = true;
        mediaSocket.close();
        socket.close();
    }
}
