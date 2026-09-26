package com.prateek.mitvremote.wifi;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Calendar;
import java.util.Date;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;

/**
 * Client identity for the Android TV Remote protocol.
 * A persistent RSA keypair lives in the AndroidKeyStore; the TV remembers
 * this certificate after pairing, so it must survive app restarts.
 */
public class CertManager {
    private static final String ALIAS = "mitvremote-tv";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    private SSLContext sslContext;

    /** Generate the keypair on first use. Throws on failure. */
    public synchronized void ensureKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return;

        Calendar c = Calendar.getInstance();
        Date notBefore = c.getTime();
        c.add(Calendar.YEAR, 10);
        Date notAfter = c.getTime();

        KeyPairGenerator kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE);
        kpg.initialize(new KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_SIGN)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(new X500Principal("CN=Mi TV Remote"))
                .setCertificateSerialNumber(BigInteger.valueOf(Math.abs(System.currentTimeMillis())))
                .setCertificateNotBefore(notBefore)
                .setCertificateNotAfter(notAfter)
                .build());
        kpg.generateKeyPair();
    }

    private KeyStore loadKeyStore() throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        return ks;
    }

    public synchronized SSLContext getSslContext() throws Exception {
        if (sslContext != null) return sslContext;
        ensureKey();
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(loadKeyStore(), null);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), new TrustManager[]{trustAll()}, new SecureRandom());
        sslContext = ctx;
        return ctx;
    }

    private static X509TrustManager trustAll() {
        return new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
            @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
    }

    public KeyManager[] getKeyManagers() throws Exception {
        ensureKey();
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(loadKeyStore(), null);
        return kmf.getKeyManagers();
    }

    private RSAPublicKey clientRsaKey() throws Exception {
        ensureKey();
        Certificate cert = loadKeyStore().getCertificate(ALIAS);
        if (cert == null) throw new IllegalStateException("client certificate missing");
        return (RSAPublicKey) cert.getPublicKey();
    }

    /** Unsigned big-endian magnitude, mirroring the reference client's encoding. */
    private static byte[] magnitude(BigInteger v) {
        byte[] b = v.toByteArray();
        int i = 0;
        while (i < b.length - 1 && b[i] == 0) i++;
        if (i == 0) return b;
        byte[] r = new byte[b.length - i];
        System.arraycopy(b, i, r, 0, r.length);
        return r;
    }

    private static byte[] hexToBytes(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++)
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    /**
     * Derive the 32-byte pairing secret (alpha) from the 6-hex-char code shown on the TV.
     * alpha = SHA-256(clientMod || clientExp || serverMod || serverExp || nonce)
     * where the code is <alpha[0] as hex><2-byte nonce as hex>.
     */
    public byte[] derivePairingSecret(String code, SSLSocket pairingSocket) throws Exception {
        String clean = code.trim().toUpperCase().replace(" ", "");
        if (clean.length() != 6 || !clean.matches("[0-9A-F]{6}"))
            throw new IllegalArgumentException("Enter the 6-character code shown on the TV");

        RSAPublicKey clientKey = clientRsaKey();
        Certificate[] peer = pairingSocket.getSession().getPeerCertificates();
        if (peer == null || peer.length == 0)
            throw new IllegalStateException("TV did not present a certificate");
        RSAPublicKey serverKey = (RSAPublicKey) peer[0].getPublicKey();

        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(magnitude(clientKey.getModulus()));
        md.update(magnitude(clientKey.getPublicExponent()));
        md.update(magnitude(serverKey.getModulus()));
        md.update(magnitude(serverKey.getPublicExponent()));
        md.update(hexToBytes(clean.substring(2)));
        byte[] alpha = md.digest();

        if ((alpha[0] & 0xFF) != Integer.parseInt(clean.substring(0, 2), 16))
            throw new IllegalArgumentException("Wrong code - check the TV screen and try again");
        return alpha;
    }
}
