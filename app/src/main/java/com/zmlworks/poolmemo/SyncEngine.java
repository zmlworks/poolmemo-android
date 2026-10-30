package com.zmlworks.poolmemo;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Conflict-safe sync for one memo. PoolSketch has no revisions / ETag / conditional
 * write, so every saved plaintext carries a small header line with a random revision
 * id and the ids of its ancestors:
 * <pre>#pmsync1 &lt;rev12hex&gt; &lt;anc,anc,...|-&gt;\n&lt;memo text&gt;</pre>
 * The header lives inside the AES-GCM ciphertext and is never shown in the editor.
 * <p>
 * sync(): fetch → find the common ancestor (via revision history, falling back to
 * the last known base) → 3-way merge → write only if something changed → re-fetch to
 * verify; if another device wrote meanwhile, merge again. Nothing is ever dropped:
 * conflicting edits are kept side by side (see {@link Merge3}).
 * Not thread-safe by itself; callers serialise (synchronized).
 */
final class SyncEngine {
    static final String HDR = "#pmsync1 ";
    static final int MAX_HIST = 32;
    static final int MAX_SNAPS = 8;
    static final int MAX_ROUNDS = 4;

    interface Remote {
        String fetch() throws Exception;
        void save(String wire) throws Exception;
    }

    interface Codec {
        String encrypt(String plain) throws Exception;
        /** "" → "". Throws if not decryptable (wrong key / not ours). */
        String decrypt(String wire) throws Exception;
    }

    interface Persist {
        void save(State st);
    }

    /** A server version we know about. */
    static final class Snap {
        final String wire, rev, text;
        final List<String> hist;
        /** No header (written by v0.2.x or empty): rev is a hash of the wire. */
        final boolean legacy;
        Snap(String wire, String rev, List<String> hist, String text, boolean legacy) {
            this.wire = wire; this.rev = rev; this.hist = hist; this.text = text; this.legacy = legacy;
        }
    }

    static final class State {
        Snap base;                         // last server version the local copy is based on
        final List<Snap> snaps = new ArrayList<>(); // recent known versions, newest first
    }

    static final class Result {
        Snap base;          // server version now matching base
        String synced;      // the local text that was passed in (UI rebases edits made since)
        boolean wrote;
        boolean conflict;   // a conflict marker was added
    }

    private final Remote remote;
    private final Codec codec;
    private final Persist persist;
    final State state;
    private final SecureRandom rnd = new SecureRandom();

    SyncEngine(Remote remote, Codec codec, Persist persist, State state) {
        this.remote = remote; this.codec = codec; this.persist = persist;
        this.state = state != null ? state : new State();
    }

    // ---------- header ----------

    static Snap parse(String wire, String plain) {
        if (plain.startsWith(HDR)) {
            int nl = plain.indexOf('\n');
            String line = nl < 0 ? plain : plain.substring(0, nl);
            String[] f = line.substring(HDR.length()).split(" ", -1);
            if (f.length == 2 && isRev(f[0])) {
                List<String> h = new ArrayList<>();
                boolean ok = true;
                if (!f[1].equals("-")) {
                    for (String r : f[1].split(",", -1)) { if (isRev(r)) h.add(r); else { ok = false; break; } }
                }
                if (ok) return new Snap(wire, f[0], h, nl < 0 ? "" : plain.substring(nl + 1), false);
            }
        }
        return new Snap(wire, pseudoRev(wire), Collections.emptyList(), plain, true);
    }

    /** Stable id for a header-less server version, so it can appear in histories. */
    static String pseudoRev(String wire) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(wire.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", d[i] & 0xff));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static String format(String rev, List<String> hist, String text) {
        StringBuilder sb = new StringBuilder(HDR).append(rev).append(' ');
        if (hist.isEmpty()) sb.append('-');
        for (int i = 0; i < hist.size(); i++) { if (i > 0) sb.append(','); sb.append(hist.get(i)); }
        return sb.append('\n').append(text).toString();
    }

