package com.prateek.mitvremote.wifi;

import com.prateek.mitvremote.proto.Proto;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Android TV Remote v2 pairing (TLS port 6467).
 * Runs the handshake on a worker thread; the TV shows a 6-character code
 * which the user types in via {@link #submitCode(String)}.
 */
public class PairingClient {
    public static final int PORT = 6467;

    public interface Listener {
        void onCodeRequired();            // TV is showing the code - ask the user
        void onCodeError(String message); // wrong code - ask again
        void onPaired();                  // done
        void onError(String message);     // fatal
    }

    private final String host;
    private final CertManager certManager;
    private final Listener listener;
    private final BlockingQueue<byte[]> inbox = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> codeBox = new ArrayBlockingQueue<>(1);
    private volatile boolean aborted;
    private TlsConnection conn;

    public PairingClient(String host, CertManager certManager, Listener listener) {
        this.host = host;
        this.certManager = certManager;
        this.listener = listener;
    }

    public void start() {
        new Thread(this::run, "pairing").start();
    }

    public void submitCode(String code) {
        codeBox.offer(code == null ? "" : code);
    }

    public void abort() {
        aborted = true;
        codeBox.offer("");
        if (conn != null) conn.close();
    }

    private void run() {
        try {
            conn = new TlsConnection(certManager, new TlsConnection.Listener() {
                @Override public void onMessage(byte[] message) { inbox.offer(message); }
                @Override public void onClosed(Exception error) { inbox.offer(new byte[0]); }
            });
            conn.connect(host, PORT, 8000);

            sendAndWait(Proto.pairingRequest("Mi TV Remote", "atvremote"), 11, "pairing request");
            sendAndWait(Proto.pairingOption(), 20, "pairing option");
            sendAndWait(Proto.pairingConfiguration(), 31, "pairing configuration");

            // TV now shows the code.
            listener.onCodeRequired();
            while (!aborted) {
                String code = codeBox.poll(180, TimeUnit.SECONDS);
                if (aborted || code == null) return;
                byte[] alpha;
                try {
                    alpha = certManager.derivePairingSecret(code, conn.getSocket());
                } catch (IllegalArgumentException e) {
                    listener.onCodeError(e.getMessage());
                    continue;
                }
                try {
                    sendAndWait(Proto.pairingSecret(alpha), 41, "pairing secret");
                    listener.onPaired();
                    return;
                } catch (PairingException e) {
                    listener.onCodeError("TV rejected the code - try again");
                }
            }
        } catch (PairingException e) {
            listener.onError(e.getMessage());
        } catch (Exception e) {
            listener.onError(friendly(e));
        } finally {
            if (conn != null) conn.close();
        }
    }

    private void sendAndWait(byte[] msg, int expectField, String step) throws Exception {
        inbox.clear();
        conn.send(msg);
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            byte[] raw = inbox.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
            if (aborted) throw new PairingException("cancelled");
            if (raw == null) throw new PairingException("TV did not answer during " + step);
            if (raw.length == 0) throw new PairingException("Connection lost during " + step);
            List<Proto.Field> fields = Proto.parse(raw);
            long status = Proto.first(fields, 2) != null ? Proto.first(fields, 2).varint : -1;
            if (status != 200) throw new PairingException("TV refused " + step + " (status " + status + ")");
            if (Proto.first(fields, expectField) != null) return;
            // unexpected message - keep waiting
        }
        throw new PairingException("Timed out waiting for TV during " + step);
    }

    private static String friendly(Exception e) {
        String m = e.getMessage();
        if (m == null) m = e.toString();
        if (m.contains("ECONNREFUSED") || m.contains("refused")) return "TV refused the connection - is it on and on the same Wi-Fi?";
        if (m.contains("ETIMEDOUT") || m.contains("timed out")) return "Could not reach the TV - check the IP address";
        return m;
    }

    private static class PairingException extends Exception {
        PairingException(String m) { super(m); }
    }
}
