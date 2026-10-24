package com.zmlworks.poolmemo;

import org.junit.Test;
import static org.junit.Assert.*;

public class CryptoTest {
    @Test
    public void roundTripRawKey() throws Exception {
        String key = Crypto.generateKeyB64();
        String wire = Crypto.encrypt("こんにちは PoolMemo", key);
        assertTrue(wire.startsWith("PM1."));
        assertEquals("こんにちは PoolMemo", Crypto.decrypt(wire, key));
    }

    @Test
    public void passphraseSha256() throws Exception {
        String key = "my-passphrase-not-base64";
        String wire = Crypto.encrypt("secret", key);
        assertEquals("secret", Crypto.decrypt(wire, key));
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
    }
}
