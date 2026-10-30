package com.zmlworks.poolmemo;

import java.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Wire format shared with Windows (windows/pmcrypto.h). Written since 0.4.0:
 * <pre>PM2.&lt;standard-base64( salt[16] || iter[4, big-endian] || nonce[12] || ciphertext || tag[16] )&gt;</pre>
 * Key material (key string = trimmed setting):
 * <ul>
 *   <li>iter == 0: the key string base64-decodes to exactly 32 random bytes (the generated
 *       "キー") and those bytes are the AES-256 key; salt is unused. This is safe because
 *       such a key already has 256 bits of entropy — stretching would add nothing.</li>
 *   <li>iter &gt; 0: AES key = PBKDF2-HMAC-SHA256(UTF-8(key string), salt, iter, 32 bytes).
 *       Written with {@link #ITER} iterations and a random salt; readers accept
 *       {@link #MIN_ITER}..{@link #MAX_ITER} so a hostile server cannot make us spin.</li>
 * </ul>
 * Still read (never written): legacy
 * <pre>PM1.&lt;standard-base64(nonce[12] || ciphertext || tag[16])&gt;</pre>
 * with key = 32-byte base64 key, else SHA-256(UTF-8(key string)) (no stretching).
 * <p>
 * PBKDF2 is expensive on purpose, so derived keys are cached per (key, salt, iter) and
 * encryption reuses the salt of a key already derived for the same key string (fresh
 * random salt only when none is cached). Nonces are always fresh. Never call
 * encrypt/decrypt on the UI thread.
 */
final class Crypto {
    static final String PREFIX = "PM2.";
    static final String PREFIX_V1 = "PM1.";
    static final int ITER = 600_000;
    static final int MIN_ITER = 10_000;
    static final int MAX_ITER = 10_000_000;
    static final int SALT = 16;
    private static final int NONCE = 12;
    private static final int TAG = 16;
    private static final int KEY = 32;
    private static final int HDR = SALT + 4 + NONCE;

    private static final SecureRandom RND = new SecureRandom();
    /** "keyStr\0b64(salt)\0iter" → derived key (LRU). */
    private static final Map<String, byte[]> DERIVED = new LinkedHashMap<String, byte[]>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, byte[]> e) { return size() > 8; }
    };
    /** keyStr → salt to use when encrypting (one whose key is already derived). */
    private static final Map<String, byte[]> ENC_SALT = new LinkedHashMap<String, byte[]>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, byte[]> e) { return size() > 8; }
    };

    private Crypto() {}

    /** 32 raw key bytes if the key string is a base64 32-byte key, else null (passphrase). */
    static byte[] rawKey(String keyStr) {
        if (keyStr == null) throw new IllegalArgumentException("key");
        try {
            byte[] raw = Base64.getDecoder().decode(keyStr.trim());
            if (raw != null && raw.length == KEY) return raw;
        } catch (IllegalArgumentException ignored) { }
        return null;
    }

    /** Legacy PM1 key: raw 32-byte key, else SHA-256(UTF-8(trimmed key)). */
    static byte[] keyFromStringV1(String keyStr) {
        byte[] raw = rawKey(keyStr);
        if (raw != null) return raw;
        try {
            return MessageDigest.getInstance("SHA-256").digest(keyStr.trim().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** PBKDF2-HMAC-SHA256 with a 32-byte output (one block). */
    static byte[] pbkdf2(byte[] password, byte[] salt, int iter) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        // HMAC keys must be non-empty for SecretKeySpec; an empty password is rejected earlier.
        mac.init(new SecretKeySpec(password, "HmacSHA256"));
        mac.update(salt);
        mac.update(new byte[]{0, 0, 0, 1});
        byte[] u = mac.doFinal();
        byte[] t = u.clone();
        for (int i = 1; i < iter; i++) {
            mac.update(u);
            mac.doFinal(u, 0);
            for (int j = 0; j < t.length; j++) t[j] ^= u[j];
        }
        return t;
    }

    private static String cacheKey(String keyStr, byte[] salt, int iter) {
        return keyStr + '\0' + Base64.getEncoder().encodeToString(salt) + '\0' + iter;
    }

    private static byte[] derive(String keyStr, byte[] salt, int iter) throws Exception {
        String s = keyStr.trim();
        if (s.isEmpty()) throw new IllegalArgumentException("empty key");
        String ck = cacheKey(s, salt, iter);
        synchronized (DERIVED) {
            byte[] k = DERIVED.get(ck);
            if (k != null) { if (iter == ITER) ENC_SALT.put(s, salt.clone()); return k; }
        }
        byte[] k = pbkdf2(s.getBytes(StandardCharsets.UTF_8), salt, iter);
        synchronized (DERIVED) {
            DERIVED.put(ck, k);
            if (iter == ITER) ENC_SALT.put(s, salt.clone());
        }
        return k;
    }

    /** For tests: forget derived keys. */
    static void clearCache() {
        synchronized (DERIVED) { DERIVED.clear(); ENC_SALT.clear(); }
    }

    static String generateKeyB64() {
        byte[] k = new byte[KEY];
        RND.nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }

    static String generateId() {
        byte[] b = new byte[16];
        RND.nextBytes(b);
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

    /** Encrypts to PM2 (see class doc). */
    static String encrypt(String plaintext, String keyStr) throws Exception {
        byte[] raw = rawKey(keyStr);
        byte[] salt;
        int iter;
        byte[] key;
        if (raw != null) {
            salt = new byte[SALT];
            RND.nextBytes(salt);
            iter = 0;
            key = raw;
        } else {
            String s = keyStr.trim();
            synchronized (DERIVED) {
                byte[] cached = ENC_SALT.get(s);
                salt = cached != null ? cached.clone() : null;
            }
            if (salt == null) { salt = new byte[SALT]; RND.nextBytes(salt); }
            iter = ITER;
            key = derive(keyStr, salt, iter);
        }
        return PREFIX + Base64.getEncoder().encodeToString(seal(salt, iter, key, plaintext));
    }

    /** PM2 with explicit salt/iter (tests and vectors). */
    static String encryptWith(String plaintext, String keyStr, byte[] salt, int iter) throws Exception {
        byte[] key = iter == 0 ? rawKey(keyStr) : derive(keyStr, salt, iter);
        if (key == null) throw new IllegalArgumentException("not a raw key");
        return PREFIX + Base64.getEncoder().encodeToString(seal(salt, iter, key, plaintext));
    }

    private static byte[] seal(byte[] salt, int iter, byte[] key, String plaintext) throws Exception {
        byte[] nonce = new byte[NONCE];
        RND.nextBytes(nonce);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG * 8, nonce));
        byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)); // ciphertext || tag
        byte[] out = new byte[HDR + ct.length];
        System.arraycopy(salt, 0, out, 0, SALT);
        out[SALT] = (byte) (iter >>> 24);
        out[SALT + 1] = (byte) (iter >>> 16);
        out[SALT + 2] = (byte) (iter >>> 8);
        out[SALT + 3] = (byte) iter;
        System.arraycopy(nonce, 0, out, SALT + 4, NONCE);
        System.arraycopy(ct, 0, out, HDR, ct.length);
        return out;
    }

    /** Legacy writer, kept only for tests / vectors. */
    static String encryptV1(String plaintext, String keyStr) throws Exception {
        byte[] key = keyFromStringV1(keyStr);
        byte[] nonce = new byte[NONCE];
        RND.nextBytes(nonce);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG * 8, nonce));
        byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[NONCE + ct.length];
        System.arraycopy(nonce, 0, out, 0, NONCE);
        System.arraycopy(ct, 0, out, NONCE, ct.length);
        return PREFIX_V1 + Base64.getEncoder().encodeToString(out);
    }

    /** @return plaintext ("" for an empty remote); throws if bad format / wrong key. */
    static String decrypt(String wire, String keyStr) throws Exception {
        if (wire == null) return "";
        String w = wire.trim();
        if (w.isEmpty()) return "";
        if (w.startsWith(PREFIX)) {
            byte[] all = Base64.getDecoder().decode(w.substring(PREFIX.length()));
            if (all.length < HDR + TAG) throw new IllegalArgumentException("short");
            byte[] salt = Arrays.copyOfRange(all, 0, SALT);
            int iter = ((all[SALT] & 0xff) << 24) | ((all[SALT + 1] & 0xff) << 16)
                    | ((all[SALT + 2] & 0xff) << 8) | (all[SALT + 3] & 0xff);
            byte[] key;
            if (iter == 0) {
                key = rawKey(keyStr);
                if (key == null) throw new javax.crypto.AEADBadTagException("raw-key memo, passphrase given");
            } else {
                if (iter < MIN_ITER || iter > MAX_ITER) throw new IllegalArgumentException("iterations");
                key = derive(keyStr, salt, iter);
            }
            return open(key, all, SALT + 4);
        }
        if (w.startsWith(PREFIX_V1)) {
            byte[] all = Base64.getDecoder().decode(w.substring(PREFIX_V1.length()));
            if (all.length < NONCE + TAG) throw new IllegalArgumentException("short");
            return open(keyFromStringV1(keyStr), all, 0);
        }
        throw new IllegalArgumentException("not PoolMemo");
    }

    private static String open(byte[] key, byte[] all, int nonceAt) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(TAG * 8, all, nonceAt, NONCE));
        byte[] pt = c.doFinal(all, nonceAt + NONCE, all.length - nonceAt - NONCE);
        return new String(pt, StandardCharsets.UTF_8);
    }
}
