package com.prateek.mitvremote.wifi;

import com.prateek.mitvremote.proto.Proto;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * TLS socket with varint-length-prefixed protobuf framing.
 * A reader thread delivers complete messages to the listener.
 */
public class TlsConnection {
    public interface Listener {
        void onMessage(byte[] message);
        void onClosed(Exception error); // error null => clean close
    }

    private final CertManager certManager;
    private final Listener listener;
    private SSLSocket socket;
    private OutputStream out;
    private Thread readerThread;
    private volatile boolean closed;

    public TlsConnection(CertManager certManager, Listener listener) {
        this.certManager = certManager;
        this.listener = listener;
    }

    /** Connect + TLS handshake. Throws on failure. */
    public void connect(String host, int port, int timeoutMs) throws Exception {
        SSLSocketFactory factory = certManager.getSslContext().getSocketFactory();
        SSLSocket s = (SSLSocket) factory.createSocket();
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
        s.connect(new InetSocketAddress(host, port), timeoutMs);
        s.setSoTimeout(timeoutMs); // bounds the handshake read
        try {
            s.startHandshake();
        } finally {
            s.setSoTimeout(0);
        }
        this.socket = s;
        this.out = s.getOutputStream();
        startReader(s.getInputStream());
    }

    public SSLSocket getSocket() { return socket; }

    private void startReader(InputStream in) {
        readerThread = new Thread(() -> {
            try {
                while (!closed) {
                    byte[] msg = readFrame(in);
                    listener.onMessage(msg);
                }
            } catch (Exception e) {
                if (!closed) listener.onClosed(e);
            }
        }, "tls-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private static byte[] readFrame(InputStream in) throws IOException {
        long len = 0;
        int shift = 0;
        while (true) {
            int b = in.read();
            if (b < 0) throw new EOFException("stream closed");
            len |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
            if (shift > 63) throw new IOException("varint too long");
        }
        if (len > 8 * 1024 * 1024) throw new IOException("frame too large: " + len);
        byte[] buf = new byte[(int) len];
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new EOFException("stream closed mid-frame");
            off += n;
        }
        return buf;
    }

    /** Send one protobuf message (framing added). Thread-safe. */
    public synchronized void send(byte[] message) throws IOException {
        if (closed || out == null) throw new IOException("not connected");
        out.write(Proto.frame(message));
        out.flush();
    }

    public synchronized void close() {
        if (closed) return;
        closed = true;
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        // reader thread exits on its own via the closed flag / socket close
    }
}
