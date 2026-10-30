package org.hdvcam.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.hdvcam.protocol.aw.AwIoControlFrame;
import com.hdvcam.protocol.aw.AwLoginAck;
import com.hdvcam.protocol.aw.AwLoginRequest;
import com.hdvcam.protocol.aw.AwMediaFragment;

import org.junit.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;

public final class AwTcpSessionLoopbackTest {
    @Test public void logsInBindsUdpConnectsToAckPortAndSendsStartStopCommands() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        int selector = 7;
        try (ServerSocket server = new ServerSocket(0, 1, loopback);
             DatagramSocket cameraMedia = new DatagramSocket(
                     new InetSocketAddress(loopback, AwLoginAck.UDP_PORT_BASE + selector))) {
            AtomicReference<Throwable> serverFailure = new AtomicReference<>();
            Thread camera = new Thread(() -> {
                try (Socket peer = server.accept()) {
                    peer.setSoTimeout(3000);
                    DataInputStream input = new DataInputStream(peer.getInputStream());
                    DataOutputStream output = new DataOutputStream(peer.getOutputStream());
                    byte[] login = new byte[AwLoginRequest.FRAME_LENGTH];
                    input.readFully(login);
                    assertEquals(AwLoginRequest.LOGIN_TYPE, readType(login));
                    assertEquals(0, login[8 + AwLoginRequest.SESSION_INDEX_OFFSET] & 0xff);

                    byte[] ack = new byte[AwLoginRequest.FRAME_LENGTH];
                    ack[0] = (byte) 0xab; ack[1] = (byte) 0xcd;
                    ack[2] = 0; ack[3] = (byte) AwLoginRequest.BODY_LENGTH;
                    ack[4] = 0; ack[5] = 0; ack[6] = 1; ack[7] = 0x11;
                    ack[8 + AwLoginRequest.SESSION_INDEX_OFFSET] = (byte) selector;
                    output.write(ack);
                    output.flush();

                    byte[] start = readFrame(input);
                    assertEquals(AwIoControlFrame.TYPE_START_VIDEO, AwIoControlFrame.parse(start).type());
                    assertArrayEquals(new byte[8], AwIoControlFrame.parse(start).payload());
                    byte[] stop = readFrame(input);
                    assertEquals(AwIoControlFrame.TYPE_STOP_VIDEO, AwIoControlFrame.parse(stop).type());
                    assertArrayEquals(new byte[8], AwIoControlFrame.parse(stop).payload());
                } catch (Throwable failure) {
                    serverFailure.set(failure);
                }
            }, "aw-loopback-camera");
            camera.start();

            DatagramSocket phoneMedia = new DatagramSocket(null);
            phoneMedia.bind(new InetSocketAddress(AwLoginAck.UDP_PORT_BASE));
            Socket phoneControl = new Socket(loopback, server.getLocalPort());
            AwTcpSession session = AwTcpSession.completeLogin("test-device", loopback.getHostAddress(),
                    phoneControl, phoneMedia);
            assertTrue(session.isConnected());
            assertEquals(AwLoginAck.UDP_PORT_BASE + selector, session.udpPeerPort());
            assertEquals(selector, session.udpSelector());
            assertEquals(phoneMedia.getLocalPort(), session.localMediaPort());
            assertTrue("UDP receive buffer did not reach the native 1 MiB setting",
                    session.mediaReceiveBufferBytes() >= 1024 * 1024);

            byte[] malformedMediaProbe = {0, (byte) 0xde, 0, 0, 0, 1, 0, 0};
            cameraMedia.send(new DatagramPacket(malformedMediaProbe, malformedMediaProbe.length,
                    loopback, session.localMediaPort()));
            assertTrue("ACK-selected UDP peer did not reach the media receiver",
                    waitFor(() -> session.malformedMediaPackets() == 1, 1500));

            java.io.ByteArrayOutputStream framedPayload = new java.io.ByteArrayOutputStream();
            framedPayload.write(new byte[16]);
            framedPayload.write(new byte[]{0, 0, 0, 1, 0x65, 0x11, 0x22});
            byte[] mediaPacket = encodeVideoFragment(1, 0, framedPayload.toByteArray(), AwMediaFragment.VIDEO_START);
            cameraMedia.send(new DatagramPacket(mediaPacket, mediaPacket.length,
                    loopback, session.localMediaPort()));
            assertTrue("A candidate Annex-B payload was not delivered after its 16-byte prefix",
                    waitFor(() -> session.deliveredVideoPayloads() == 1, 1500));
            assertEquals(1, session.receivedVideoFrames());
            assertEquals(1, session.deliveredVideoPayloads());
            assertArrayEquals(new byte[]{0, 0, 0, 1, 0x65, 0x11, 0x22}, session.lastVideoFrame());

            byte[] heartbeat = encodeVideoFragment(99, 0, new byte[0], AwMediaFragment.HEARTBEAT);
            cameraMedia.send(new DatagramPacket(heartbeat, heartbeat.length, loopback, session.localMediaPort()));
            byte[] audioPayload = new byte[17];
            byte[] audio = encodeVideoFragment(2, 0, audioPayload, AwMediaFragment.AUDIO_START);
            cameraMedia.send(new DatagramPacket(audio, audio.length, loopback, session.localMediaPort()));
            java.io.ByteArrayOutputStream secondPayload = new java.io.ByteArrayOutputStream();
            secondPayload.write(new byte[16]);
            secondPayload.write(new byte[]{0, 0, 1, 0x41, 0x33});
            byte[] secondVideo = encodeVideoFragment(3, 0, secondPayload.toByteArray(), AwMediaFragment.VIDEO_START);
            cameraMedia.send(new DatagramPacket(secondVideo, secondVideo.length, loopback, session.localMediaPort()));
            assertTrue("Heartbeat/audio/video sequence was not consumed", waitFor(
                    () -> session.receivedHeartbeats() == 1 && session.receivedAudioFrames() == 1
                            && session.deliveredVideoPayloads() == 2, 1500));
            assertEquals("Heartbeat must not advance the shared media sequence", 0, session.mediaSequenceGaps());
            assertEquals(1, session.lastAudioFrameBytes());

            session.startVideo();
            assertTrue(session.isVideoStartRequested());
            session.stopVideo();
            assertFalse(session.isVideoStartRequested());
            session.close();
            camera.join(4000);
            assertFalse("Loopback camera thread did not finish", camera.isAlive());
            if (serverFailure.get() != null) throw new AssertionError(serverFailure.get());
        }
    }

    @Test public void hostJvmCanReceiveUdpOnLoopback() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (DatagramSocket receiver = new DatagramSocket(new InetSocketAddress(loopback, 0));
             DatagramSocket sender = new DatagramSocket(new InetSocketAddress(loopback, 0))) {
            receiver.setSoTimeout(1000);
            sender.send(new DatagramPacket(new byte[]{1}, 1, loopback, receiver.getLocalPort()));
            byte[] buffer = new byte[8];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            receiver.receive(packet);
            assertEquals(1, packet.getLength());
        }
    }

    @Test public void detectsRemoteTcpCloseAsSessionLoss() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (ServerSocket server = new ServerSocket(0, 1, loopback);
             DatagramSocket phoneMedia = new DatagramSocket(null)) {
            phoneMedia.bind(new InetSocketAddress(AwLoginAck.UDP_PORT_BASE));
            AtomicReference<Throwable> serverFailure = new AtomicReference<>();
            Thread camera = new Thread(() -> {
                try (Socket peer = server.accept()) {
                    DataInputStream input = new DataInputStream(peer.getInputStream());
                    DataOutputStream output = new DataOutputStream(peer.getOutputStream());
                    byte[] login = new byte[AwLoginRequest.FRAME_LENGTH];
                    input.readFully(login);
                    byte[] ack = new byte[AwLoginRequest.FRAME_LENGTH];
                    ack[0] = (byte) 0xab; ack[1] = (byte) 0xcd;
                    ack[2] = 0; ack[3] = (byte) AwLoginRequest.BODY_LENGTH;
                    ack[4] = 0; ack[5] = 0; ack[6] = 1; ack[7] = 0x11;
                    output.write(ack);
                    output.flush();
                } catch (Throwable failure) {
                    serverFailure.set(failure);
                }
            }, "aw-loopback-remote-close");
            camera.start();

            AwTcpSession session = AwTcpSession.completeLogin("test-device", loopback.getHostAddress(),
                    new Socket(loopback, server.getLocalPort()), phoneMedia);
            assertTrue(session.isConnected());
            camera.join(3000);
            assertFalse("Loopback camera thread did not close TCP", camera.isAlive());
            assertTrue("Remote TCP close was not reflected in session state", waitFor(() -> !session.isConnected(), 1500));
            if (serverFailure.get() != null) throw new AssertionError(serverFailure.get());
            session.close();
        }
    }

    private interface Condition { boolean isTrue(); }

    private static boolean waitFor(Condition condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.isTrue()) return true;
            Thread.sleep(10);
        }
        return condition.isTrue();
    }

    private static int readType(byte[] frame) {
        return ((frame[4] & 0xff) << 24) | ((frame[5] & 0xff) << 16)
                | ((frame[6] & 0xff) << 8) | (frame[7] & 0xff);
    }

    private static byte[] encodeVideoFragment(int sequence, int channel, byte[] payload, int marker) {
        byte[] packet = new byte[AwMediaFragment.HEADER_LENGTH + payload.length];
        packet[0] = (byte) 0xbc; packet[1] = (byte) 0xde;
        packet[2] = (byte) (payload.length >>> 8); packet[3] = (byte) payload.length;
        packet[4] = (byte) (sequence >>> 8); packet[5] = (byte) sequence;
        packet[6] = (byte) channel; packet[7] = (byte) marker;
        System.arraycopy(payload, 0, packet, AwMediaFragment.HEADER_LENGTH, payload.length);
        return packet;
    }

    private static byte[] readFrame(DataInputStream input) throws Exception {
        int magic = input.readUnsignedShort();
        int length = input.readUnsignedShort();
        int type = input.readInt();
        byte[] frame = new byte[8 + length];
        frame[0] = (byte) (magic >>> 8); frame[1] = (byte) magic;
        frame[2] = (byte) (length >>> 8); frame[3] = (byte) length;
        frame[4] = (byte) (type >>> 24); frame[5] = (byte) (type >>> 16);
        frame[6] = (byte) (type >>> 8); frame[7] = (byte) type;
        input.readFully(frame, 8, length);
        return frame;
    }
}