    private static boolean isRev(String s) {
        if (s.length() != 12) return false;
        for (int i = 0; i < 12; i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    private String newRev() {
        byte[] b = new byte[6];
        rnd.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte v : b) sb.append(String.format("%02x", v & 0xff));
        return sb.toString();
    }

    // ---------- state helpers ----------

    Snap findByWire(String wire) {
        if (wire == null) return null;
        for (Snap s : state.snaps) if (wire.equals(s.wire)) return s;
        return null;
    }

    private void remember(Snap s) {
        if (s == null || s.wire == null) return;
        for (int i = 0; i < state.snaps.size(); i++) {
            if (state.snaps.get(i).wire.equals(s.wire)) { state.snaps.remove(i); break; }
        }
        state.snaps.add(0, s);
        while (state.snaps.size() > MAX_SNAPS) state.snaps.remove(state.snaps.size() - 1);
    }

    /**
     * Common ancestor of the server version x and our local copy: the newest version we
     * know that is x itself or in x's history. Unknown header-less x (an old client
     * edited what it loaded) → our base. Otherwise unknown → "" (keeps both, loses none).
     */
    private String ancestorFor(Snap x) {
        Snap base = state.base;
        Set<String> anc = new HashSet<>(x.hist);
        anc.add(x.rev);
        if (base != null && anc.contains(base.rev)) return base.text;
        for (Snap s : state.snaps) if (anc.contains(s.rev)) return s.text;
        if (x.legacy) return base != null ? base.text : "";
        return "";
    }

    private static int count(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); }
        return n;
    }

    /** Local text as rebased onto the current state.base (for a UI that lags behind). */
    String rebase(String local, String localBaseWire) {
        Snap base = state.base;
        if (base == null) return local;
        if (localBaseWire != null && localBaseWire.equals(base.wire)) return local;
        Snap lb = findByWire(localBaseWire);
        return Merge3.merge(lb != null ? lb.text : "", local, base.text);
    }

    // ---------- sync ----------

    synchronized Result sync(String local, String localBaseWire) throws Exception {
        Result res = new Result();
        res.synced = local;
        String L = rebase(local, localBaseWire);
        String prefetched = null;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            String xw = prefetched != null ? prefetched : norm(remote.fetch());
            prefetched = null;
            Snap base = state.base;
            Snap x;
            String ancestor;
            if (base != null && xw.equals(base.wire)) {
                if (L.equals(base.text)) { res.base = base; return res; }
                x = base;
                ancestor = base.text;
            } else {
                x = parse(xw, codec.decrypt(xw)); // throws on foreign/wrong-key content: never overwrite
                ancestor = ancestorFor(x);
            }
            // Server copy vanished (expired / wiped) while we know content: re-upload ours.
            boolean lost = xw.isEmpty() && base != null && !base.text.isEmpty();
            String r = lost ? L : Merge3.merge(ancestor, L, x.text);
            if (count(r, Merge3.MARKER) > Math.max(count(L, Merge3.MARKER), count(x.text, Merge3.MARKER)))
                res.conflict = true;
            if (!lost && r.equals(x.text)) {
                remember(x);
                state.base = x;
                persist.save(state);
                res.base = x;
                return res;
            }
            LinkedHashSet<String> h = new LinkedHashSet<>();
            h.add(x.rev);
            h.addAll(x.hist);
            if (base != null) { h.add(base.rev); h.addAll(base.hist); }
            List<String> hist = new ArrayList<>(h);
            while (hist.size() > MAX_HIST) hist.remove(hist.size() - 1);
            String rev = newRev();
            String wire = codec.encrypt(format(rev, hist, r));
            Snap m = new Snap(wire, rev, hist, r, false);
            remember(x);
            remote.save(wire);
            remember(m);
            state.base = m;
            persist.save(state);
            res.wrote = true;
            res.base = m;
            String vw;
            try {
                vw = norm(remote.fetch());
            } catch (Exception e) {
                return res; // written; the delayed re-check will verify later
            }
            if (vw.equals(wire)) return res;
            // Another device wrote around the same time: merge again on top of it.
            L = r;
            prefetched = vw;
        }
        return res;
    }

    static String norm(String w) {
        return w == null ? "" : w.trim();
    }
}
