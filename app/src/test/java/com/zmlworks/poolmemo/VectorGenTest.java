package com.zmlworks.poolmemo;

import org.junit.Assume;
import org.junit.Test;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Writes merge/header vectors for the Windows C port (PM_VEC_OUT=path). */
public class VectorGenTest {
    @Test public void gen() throws Exception {
        String out = System.getenv("PM_VEC_OUT");
        Assume.assumeTrue(out != null);
        Random r = new Random(42);
        List<String> f = new ArrayList<>();
        String[] alpha = {"a", "b", "c", "買い物", "", "x y", "牛乳", Merge3.MARKER};
        for (int t = 0; t < 4000; t++) {
            String o = gen(r, alpha);
            String a = mut(r, o, alpha), b = mut(r, o, alpha);
            if (r.nextInt(10) == 0) a = o;
            if (r.nextInt(10) == 0) b = a;
            f.add("M"); f.add(o); f.add(a); f.add(b); f.add(Merge3.merge(o, a, b));
        }
        String[] wires = {"", "PM1.abc", "PM1.ERERERERERERERER2wJHfwLhY6Srf/sLgANfdqaU4WPjEKVtvhVfExulnR0irSDgEw=="};
        for (String w : wires) { f.add("P"); f.add(w); f.add(SyncEngine.pseudoRev(w)); }
        try (FileOutputStream os = new FileOutputStream(out)) {
            os.write(LocalStoreBytes.pack(f));
        }
    }

    static String gen(Random r, String[] al) {
        StringBuilder sb = new StringBuilder();
        int n = r.nextInt(7);
        for (int i = 0; i < n; i++) { if (i > 0) sb.append('\n'); sb.append(al[r.nextInt(al.length)]); if (r.nextBoolean()) sb.append(i); }
        if (r.nextInt(4) == 0) sb.append('\n');
        return sb.toString();
    }

    static String mut(Random r, String o, String[] al) {
        List<String> l = new ArrayList<>(java.util.Arrays.asList(Merge3.lines(o)));
        int ops = r.nextInt(4);
        for (int i = 0; i < ops; i++) {
            int op = r.nextInt(3);
            if (op == 0 || l.isEmpty()) l.add(r.nextInt(l.size() + 1), al[r.nextInt(al.length)]);
            else if (op == 1) l.set(r.nextInt(l.size()), al[r.nextInt(al.length)] + "!");
            else l.remove(r.nextInt(l.size()));
        }
        return Merge3.join(l);
    }

    /** byte-length prefixed (matches pmsync.h pm_bput). */
    static final class LocalStoreBytes {
        static byte[] pack(List<String> f) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            for (String s : f) {
                byte[] b = s.getBytes(StandardCharsets.UTF_8);
                byte[] h = (b.length + ":").getBytes(StandardCharsets.UTF_8);
                bos.write(h, 0, h.length); bos.write(b, 0, b.length);
            }
            return bos.toByteArray();
        }
    }
}
