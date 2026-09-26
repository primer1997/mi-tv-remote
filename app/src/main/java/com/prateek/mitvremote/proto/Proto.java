package com.prateek.mitvremote.proto;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Minimal protobuf codec for the Android TV Remote v2 protocol (no dependencies). */
public class Proto {
    // wire types
    public static final int VARINT = 0, FIXED64 = 1, LEN = 2, FIXED32 = 5;

    // ---- Pairing (polo) field numbers ----
    public static final int P_PROTOCOL_VERSION = 1, P_STATUS = 2;
    public static final int P_PAIRING_REQUEST = 10, P_OPTIONS = 20, P_CONFIGURATION = 30, P_SECRET = 40;
    public static final int PR_SERVICE_NAME = 1, PR_CLIENT_NAME = 2;
    public static final int O_INPUT_ENCODINGS = 1, O_PREFERRED_ROLE = 3;
    public static final int E_TYPE = 1, E_SYMBOL_LENGTH = 2;
    public static final int C_ENCODING = 1, C_CLIENT_ROLE = 2;
    public static final int S_SECRET = 1;
    public static final int STATUS_OK = 200;
    public static final int ROLE_INPUT = 1, ENC_HEX = 3;

    // ---- Remote field numbers ----
    public static final int R_CONFIGURE = 1, R_SET_ACTIVE = 2, R_PING_REQUEST = 8, R_PING_RESPONSE = 9;
    public static final int R_KEY_INJECT = 10, R_IME_BATCH_EDIT = 21, R_START = 40;
    public static final int R_SET_VOLUME_LEVEL = 50, R_APP_LINK = 90;
    public static final int RC_CODE1 = 1, RC_DEVICE_INFO = 2;
    public static final int DI_UNKNOWN1 = 3, DI_UNKNOWN2 = 4, DI_PACKAGE_NAME = 5, DI_APP_VERSION = 6;
    public static final int KI_KEY_CODE = 1, KI_DIRECTION = 2;
    public static final int PING_VAL1 = 1;
    public static final int SA_ACTIVE = 1;
    public static final int ST_STARTED = 1;
    public static final int IME_COUNTER = 1, IME_FIELD_COUNTER = 2, IME_EDIT_INFO = 3;
    public static final int EI_INSERT = 1, EI_TEXT_FIELD_STATUS = 2;
    public static final int IO_START = 1, IO_END = 2, IO_VALUE = 3;
    public static final int VOL_MAX = 6, VOL_LEVEL = 7, VOL_MUTED = 8;
    public static final int DIR_SHORT = 3;

    // ---------- writer ----------
    public static void varint(ByteArrayOutputStream o, long v) {
        while ((v & ~0x7FL) != 0) { o.write((int) ((v & 0x7F) | 0x80)); v >>>= 7; }
        o.write((int) v);
    }
    public static void tag(ByteArrayOutputStream o, int field, int wire) { varint(o, ((long) field << 3) | wire); }
    public static void varintField(ByteArrayOutputStream o, int field, long v) { tag(o, field, VARINT); varint(o, v); }
    public static void boolField(ByteArrayOutputStream o, int field, boolean v) { varintField(o, field, v ? 1 : 0); }
    public static void bytesField(ByteArrayOutputStream o, int field, byte[] b) {
        tag(o, field, LEN); varint(o, b.length); o.write(b, 0, b.length);
    }
    public static void stringField(ByteArrayOutputStream o, int field, String s) {
        bytesField(o, field, s.getBytes(StandardCharsets.UTF_8));
    }
    public static void msgField(ByteArrayOutputStream o, int field, byte[] msg) { bytesField(o, field, msg); }

