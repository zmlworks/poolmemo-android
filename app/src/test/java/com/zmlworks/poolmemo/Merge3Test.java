package com.zmlworks.poolmemo;

import org.junit.Test;
import static org.junit.Assert.*;

public class Merge3Test {
    private static final String M = Merge3.MARKER;

    private static String mg(String o, String a, String b) { return Merge3.merge(o, a, b); }

    @Test public void trivial() {
        assertEquals("x", mg("a", "x", "a"));
        assertEquals("x", mg("a", "a", "x"));
        assertEquals("x", mg("a", "x", "x"));
        assertEquals("", mg("a", "", "a"));
    }

    @Test public void differentLines() {
        assertEquals("A\nb\nC", mg("a\nb\nc", "A\nb\nc", "a\nb\nC"));
        assertEquals("a\nX\nb\nc\nY", mg("a\nb\nc", "a\nX\nb\nc", "a\nb\nc\nY"));
    }

    @Test public void adjacentLinesNoConflict() {
        assertEquals("A\nB\nc", mg("a\nb\nc", "A\nb\nc", "a\nB\nc"));
    }

    @Test public void bothAppendKeepsBothNoMarker() {
        assertEquals("a\nx\ny", mg("a", "a\nx", "a\ny"));
        assertEquals("a\nx\ny\n", mg("a\n", "a\nx\n", "a\ny\n"));
        assertEquals("x\ny", mg("", "x", "y"));
    }

    @Test public void sameLineConflictKeepsBoth() {
        assertEquals("a\nLOCAL\n" + M + "\nSERVER\nc", mg("a\nb\nc", "a\nLOCAL\nc", "a\nSERVER\nc"));
    }

    @Test public void conflictTrimsCommonLines() {
        String r = mg("a\nb\nc", "a\nB1\nsame\nc", "a\nB2\nsame\nc");
        assertEquals("a\nB1\n" + M + "\nB2\nsame\nc", r);
    }

    @Test public void deleteVsEditKeepsEdit() {
        assertEquals("a\nB!\nc", mg("a\nb\nc", "a\nc", "a\nB!\nc"));
        assertEquals("a\nB!\nc", mg("a\nb\nc", "a\nB!\nc", "a\nc"));
    }

    @Test public void bothDeleteSame() {
        assertEquals("a\nc", mg("a\nb\nc", "a\nc", "a\nc"));
    }

    @Test public void editLastLineAndAppend() {
        assertEquals("ab\ny", mg("a", "ab", "a\ny"));
    }

    @Test public void japanese() {
        assertEquals("買い物\n牛乳\n卵\nパン", mg("買い物\n牛乳", "買い物\n牛乳\n卵", "買い物\n牛乳\nパン"));
    }

    @Test public void neverLosesLines() {
        java.util.Random rnd = new java.util.Random(7);
        for (int t = 0; t < 3000; t++) {
            String o = rand(rnd, 6, "o");
            String a = mutate(rnd, o, "A" + t);
            String b = mutate(rnd, o, "B" + t);
            String r = mg(o, a, b);
            for (String l : Merge3.lines(a)) if (!o.contains(l)) assertTrue(r + "|" + l, r.contains(l));
            for (String l : Merge3.lines(b)) if (!o.contains(l)) assertTrue(r + "|" + l, r.contains(l));
        }
    }

    static String rand(java.util.Random r, int n, String p) {
        StringBuilder sb = new StringBuilder();
        int k = r.nextInt(n + 1);
        for (int i = 0; i < k; i++) { if (i > 0) sb.append('\n'); sb.append(p).append(i); }
        return sb.toString();
    }

    static String mutate(java.util.Random r, String o, String tag) {
        java.util.List<String> l = new java.util.ArrayList<>(java.util.Arrays.asList(Merge3.lines(o)));
        int ops = 1 + r.nextInt(3);
        for (int i = 0; i < ops; i++) {
            int op = r.nextInt(3);
            if (op == 0 || l.isEmpty()) l.add(r.nextInt(l.size() + 1), tag + "+" + i);
            else if (op == 1) l.set(r.nextInt(l.size()), tag + "~" + i);
            else l.remove(r.nextInt(l.size()));
        }
        return Merge3.join(l);
    }
}
