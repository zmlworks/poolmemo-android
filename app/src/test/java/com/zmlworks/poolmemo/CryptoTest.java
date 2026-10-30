package com.zmlworks.poolmemo;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.Assert.*;

public class CryptoTest {
    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte v : b) sb.append(String.format("%02x", v & 0xff));
        return sb.toString();
    }

    private static byte[] blob(String wire) {
        return Base64.getDecoder().decode(wire.substring(4));
    }

    private static int iterOf(String wire) {
        byte[] a = blob(wire);
        return ((a[16] & 0xff) << 24) | ((a[17] & 0xff) << 16) | ((a[18] & 0xff) << 8) | (a[19] & 0xff);
    }

    @Test
    public void roundTripRawKey() throws Exception {
        String key = Crypto.generateKeyB64();
        String wire = Crypto.encrypt("こんにちは PoolMemo", key);
        assertTrue(wire.startsWith("PM2."));
        assertEquals(0, iterOf(wire)); // random 32-byte key is used directly
        assertEquals("こんにちは PoolMemo", Crypto.decrypt(wire, key));
    }

    @Test
    public void passphrasePbkdf2() throws Exception {
        Crypto.clearCache();
        String key = "my-passphrase-not-base64";
        long t0 = System.nanoTime();
        String wire = Crypto.encrypt("secret", key);
        long t1 = System.nanoTime();
        assertTrue(wire.startsWith("PM2."));
        assertEquals(Crypto.ITER, iterOf(wire));
        assertEquals("secret", Crypto.decrypt(wire, key));
        String wire2 = Crypto.encrypt("secret2", key);
        long t2 = System.nanoTime();
        // same salt reused (no second PBKDF2), fresh nonce
        assertArrayEquals(java.util.Arrays.copyOf(blob(wire), 16), java.util.Arrays.copyOf(blob(wire2), 16));
        assertNotEquals(hex(java.util.Arrays.copyOfRange(blob(wire), 20, 32)),
                hex(java.util.Arrays.copyOfRange(blob(wire2), 20, 32)));
        System.out.printf("PBKDF2 %d iter: %d ms; cached encrypt: %d ms%n", Crypto.ITER,
                (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000);
        try { Crypto.decrypt(wire, "other passphrase"); fail(); } catch (javax.crypto.AEADBadTagException expected) { }
    }

    @Test
    public void pbkdf2KnownAnswers() throws Exception {
        // RFC 7914 §11 / common PBKDF2-HMAC-SHA256 vectors
        byte[] p = "password".getBytes(StandardCharsets.UTF_8), s = "salt".getBytes(StandardCharsets.UTF_8);
        assertEquals("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b", hex(Crypto.pbkdf2(p, s, 1)));
        assertEquals("ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43", hex(Crypto.pbkdf2(p, s, 2)));
        assertEquals("c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a", hex(Crypto.pbkdf2(p, s, 4096)));
    }

    @Test
    public void iterationBoundsEnforced() throws Exception {
        byte[] salt = new byte[16];
        String w = Crypto.encryptWith("x", "pass phrase", salt, Crypto.MIN_ITER);
        assertEquals("x", Crypto.decrypt(w, "pass phrase"));
        byte[] a = blob(w);
        a[16] = 0x7f; // ~2.1e9 iterations: refuse instead of spinning
        try { Crypto.decrypt("PM2." + Base64.getEncoder().encodeToString(a), "pass phrase"); fail(); }
        catch (IllegalArgumentException expected) { }
    }

    @Test
    public void legacyPm1StillReadable() throws Exception {
        String key = "my-passphrase-not-base64";
        String wire = Crypto.encryptV1("old", key);
        assertTrue(wire.startsWith("PM1."));
        assertEquals("old", Crypto.decrypt(wire, key));
    }

    @Test
    public void validId() {
        assertTrue(Crypto.validId("abcdef"));
        assertFalse(Crypto.validId("abc"));
        assertFalse(Crypto.validId("abc def"));
    }

    @Test
    public void fixedVectorFromNode() throws Exception {
        // From shared/crypto_ref.mjs (key = 32 bytes of 0x42)
        String key = "QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI=";
        String wire = "PM1.ERERERERERERERER2wJHfwLhY6Srf/sLgANfdqaU4WPjEKVtvhVfExulnR0irSDgEw==";
        assertEquals("hello-プールメモ", Crypto.decrypt(wire, key));
        // PM2 (salt = 16×0x22, nonce = 12×0x11)
        assertEquals("hello-プールメモ", Crypto.decrypt("PM2.IiIiIiIiIiIiIiIiIiIiIgAAAAARERERERERERERERHbAkd/AuFjpKt/+wuAA192ppThY+MQpW2+FV8TG6WdHSKtIOAT", key));
        assertEquals("hello-プールメモ", Crypto.decrypt("PM2.IiIiIiIiIiIiIiIiIiIiIgAJJ8ARERERERERERERERG3rjh+E/aSjXcj9QKClrDo8dBNZVf+4veDIvHPbXiV1Sp+qYoV", "correct horse"));
    }
}