    // ---------- pairing message builders ----------
    public static byte[] pairingEnvelope(int payloadField, byte[] payload) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        varintField(o, P_PROTOCOL_VERSION, 2);
        varintField(o, P_STATUS, STATUS_OK);
        msgField(o, payloadField, payload);
        return o.toByteArray();
    }
    public static byte[] pairingRequest(String clientName, String serviceName) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        stringField(o, PR_SERVICE_NAME, serviceName);
        stringField(o, PR_CLIENT_NAME, clientName);
        return pairingEnvelope(P_PAIRING_REQUEST, o.toByteArray());
    }
    public static byte[] pairingOption() {
        ByteArrayOutputStream enc = new ByteArrayOutputStream();
        varintField(enc, E_TYPE, ENC_HEX);
        varintField(enc, E_SYMBOL_LENGTH, 6);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        msgField(o, O_INPUT_ENCODINGS, enc.toByteArray());
        varintField(o, O_PREFERRED_ROLE, ROLE_INPUT);
        return pairingEnvelope(P_OPTIONS, o.toByteArray());
    }
    public static byte[] pairingConfiguration() {
        ByteArrayOutputStream enc = new ByteArrayOutputStream();
        varintField(enc, E_TYPE, ENC_HEX);
        varintField(enc, E_SYMBOL_LENGTH, 6);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        msgField(o, C_ENCODING, enc.toByteArray());
        varintField(o, C_CLIENT_ROLE, ROLE_INPUT);
        return pairingEnvelope(P_CONFIGURATION, o.toByteArray());
    }
    public static byte[] pairingSecret(byte[] secret) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        bytesField(o, S_SECRET, secret);
        return pairingEnvelope(P_SECRET, o.toByteArray());
    }

    // ---------- remote message builders ----------
    public static byte[] remoteConfigure(int features, String pkg, String ver) {
        ByteArrayOutputStream di = new ByteArrayOutputStream();
        varintField(di, DI_UNKNOWN1, 1);
        stringField(di, DI_UNKNOWN2, "1");
        stringField(di, DI_PACKAGE_NAME, pkg);
        stringField(di, DI_APP_VERSION, ver);
        ByteArrayOutputStream cfg = new ByteArrayOutputStream();
        varintField(cfg, RC_CODE1, features);
        msgField(cfg, RC_DEVICE_INFO, di.toByteArray());
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        msgField(o, R_CONFIGURE, cfg.toByteArray());
        return o.toByteArray();
    }
    public static byte[] remoteSetActive(int features) {
        ByteArrayOutputStream sa = new ByteArrayOutputStream();
        varintField(sa, SA_ACTIVE, features);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        msgField(o, R_SET_ACTIVE, sa.toByteArray());
        return o.toByteArray();
    }
    public static byte[] remoteKeyInject(int keyCode, int direction) {
        ByteArrayOutputStream ki = new ByteArrayOutputStream();
        varintField(ki, KI_KEY_CODE, keyCode);
        varintField(ki, KI_DIRECTION, direction);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        msgField(o, R_KEY_INJECT, ki.toByteArray());
        return o.toByteArray();
    }
    public static byte[] remotePingResponse(int val1) {
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        varintField(p, PING_VAL1, val1);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        msgField(o, R_PING_RESPONSE, p.toByteArray());
        return o.toByteArray();
    }
    public static byte[] remoteImeText(String text, int imeCounter, int fieldCounter) {
        byte[] tb = text.getBytes(StandardCharsets.UTF_8);
        int cursor = Math.max(0, text.codePointCount(0, text.length()) - 1);
        ByteArrayOutputStream io = new ByteArrayOutputStream();
        varintField(io, IO_START, cursor);
        varintField(io, IO_END, cursor);
        bytesField(io, IO_VALUE, tb);
        ByteArrayOutputStream ei = new ByteArrayOutputStream();
        varintField(ei, EI_INSERT, 1);
        msgField(ei, EI_TEXT_FIELD_STATUS, io.toByteArray());
        ByteArrayOutputStream be = new ByteArrayOutputStream();
        varintField(be, IME_COUNTER, imeCounter);
        varintField(be, IME_FIELD_COUNTER, fieldCounter);
        msgField(be, IME_EDIT_INFO, ei.toByteArray());
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        msgField(o, R_IME_BATCH_EDIT, be.toByteArray());
        return o.toByteArray();
    }
    public static byte[] remoteAppLink(String appLink) {
        ByteArrayOutputStream a = new ByteArrayOutputStream();
        stringField(a, 1, appLink);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        msgField(o, R_APP_LINK, a.toByteArray());
        return o.toByteArray();
    }

    /** Length-prefix a message with a varint (wire framing). */
    public static byte[] frame(byte[] msg) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        varint(o, msg.length);
        o.write(msg, 0, msg.length);
        return o.toByteArray();
    }

    // ---------- reader ----------
    public static class Field {
        public int num, wire;
        public long varint;
        public byte[] bytes; // for LEN
        Field(int n, int w) { num = n; wire = w; }
    }
    public static long readVarint(byte[] b, int[] pos) {
        long r = 0; int shift = 0;
        while (true) {
            int x = b[pos[0]++] & 0xFF;
            r |= (long) (x & 0x7F) << shift;
            if ((x & 0x80) == 0) break;
            shift += 7;
        }
        return r;
    }
    public static List<Field> parse(byte[] msg) { return parse(msg, 0, msg.length); }
    public static List<Field> parse(byte[] msg, int off, int len) {
        List<Field> out = new ArrayList<>();
        int[] p = { off };
        int end = off + len;
        while (p[0] < end) {
            long t = readVarint(msg, p);
            Field f = new Field((int) (t >>> 3), (int) (t & 7));
            switch (f.wire) {
                case VARINT: f.varint = readVarint(msg, p); break;
                case FIXED64: p[0] += 8; break;
                case LEN: {
                    int n = (int) readVarint(msg, p);
                    f.bytes = new byte[n];
                    System.arraycopy(msg, p[0], f.bytes, 0, n);
                    p[0] += n;
                    break;
                }
                case FIXED32: p[0] += 4; break;
                default: throw new IllegalArgumentException("bad wire type " + f.wire);
            }
            out.add(f);
        }
        return out;
    }
    public static Field first(List<Field> fs, int num) {
        for (Field f : fs) if (f.num == num) return f;
        return null;
    }
    public static long varintOf(byte[] msg, int num) {
        Field f = first(parse(msg), num);
        return f == null ? -1 : f.varint;
    }
}
