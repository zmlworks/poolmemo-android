package com.zmlworks.poolmemo;

import org.junit.Assume;
import org.junit.Test;

import static org.junit.Assert.*;

/** Real plsk.net, throwaway memo only: run with PM_LIVE_ID=<new id> PM_LIVE_KEY=<new key>. */
public class LiveTest {
    static SyncEngine dev(String id, String key) {
        return new SyncEngine(new SyncEngine.Remote() {
            @Override public String fetch() throws Exception { return PoolSketch.fetch(id); }
            @Override public void save(String w) throws Exception { PoolSketch.save(id, w); }
        }, new SyncEngine.Codec() {
            @Override public String encrypt(String p) throws Exception { return Crypto.encrypt(p, key); }
            @Override public String decrypt(String w) throws Exception { return Crypto.decrypt(w, key); }
        }, st -> { }, null);
    }

    @Test public void twoDevicesLive() throws Exception {
        String id = System.getenv("PM_LIVE_ID"), key = System.getenv("PM_LIVE_KEY");
        Assume.assumeTrue(id != null && id.startsWith("pmtest") && key != null);
        SyncEngine a = dev(id, key), b = dev(id, key);
        SyncEngine.Result ra = a.sync("live 1\nlive 2\nlive 3", null);
        SyncEngine.Result rb = b.sync("", null);
        assertEquals("live 1\nlive 2\nlive 3", rb.base.text);
        // different lines, sequential
        ra = a.sync("live 1 A\nlive 2\nlive 3", ra.base.wire);
        rb = b.sync("live 1\nlive 2\nlive 3 B", rb.base.wire);
        assertEquals("live 1 A\nlive 2\nlive 3 B", rb.base.text);
        // truly concurrent: both threads save at the same time
        final String baseA = a.sync(rb.base.text, ra.base.wire).base.wire;
        final String baseB = rb.base.wire;
        final String[] err = new String[1];
        Thread t1 = new Thread(() -> { try { a.sync("live 1 A\nlive 2 AA\nlive 3 B", baseA); } catch (Exception e) { err[0] = e.toString(); } });
        Thread t2 = new Thread(() -> { try { b.sync("live 1 A\nlive 2\nlive 3 B\nlive 4 BB", baseB); } catch (Exception e) { err[0] = e.toString(); } });
        t1.start(); t2.start(); t1.join(); t2.join();
        assertNull(err[0]);
        Thread.sleep(1500);
        String fa = a.sync(a.state.base.text, a.state.base.wire).base.text;
        String fb = b.sync(b.state.base.text, b.state.base.wire).base.text;
        fa = a.sync(fa, a.state.base.wire).base.text;
        System.out.println("LIVE final:\n" + fa);
        assertEquals(fa, fb);
        assertTrue(fa.contains("live 2 AA"));
        assertTrue(fa.contains("live 4 BB"));
        // same line conflict
        SyncEngine.Result x = a.sync(fa.replace("live 2 AA", "two by A"), a.state.base.wire);
        SyncEngine.Result y = b.sync(fb.replace("live 2 AA", "two by B"), fb.equals(b.state.base.text) ? b.state.base.wire : null);
        System.out.println("LIVE conflict:\n" + y.base.text);
        assertTrue(y.conflict);
        assertTrue(y.base.text.contains("two by A") && y.base.text.contains("two by B"));
        assertTrue(x.wrote);
    }
}
