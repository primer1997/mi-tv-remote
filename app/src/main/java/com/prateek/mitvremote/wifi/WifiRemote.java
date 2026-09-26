package com.prateek.mitvremote.wifi;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;

/**
 * High-level Wi-Fi transport: connect (pairing when needed), send keys/text, scan LAN.
 * All callbacks are delivered on the main thread.
 */
public class WifiRemote {
    public interface Callback {
        void onStatus(String status);
        void onConnected(String host);
        void onPairingCodeRequired();
        void onPairingCodeError(String message);
        void onDisconnected(String reason);
        void onError(String message);
    }

    public interface ScanListener {
        void onFound(String ip);
        void onDone();
    }

    private static final String PREFS = "mitvremote";
    private static final String KEY_LAST_IP = "last_ip";

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newCachedThreadPool();
    private final CertManager certManager = new CertManager();

    private volatile RemoteClient remote;
    private volatile PairingClient pairing;
    private volatile String host;
    private final AtomicBoolean connecting = new AtomicBoolean(false);

    public WifiRemote(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public String lastIp() {
        return appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST_IP, "");
    }

    private void saveIp(String ip) {
        SharedPreferences.Editor e = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.putString(KEY_LAST_IP, ip);
        e.apply();
    }

    public boolean isConnected() {
        RemoteClient r = remote;
        return r != null && r.isReady();
    }

    public void connect(String ip, Callback cb) {
        if (!connecting.compareAndSet(false, true)) return;
        net.execute(() -> {
            try {
                certManager.ensureKey();
            } catch (Exception e) {
                fail(cb, "Could not create security key: " + e.getMessage());
                return;
            }
            post(() -> cb.onStatus("Connecting to " + ip + "..."));
            host = ip;
            try {
                openRemoteSession(cb);
                saveIp(ip);
                post(() -> cb.onConnected(ip));
            } catch (RemoteClient.NotPairedException e) {
                doPairing(cb);
            } catch (Exception e) {
                if (isTlsFailure(e)) {
                    doPairing(cb);
                } else {
                    fail(cb, friendlyConnectError(e));
                }
            } finally {
                connecting.set(false);
            }
        });
    }

    private void openRemoteSession(Callback cb) throws Exception {
        closeRemote();
        RemoteClient rc = new RemoteClient(host, certManager, new RemoteClient.Listener() {
            @Override public void onReady(boolean tvOn) {}
            @Override public void onDisconnected(String reason) {
                remote = null;
                post(() -> cb.onDisconnected(reason));
            }
            @Override public void onVolume(int level, int max, boolean muted) {}
        });
        rc.connect(); // throws NotPairedException when the TV won't start the session
        remote = rc;
    }

    private void doPairing(Callback cb) {
        post(() -> cb.onStatus("Pairing with TV..."));
        pairing = new PairingClient(host, certManager, new PairingClient.Listener() {
            @Override public void onCodeRequired() { post(cb::onPairingCodeRequired); }
            @Override public void onCodeError(String m) { post(() -> cb.onPairingCodeError(m)); }
            @Override public void onPaired() {
                net.execute(() -> {
                    try {
                        post(() -> cb.onStatus("Paired! Connecting..."));
                        openRemoteSession(cb);
                        saveIp(host);
                        post(() -> cb.onConnected(host));
                    } catch (Exception e) {
                        fail(cb, "Paired, but could not start remote session: " + friendlyConnectError(e));
                    }
                });
            }
            @Override public void onError(String message) { fail(cb, message); }
        });
        pairing.start();
    }

    public void submitPairingCode(String code) {
        PairingClient p = pairing;
        if (p != null) p.submitCode(code);
    }

    public void cancelPairing() {
        PairingClient p = pairing;
        if (p != null) p.abort();
        pairing = null;
    }

    private void fail(Callback cb, String message) {
        connecting.set(false);
        post(() -> cb.onError(message));
    }

    private void post(Runnable r) { main.post(r); }

    private static boolean isTlsFailure(Throwable e) {
        while (e != null) {
            if (e instanceof SSLException) return true;
            e = e.getCause();
        }
        return false;
    }

    private static String friendlyConnectError(Exception e) {
        String m = e.getMessage() == null ? e.toString() : e.getMessage();
        if (m.contains("ENETUNREACH") || m.contains("EHOSTUNREACH"))
            return "TV unreachable - is your phone on the same Wi-Fi as the TV?";
        if (m.contains("ECONNREFUSED"))
            return "TV refused the connection - is it powered on?";
        if (m.contains("ETIMEDOUT") || m.contains("timed out") || m.contains("Timeout"))
            return "Timed out - check the IP address and that the TV is on";
        return m;
    }

    public void sendKey(int keyCode) {
        net.execute(() -> {
            try {
                RemoteClient r = remote;
                if (r != null) r.sendKey(keyCode);
            } catch (Exception ignored) {}
        });
    }

    public void sendText(String text) {
        net.execute(() -> {
            try {
                RemoteClient r = remote;
                if (r != null) r.sendText(text);
            } catch (Exception ignored) {}
        });
    }

    public void disconnect() {
        cancelPairing();
        closeRemote();
    }

    private void closeRemote() {
        RemoteClient r = remote;
        remote = null;
        if (r != null) r.close();
    }

    /** Scan the local /24 for hosts with the Android TV remote TLS port open. */
    public void scan(ScanListener listener) {
        net.execute(() -> {
            try {
                WifiManager wm = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
                if (wm == null || !wm.isWifiEnabled()) { post(listener::onDone); return; }
                DhcpInfo d = wm.getDhcpInfo();
                int base = d.ipAddress & d.netmask;
                int own = d.ipAddress;
                ExecutorService pool = Executors.newFixedThreadPool(48);
                AtomicBoolean done = new AtomicBoolean(false);
                for (int i = 1; i < 255; i++) {
                    final int addr = (base & 0xFFFFFF00) | i;
                    if (addr == own || i == 255) continue;
                    pool.execute(() -> probe(addr, listener));
                }
                pool.shutdown();
                pool.awaitTermination(25, TimeUnit.SECONDS);
                if (done.compareAndSet(false, true)) post(listener::onDone);
            } catch (Exception e) {
                post(listener::onDone);
            }
        });
    }

    private void probe(int addr, ScanListener listener) {
        String ip = ((addr) & 0xFF) + "." + ((addr >> 8) & 0xFF) + "."
                + ((addr >> 16) & 0xFF) + "." + ((addr >> 24) & 0xFF);
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, RemoteClient.PORT), 350);
        } catch (Exception e) {
            return; // port closed
        }
        // Port open - verify it's the TV remote TLS service.
        try {
            SSLSocket tls = (SSLSocket) certManager.getSslContext().getSocketFactory().createSocket();
            tls.connect(new InetSocketAddress(ip, RemoteClient.PORT), 2500);
            tls.setSoTimeout(2500);
            try {
                tls.startHandshake();
                if (tls.getSession().getPeerCertificates().length > 0)
                    post(() -> listener.onFound(ip));
            } finally {
                tls.close();
            }
        } catch (Exception ignored) {}
    }
}
