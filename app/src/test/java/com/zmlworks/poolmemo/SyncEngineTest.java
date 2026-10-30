package com.zmlworks.poolmemo;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Two simulated devices against a mock PoolSketch (last-write-wins, no revisions). */
public class SyncEngineTest {
    static final String KEY = "QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI=";

    /** Mock server; hooks let a test interleave operations deterministically. */
    static class Server {
        volatile String wire = "";
        volatile Runnable beforeSave, afterSave, beforeFetch;
        volatile boolean failSave;
        volatile int maxDelayMs;
        final Random rnd = new Random(1);
        final AtomicInteger saves = new AtomicInteger();
        void delay() {
            int d = maxDelayMs;
            if (d > 0) { try { Thread.sleep(rnd.nextInt(d)); } catch (InterruptedException ignored) { } }
        }
    }

    static class Device {
        final SyncEngine eng;
        final Server srv;
        String text = "", baseWire;
        Device(Server srv) {
            this.srv = srv;
            eng = new SyncEngine(new SyncEngine.Remote() {
                @Override public String fetch() {
                    Runnable h = srv.beforeFetch; if (h != null) { srv.beforeFetch = null; h.run(); }
                    srv.delay();
                    return srv.wire;
                }
                @Override public void save(String w) throws Exception {
                    Runnable h = srv.beforeSave; if (h != null) { srv.beforeSave = null; h.run(); }
                    if (srv.failSave) throw new Exception("offline");
                    srv.delay();
                    synchronized (srv) { srv.wire = w; }
                    srv.saves.incrementAndGet();
                    Runnable a = srv.afterSave; if (a != null) { srv.afterSave = null; a.run(); }
                }
            }, new SyncEngine.Codec() {
                @Override public String encrypt(String p) throws Exception { return Crypto.encrypt(p, KEY); }
                @Override public String decrypt(String w) throws Exception { return Crypto.decrypt(w, KEY); }
            }, st -> { }, null);
        }
        void sync() throws Exception {
            SyncEngine.Result r = eng.sync(text, baseWire);
            text = r.synced.equals(text) ? r.base.text : Merge3.merge(r.synced, text, r.base.text);
            baseWire = r.base.wire;
        }
    }

    static String serverText(Server s) throws Exception {
        return SyncEngine.parse(s.wire, Crypto.decrypt(s.wire, KEY)).text;
    }

    @Test public void differentLinesBothKept() throws Exception {
        Server s = new Server();
        Device a = new Device(s), b = new Device(s);
        a.text = "1\n2\n3"; a.sync(); b.sync();
        assertEquals("1\n2\n3", b.text);
        a.text = "1 A\n2\n3"; b.text = "1\n2\n3 B";
        a.sync(); b.sync(); a.sync();
        assertEquals("1 A\n2\n3 B", serverText(s));
        assertEquals(serverText(s), a.text);
        assertEquals(serverText(s), b.text);
    }

    @Test public void sameLineKeepsBothVersions() throws Exception {
        Server s = new Server();
        Device a = new Device(s), b = new Device(s);
        a.text = "x\ny"; a.sync(); b.sync();
        a.text = "x\nfrom A"; b.text = "x\nfrom B";
        a.sync(); b.sync(); a.sync();
        assertEquals("x\nfrom B\n" + Merge3.MARKER + "\nfrom A", serverText(s));
        assertEquals(a.text, b.text);
    }

    @Test public void noEditsNoWrite() throws Exception {
        Server s = new Server();
        Device a = new Device(s);
        a.text = "hello"; a.sync();
        int n = s.saves.get();
        a.sync(); a.sync();
        assertEquals(n, s.saves.get());
    }

