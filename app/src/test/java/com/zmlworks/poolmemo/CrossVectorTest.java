package com.zmlworks.poolmemo;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * C → Java: wires produced by the Windows code (windows/test/test_pmcrypto.c, pmcrypto.h)
 * must decrypt here. Fixture: src/test/resources/c_crypto_vectors.bin (regenerate with
 * windows/test/run.sh, see README). Java → C is checked by the C test with crypto_vectors.bin.
 */
public class CrossVectorTest {
    static List<String> unpack(byte[] b) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < b.length) {
            int c = i;
            while (b[c] != ':') c++;
            int n = Integer.parseInt(new String(b, i, c - i, StandardCharsets.US_ASCII));
            out.add(new String(b, c + 1, n, StandardCharsets.UTF_8));
            i = c + 1 + n;
        }
        return out;
    }

    @Test public void decryptsWiresFromC() throws Exception {
        byte[] data;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("c_crypto_vectors.bin")) {
            assertNotNull("fixture missing", in);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) bos.write(buf, 0, n);
            data = bos.toByteArray();
        }
        List<String> f = unpack(data);
        int ok = 0, rejected = 0;
        for (int i = 0; i < f.size(); ) {
            String k = f.get(i++);
            if (k.equals("C")) {
                String key = f.get(i++), wire = f.get(i++), plain = f.get(i++);
                assertTrue(wire.startsWith("PM2."));
                assertEquals(plain, Crypto.decrypt(wire, key));
                ok++;
            } else if (k.equals("X")) {
                String key = f.get(i++), wire = f.get(i++);
                try { Crypto.decrypt(wire, key); fail("decrypted with wrong key"); } catch (Exception expected) { }
                rejected++;
            } else fail("bad record " + k);
        }
        assertTrue(ok >= 5);
        assertTrue(rejected >= 1);
    }
}
