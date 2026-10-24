package com.zmlworks.poolmemo;

import java.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Shared wire format with Windows:
 *   PM1.&lt;standard-base64(nonce[12] || ciphertext || tag[16])&gt;
 * Key material:
 *   - If key string base64-decodes to exactly 32 bytes → use those bytes
 *   - Else → SHA-256(UTF-8(keyString))
 */
final class Crypto {
    static final String PREFIX = "PM1.";
    private static final int NONCE = 12;
    private static final int TAG = 16;
    private static final int KEY = 32;

    private Crypto() {}

    static byte[] keyFromString(String keyStr) {
        if (keyStr == null) throw new IllegalArgumentException("key");
        String s = keyStr.trim();
        try {
            byte[] raw = Base64.getDecoder().decode(s);
            if (raw != null && raw.length == KEY) return raw;
        } catch (IllegalArgumentException ignored) { }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static String generateKeyB64() {
        byte[] k = new byte[KEY];
        new SecureRandom().nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }

    static String generateId() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder("pm");
        final char[] alphabet = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
        for (byte v : b) {
            sb.append(alphabet[(v & 0xff) % alphabet.length]);
        }
        return sb.toString(); // 18 chars
    }

    static boolean validId(String id) {
        if (id == null) return false;
        int n = id.length();
        if (n < 6 || n > 200) return false;
        for (int i = 0; i < n; i++) {
            char c = id.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z')
                    && !(c >= '0' && c <= '9') && c != '_' && c != '-') return false;
        }
        return true;
    }

    static String encrypt(String plaintext, String keyStr) throws Exception {
        byte[] key = keyFromString(keyStr);
        byte[] nonce = new byte[NONCE];
        new SecureRandom().nextBytes(nonce);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG * 8, nonce));
        byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        // ct includes tag at end on Android
        byte[] out = new byte[NONCE + ct.length];
        System.arraycopy(nonce, 0, out, 0, NONCE);
        System.arraycopy(ct, 0, out, NONCE, ct.length);
        return PREFIX + Base64.getEncoder().encodeToString(out);
    }

    /** @return plaintext, or null if empty remote, throws if bad format/key */
    static String decrypt(String wire, String keyStr) throws Exception {
        if (wire == null) return "";
        String w = wire.trim();
        if (w.isEmpty()) return "";
        if (!w.startsWith(PREFIX)) throw new IllegalArgumentException("not PM1");
        byte[] all = Base64.getDecoder().decode(w.substring(PREFIX.length()));
        if (all.length < NONCE + TAG) throw new IllegalArgumentException("short");
        byte[] key = keyFromString(keyStr);
        byte[] nonce = Arrays.copyOfRange(all, 0, NONCE);
        byte[] ct = Arrays.copyOfRange(all, NONCE, all.length);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG * 8, nonce));
        byte[] pt = c.doFinal(ct);
        return new String(pt, StandardCharsets.UTF_8);
    }
}