    /** B fetched before A wrote, B's write lands after A's verify: A must notice later. */
    @Test public void lostUpdateRaceRecovered() throws Exception {
        Server s = new Server();
        Device a = new Device(s), b = new Device(s);
        a.text = "base"; a.sync(); b.sync();
        a.text = "base\nA line"; b.text = "base\nB line";
        // B fetches + merges now, but its save is held until A has fully written and verified.
        CountDownLatch aDone = new CountDownLatch(1);
        CountDownLatch bReady = new CountDownLatch(1);
        Thread tb = new Thread(() -> {
            try {
                s.beforeSave = () -> { bReady.countDown(); try { aDone.await(); } catch (InterruptedException ignored) { } };
                b.sync();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        tb.start();
        bReady.await();
        a.sync();               // A writes and verifies successfully (B not yet written)
        aDone.countDown();
        tb.join();              // B overwrites with a version lacking A's line
        assertFalse(serverText(s).contains("A line"));
        a.sync();               // A's delayed verify: sibling detected → A's line restored
        b.sync();
        assertTrue(serverText(s).contains("A line"));
        assertTrue(serverText(s).contains("B line"));
        assertEquals(a.text, b.text);
    }

    /** Another device writes between our save and our verify. */
    @Test public void writeBetweenSaveAndVerify() throws Exception {
        Server s = new Server();
        Device a = new Device(s), b = new Device(s);
        a.text = "t"; a.sync(); b.sync();
        b.text = "t\nB";
        a.text = "t\nA";
        s.afterSave = () -> { try { b.sync(); } catch (Exception e) { throw new RuntimeException(e); } };
        a.sync();
        a.sync(); b.sync();
        assertTrue(serverText(s).contains("A"));
        assertTrue(serverText(s).contains("B"));
        assertEquals(a.text, b.text);
    }

    @Test public void typingDuringSaveIsKept() throws Exception {
        Server s = new Server();
        Device a = new Device(s), b = new Device(s);
        a.text = "1\n2"; a.sync(); b.sync();
        b.text = "1\n2\nfrom B"; b.sync();
        // A starts syncing "1x\n2" and types more while the request is in flight
        String snapshot = "1x\n2";
        a.text = snapshot;
        SyncEngine.Result r = a.eng.sync(snapshot, a.baseWire);
        String typedMeanwhile = "1x\n2 more";
        String shown = Merge3.merge(r.synced, typedMeanwhile, r.base.text);
        assertEquals("1x\n2 more\nfrom B", shown);
        a.text = shown; a.baseWire = r.base.wire;
        a.sync(); b.sync();
        assertEquals("1x\n2 more\nfrom B", serverText(s));
        assertEquals(a.text, b.text);
    }

    @Test public void offlineThenRecover() throws Exception {
        Server s = new Server();
        Device a = new Device(s), b = new Device(s);
        a.text = "a"; a.sync(); b.sync();
        s.failSave = true;
        a.text = "a\noffline edit";
        try { a.sync(); fail(); } catch (Exception expected) { }
        s.failSave = false;                      // A's network back later; B saved meanwhile
        b.text = "b first\na"; b.sync();
        a.sync();
        assertEquals("b first\na\noffline edit", serverText(s));
    }

    @Test public void serverWipedReuploads() throws Exception {
        Server s = new Server();
        Device a = new Device(s);
        a.text = "keep me"; a.sync();
        s.wire = "";   // expired on PoolSketch
        a.sync();
        assertEquals("keep me", serverText(s));
        assertEquals("keep me", a.text);
    }

    @Test public void foreignContentNeverOverwritten() throws Exception {
        Server s = new Server();
        s.wire = "PM1." + java.util.Base64.getEncoder().encodeToString(new byte[40]);
        Device a = new Device(s);
        a.text = "mine";
        try { a.sync(); fail(); } catch (Exception expected) { }
        assertEquals(0, s.saves.get());
    }

    @Test public void legacyClientWrite() throws Exception {
        Server s = new Server();
        s.wire = Crypto.encryptV1("old\nmemo", KEY);       // written by v0.2.x
        Device a = new Device(s);
        a.sync();
        assertEquals("old\nmemo", a.text);
        a.text = "old\nmemo\nnew"; a.sync();
        // old client shows the header line, keeps it and edits line 1
        String plain = Crypto.decrypt(s.wire, KEY).replace("\nold\n", "\nOLD\n");
        s.wire = Crypto.encryptV1(plain, KEY);
        a.text = "old\nmemo\nnew\nnewer"; a.sync();
        assertEquals("OLD\nmemo\nnew\nnewer", serverText(s));
    }

    @Test public void headerRoundTrip() {
        List<String> h = new ArrayList<>(); h.add("0123456789ab"); h.add("ba9876543210");
        String p = SyncEngine.format("aaaaaaaaaaaa", h, "#pmsync1 not a header\ntext");
        SyncEngine.Snap sn = SyncEngine.parse("w", p);
        assertEquals("aaaaaaaaaaaa", sn.rev);
        assertEquals(h, sn.hist);
        assertEquals("#pmsync1 not a header\ntext", sn.text);
        assertTrue(SyncEngine.parse("w", "#pmsync1 nothex\nx").legacy);
    }

    @Test public void rebaseDraftFromOlderBase() throws Exception {
        Server s = new Server();
        Device a = new Device(s);
        a.text = "1\n2"; a.sync();
        String oldBase = a.baseWire;
        a.text = "1\n2\n3"; a.sync();
        // UI still believed it was on oldBase and had typed "0" on top
        SyncEngine.Result r = a.eng.sync("0\n1\n2", oldBase);
        assertEquals("0\n1\n2\n3", r.base.text);
    }

    /** Random concurrent inserts from 2 threads with random latency: nothing may be lost. */
    @Test public void stressConcurrentInsertsNeverLost() throws Exception {
        stress(40, 4, 25);
    }

    /** Laggy server (up to 60 ms per request) → long overlapping read/merge/write windows. */
    @Test public void stressSlowServer() throws Exception {
        stress(4, 60, 12);
    }

    @Test public void localStoreRoundTrip() throws Exception {
        java.io.File dir = java.nio.file.Files.createTempDirectory("pm").toFile();
        LocalStore ls = new LocalStore(dir, "pmtestid", KEY);
        Server s = new Server();
        Device a = new Device(s);
        a.text = "x\ny"; a.sync(); a.text = "x\ny\nz"; a.sync();
        ls.saveState(a.eng.state);
        ls.saveDraft(a.baseWire, "draft text");
        SyncEngine.State st = ls.open().state;
        assertEquals(a.eng.state.base.wire, st.base.wire);
        assertEquals("x\ny\nz", st.base.text);
        assertEquals(a.eng.state.snaps.size(), st.snaps.size());
        assertEquals(a.eng.state.base.hist, st.base.hist);
        assertEquals("draft text", ls.loadDraft().text);
        LocalStore.Opened other = new LocalStore(dir, "pmtestid", "other-key").open();
        assertNull(other.state);                 // wrong key → separate files, nothing read
        assertNull(other.draft);
        assertFalse(other.otherKeyData);
        assertEquals("draft text", ls.loadDraft().text); // and nothing overwritten
    }

    /** 0.3.x files (named by ID only) are migrated with the right key, kept with a wrong one. */
    @Test public void localStoreLegacyFiles() throws Exception {
        java.io.File dir = java.nio.file.Files.createTempDirectory("pm").toFile();
        String id = "pmlegacyid";
        String legacyName = "draft-" + LocalStore.hash(id) + ".dat";
        String draft = LocalStore.pack(java.util.Arrays.asList("D1", id, "", "unsent text"));
        java.nio.file.Files.write(new java.io.File(dir, legacyName).toPath(),
                Crypto.encryptV1(draft, KEY).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        LocalStore.Opened wrong = new LocalStore(dir, id, "mistyped-key").open();
        assertTrue(wrong.otherKeyData);
        assertNull(wrong.draft);
        new LocalStore(dir, id, "mistyped-key").saveDraft(null, ""); // editor saves its (empty) text
        assertTrue(new java.io.File(dir, legacyName).exists());      // legacy draft untouched

        LocalStore.Opened right = new LocalStore(dir, id, KEY).open();
        assertFalse(right.otherKeyData);
        assertEquals("unsent text", right.draft.text);
        assertFalse(new java.io.File(dir, legacyName).exists());     // migrated
        assertEquals("unsent text", new LocalStore(dir, id, KEY).loadDraft().text);
    }

    @Test public void localStoreCorruptFileMovedAside() throws Exception {
        java.io.File dir = java.nio.file.Files.createTempDirectory("pm").toFile();
        LocalStore ls = new LocalStore(dir, "pmtestid", KEY);
        ls.saveDraft(null, "x");
        java.io.File[] fs = dir.listFiles();
        assertEquals(1, fs.length);
        java.nio.file.Files.write(fs[0].toPath(), "PM2.garbage".getBytes());
        LocalStore.Opened o = new LocalStore(dir, "pmtestid", KEY).open();
        assertNull(o.draft);
        assertEquals(1, o.movedAside.size());
        assertTrue(new java.io.File(dir, o.movedAside.get(0)).exists());
    }

    @Test public void tooLargeIsNotSent() throws Exception {
        Server s = new Server();
        Device a = new Device(s);
        a.text = "small"; a.sync();
        int saves = s.saves.get();
        StringBuilder big = new StringBuilder();
        java.util.Random r = new java.util.Random(3);
        while (big.length() < 80_000) big.append(Long.toHexString(r.nextLong())).append('\n');
        a.text = big.toString();
        try { a.sync(); fail(); } catch (SyncEngine.TooLargeException expected) { }
        assertEquals(saves, s.saves.get());
        assertEquals("small", serverText(s));
    }

    /** The site keeps something we cannot decrypt after our save: "not saved", not "wrong key". */
    @Test public void saveRejectedKeepsUnsent() throws Exception {
        Server s = new Server();
        Device a = new Device(s);
        a.text = "v1"; a.sync();
        String goodBase = a.baseWire;
        s.afterSave = () -> s.wire = s.wire.substring(0, s.wire.length() / 2); // truncated by site
        a.text = "v1\nv2";
        try { a.sync(); fail(); } catch (SyncEngine.SaveRejectedException expected) { }
        assertEquals(goodBase, a.eng.state.base.wire);       // not marked as sent
        // Retry in the same session: the mangled copy is ours → re-uploaded (nothing else lost).
        a.sync();
        assertEquals("v1\nv2", serverText(s));
        // A fresh engine (restart) does not know it was ours → never overwritten.
        s.wire = "PM2.mangled";
        Device c = new Device(s);
        c.text = "other";
        try { c.sync(); fail(); } catch (SyncEngine.KeyMismatchException expected) { }
        assertEquals("PM2.mangled", s.wire);
    }

    /** A non-merging writer overwrites after every one of our saves: result is flagged unverified. */
    @Test public void endlessInterferenceIsUnverified() throws Exception {
        Server s = new Server();
        Device a = new Device(s);
        a.text = "a"; a.sync();
        final String baseRev = a.eng.state.base.rev;
        final int[] n = {0};
        Runnable[] hook = new Runnable[1];
        hook[0] = () -> {
            try {
                String rev = String.format("%012x", 0xabc000 + n[0]);
                s.wire = Crypto.encrypt(SyncEngine.format(rev, java.util.Collections.singletonList(baseRev),
                        "a\nb" + (n[0]++)), KEY);
            } catch (Exception e) { throw new RuntimeException(e); }
            s.afterSave = hook[0];
        };
        a.text = "a\nmine";
        s.afterSave = hook[0];
        SyncEngine.Result r = a.eng.sync(a.text, a.baseWire);
        s.afterSave = null;
        assertTrue(r.unverified);
        assertEquals(SyncEngine.MAX_ROUNDS, n[0]);
        a.text = r.base.text; a.baseWire = r.base.wire;
        a.sync();
        assertTrue(serverText(s).contains("mine"));
    }

    private void stress(int rounds, int delay, int edits) throws Exception {
        for (int round = 0; round < rounds; round++) {
            Server s = new Server();
            s.maxDelayMs = delay;
            Device a = new Device(s), b = new Device(s);
            List<String> all = java.util.Collections.synchronizedList(new ArrayList<>());
            Thread[] ts = new Thread[2];
            Device[] ds = {a, b};
            for (int k = 0; k < 2; k++) {
                final Device d = ds[k];
                final String tag = (k == 0 ? "A" : "B") + round;
                final Random rnd = new Random(round * 10 + k);
                ts[k] = new Thread(() -> {
                    try {
                        for (int i = 0; i < edits; i++) {
                            List<String> l = new ArrayList<>(java.util.Arrays.asList(Merge3.lines(d.text)));
                            String tok = tag + "-" + i;
                            l.add(rnd.nextInt(l.size() + 1), tok);
                            all.add(tok);
                            d.text = Merge3.join(l);
                            if (rnd.nextInt(3) > 0) d.sync();
                        }
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
            }
            for (Thread t : ts) t.start();
            for (Thread t : ts) t.join();
            s.maxDelayMs = 0;
            for (int i = 0; i < 3; i++) { a.sync(); b.sync(); }
            String fin = serverText(s);
            for (String tok : all) assertTrue("lost " + tok, fin.contains(tok));
            assertEquals(fin, a.text);
            assertEquals(fin, b.text);
        }
    }
}
