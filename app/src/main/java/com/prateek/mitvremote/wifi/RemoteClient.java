package com.prateek.mitvremote.wifi;

import com.prateek.mitvremote.proto.Proto;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Android TV Remote v2 remote session (TLS port 6466).
 * Performs the configure/set-active/start handshake, answers pings,
 * and sends key/text/app-link commands.
 */
public class RemoteClient {
    public static final int PORT = 6466;
    /** PING | KEY | IME | POWER | VOLUME | APP_LINK */
    private static final int FEATURES = 1 + 2 + 4 + 32 + 64 + 512;

    public interface Listener {
        void onReady(boolean tvOn);
        void onDisconnected(String reason);
        void onVolume(int level, int max, boolean muted);
    }

    // Android keycode values (match the RemoteKeyCode enum)
    public static final int KEY_HOME = 3, KEY_BACK = 4;
    public static final int KEY_DPAD_UP = 19, KEY_DPAD_DOWN = 20, KEY_DPAD_LEFT = 21,
            KEY_DPAD_RIGHT = 22, KEY_DPAD_CENTER = 23;
    public static final int KEY_VOLUME_UP = 24, KEY_VOLUME_DOWN = 25, KEY_POWER = 26;
    public static final int KEY_ENTER = 66;
    public static final int KEY_MEDIA_PLAY_PAUSE = 85, KEY_MEDIA_NEXT = 87, KEY_MEDIA_PREVIOUS = 88;
    public static final int KEY_MUTE = 164, KEY_VOLUME_MUTE = 164;
    public static final int KEY_SETTINGS = 176, KEY_GUIDE = 172;

    private final String host;
    private final CertManager certManager;
    private final Listener listener;
    private final BlockingQueue<byte[]> inbox = new LinkedBlockingQueue<>();
    private final CountDownLatch startedLatch = new CountDownLatch(1);
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private volatile boolean closed;
    private TlsConnection conn;
    private int features = FEATURES;
    private volatile int imeCounter = 0, imeFieldCounter = 0;

    public RemoteClient(String host, CertManager certManager, Listener listener) {
        this.host = host;
        this.certManager = certManager;
        this.listener = listener;
    }

    /** Connect and run the handshake. Blocks until remote_start or timeout. */
    public void connect() throws Exception {
        conn = new TlsConnection(certManager, new TlsConnection.Listener() {
            @Override public void onMessage(byte[] message) { handleMessage(message); }
            @Override public void onClosed(Exception error) {
                if (!closed) {
                    closed = true;
                    startedLatch.countDown();
                    listener.onDisconnected(error == null ? "disconnected" : error.getMessage());
                }
            }
        });
        conn.connect(host, PORT, 8000);
        if (!startedLatch.await(12, TimeUnit.SECONDS) || !ready.get()) {
            close();
            throw new NotPairedException("TV did not start the remote session");
        }
    }

    private void handleMessage(byte[] raw) {
        List<Proto.Field> fields;
        try {
            fields = Proto.parse(raw);
        } catch (Exception e) {
            return;
        }
        try {
            Proto.Field f;
            if ((f = Proto.first(fields, Proto.R_CONFIGURE)) != null) {
                List<Proto.Field> inner = Proto.parse(f.bytes);
                Proto.Field code1 = Proto.first(inner, Proto.RC_CODE1);
                int supported = code1 != null ? (int) code1.varint : FEATURES;
                features = FEATURES & supported;
                conn.send(Proto.remoteConfigure(features, "atvremote", "1.0.0"));
            } else if ((f = Proto.first(fields, Proto.R_SET_ACTIVE)) != null) {
                conn.send(Proto.remoteSetActive(features));
            } else if ((f = Proto.first(fields, Proto.R_START)) != null) {
                boolean started = Proto.varintOf(f.bytes, Proto.ST_STARTED) == 1;
                if (ready.compareAndSet(false, true)) {
                    startedLatch.countDown();
                    listener.onReady(started);
                }
            } else if ((f = Proto.first(fields, Proto.R_PING_REQUEST)) != null) {
                long val1 = Proto.varintOf(f.bytes, Proto.PING_VAL1);
                conn.send(Proto.remotePingResponse((int) val1));
            } else if ((f = Proto.first(fields, Proto.R_IME_BATCH_EDIT)) != null) {
                long c = Proto.varintOf(f.bytes, Proto.IME_COUNTER);
                long fc = Proto.varintOf(f.bytes, Proto.IME_FIELD_COUNTER);
                if (c >= 0) imeCounter = (int) c;
                if (fc >= 0) imeFieldCounter = (int) fc;
            } else if ((f = Proto.first(fields, Proto.R_SET_VOLUME_LEVEL)) != null) {
                long max = Proto.varintOf(f.bytes, Proto.VOL_MAX);
                long level = Proto.varintOf(f.bytes, Proto.VOL_LEVEL);
                long muted = Proto.varintOf(f.bytes, Proto.VOL_MUTED);
                if (max >= 0 && level >= 0)
                    listener.onVolume((int) level, (int) max, muted == 1);
            } else if (Proto.first(fields, 3) != null) {
                // remote_error - ignore, TV will close if fatal
            }
            inbox.offer(raw);
        } catch (Exception e) {
            // best effort; a failed response must not kill the session
        }
    }

    public boolean isReady() { return ready.get() && !closed; }

    public void sendKey(int keyCode) throws Exception {
        if (!isReady()) throw new IllegalStateException("not connected");
        conn.send(Proto.remoteKeyInject(keyCode, Proto.DIR_SHORT));
    }

    public void sendText(String text) throws Exception {
        if (!isReady()) throw new IllegalStateException("not connected");
        if (text == null || text.isEmpty()) return;
        conn.send(Proto.remoteImeText(text, imeCounter, imeFieldCounter));
    }

    public void launchApp(String appLink) throws Exception {
        if (!isReady()) throw new IllegalStateException("not connected");
        conn.send(Proto.remoteAppLink(appLink));
    }

    public void close() {
        closed = true;
        startedLatch.countDown();
        if (conn != null) conn.close();
    }

    public static class NotPairedException extends Exception {
        NotPairedException(String m) { super(m); }
    }
}
