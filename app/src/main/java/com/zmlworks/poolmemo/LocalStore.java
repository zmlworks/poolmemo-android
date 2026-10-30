package com.zmlworks.poolmemo;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * App-private, encrypted (memo key, {@link Crypto} format) local copies:
 *  sync-&lt;h&gt;.dat  — last known server base + recent versions (for 3-way merge)
 *  draft-&lt;h&gt;.dat — the editor text not yet confirmed on the server, plus its base wire
 * <p>
 * h = hash(id + key) since 0.4.0, so opening a memo with a different (e.g. mistyped) key
 * uses different files and can never overwrite unsent text saved under the right key.
 * Files of 0.3.x (h = hash(id)) are migrated when they decrypt with the current key and
 * left untouched otherwise (they come back once the right key is entered). A current-name
 * file that cannot be read is moved aside to "*.bad-&lt;time&gt;" instead of being overwritten.
 * <p>
 * All file access goes through one process-wide lock, so several instances (activity
 * re-creation, background job) never interleave.
 */
final class LocalStore {
    private static final Object LOCK = new Object();

    private final File dir;
    private final String id, key, tag, legacyTag;

    LocalStore(File dir, String id, String key) {
        this.dir = dir; this.id = id; this.key = key;
        this.tag = hash(id + "\n" + key.trim());
        this.legacyTag = hash(id);
    }

    static final class Draft {
        final String baseWire, text;
        Draft(String baseWire, String text) { this.baseWire = baseWire; this.text = text; }
        boolean same(Draft o) {
            return o != null && text.equals(o.text)
                    && (baseWire == null ? o.baseWire == null : baseWire.equals(o.baseWire));
        }
    }

    /** What {@link #open()} found. */
    static final class Opened {
        SyncEngine.State state;
        Draft draft;
        /** A 0.3.x file of this ID exists that the current key cannot decrypt (other key). */
        boolean otherKeyData;
        /** Unreadable files that were moved aside (names). */
        final List<String> movedAside = new ArrayList<>();
    }

    /** Loads state + draft, migrating / quarantining files as described above. Slow (crypto). */
    Opened open() {
        synchronized (LOCK) {
            Opened o = new Opened();
            List<String> s = load("sync", o), d = load("draft", o);
            o.state = parseState(s);
            o.draft = parseDraft(d);
            return o;
        }
    }

    private List<String> load(String kind, Opened o) {
        File cur = new File(dir, kind + "-" + tag + ".dat");
        if (cur.exists()) {
            try {
                return read(cur);
            } catch (Exception e) {
                File bad = new File(dir, cur.getName() + ".bad-"
                        + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()));
                if (cur.renameTo(bad)) o.movedAside.add(bad.getName());
                else o.movedAside.add(cur.getName());
                return null;
            }
        }
        File old = new File(dir, kind + "-" + legacyTag + ".dat");
        if (!old.exists()) return null;
        List<String> f;
        try {
            f = read(old);
        } catch (Exception e) {
            o.otherKeyData = true; // keep it: it is readable with the key it was written with
            return null;
        }
        if (write(cur, f)) {
            //noinspection ResultOfMethodCallIgnored
            old.delete();
        }
        return f;
    }

    // ----- state -----

    private SyncEngine.State parseState(List<String> f) {
        try {
            if (f == null || f.size() < 2 || !"S2".equals(f.get(0)) || !id.equals(f.get(1))) return null;
            SyncEngine.State st = new SyncEngine.State();
            int i = 2;
            int baseIdx = Integer.parseInt(f.get(i++));
            int n = Integer.parseInt(f.get(i++));
            for (int k = 0; k < n; k++) {
                String wire = f.get(i++), rev = f.get(i++), leg = f.get(i++), hist = f.get(i++), text = f.get(i++);
                List<String> h = new ArrayList<>();
                if (!hist.isEmpty()) for (String r : hist.split(",")) h.add(r);
                st.snaps.add(new SyncEngine.Snap(wire, rev, h, text, "1".equals(leg)));
            }
            if (baseIdx >= 0 && baseIdx < st.snaps.size()) st.base = st.snaps.get(baseIdx);
            return st;
        } catch (Exception e) {
            return null;
        }
    }

    /** @return false if the file could not be written. */
    boolean saveState(SyncEngine.State st) {
        List<String> f = new ArrayList<>();
        f.add("S2"); f.add(id);
        List<SyncEngine.Snap> snaps = new ArrayList<>(st.snaps);
        int baseIdx = st.base == null ? -1 : snaps.indexOf(st.base);
        if (st.base != null && baseIdx < 0) { snaps.add(0, st.base); baseIdx = 0; }
        f.add(Integer.toString(baseIdx));
        f.add(Integer.toString(snaps.size()));
        for (SyncEngine.Snap s : snaps) {
            f.add(s.wire); f.add(s.rev); f.add(s.legacy ? "1" : "0");
            f.add(String.join(",", s.hist)); f.add(s.text);
        }
        synchronized (LOCK) {
            return write(new File(dir, "sync-" + tag + ".dat"), f);
        }
    }

    // ----- draft -----

    private Draft parseDraft(List<String> f) {
        if (f == null || f.size() != 4 || !"D1".equals(f.get(0)) || !id.equals(f.get(1))) return null;
        return new Draft(f.get(2).isEmpty() ? null : f.get(2), f.get(3));
    }

    Draft loadDraft() {
        synchronized (LOCK) {
            File cur = new File(dir, "draft-" + tag + ".dat");
            try {
                return cur.exists() ? parseDraft(read(cur)) : null;
            } catch (Exception e) {
                return null;
            }
        }
    }

    /** @return false if the file could not be written. */
    boolean saveDraft(String baseWire, String text) {
        synchronized (LOCK) {
            return write(new File(dir, "draft-" + tag + ".dat"), draftFields(baseWire, text));
        }
    }

    /** Replaces the draft only if it is still {@code expected} (used by the background job). */
    boolean replaceDraftIf(Draft expected, String baseWire, String text) {
        synchronized (LOCK) {
            Draft now = loadDraft();
            if (expected == null ? now != null : !expected.same(now)) return false;
            return write(new File(dir, "draft-" + tag + ".dat"), draftFields(baseWire, text));
        }
    }

    private List<String> draftFields(String baseWire, String text) {
        List<String> f = new ArrayList<>();
        f.add("D1"); f.add(id); f.add(baseWire == null ? "" : baseWire); f.add(text);
        return f;
    }

    // ----- io -----

    private List<String> read(File file) throws Exception {
        String wire = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        return unpack(Crypto.decrypt(wire, key));
    }

    private boolean write(File dst, List<String> fields) {
        File tmp = new File(dir, dst.getName() + ".tmp");
        try {
            String wire = Crypto.encrypt(pack(fields), key);
            try (FileOutputStream os = new FileOutputStream(tmp)) {
                os.write(wire.getBytes(StandardCharsets.UTF_8));
                os.getFD().sync();
            }
            // File.renameTo replaces atomically on Android (rename(2)).
            if (tmp.renameTo(dst)) return true;
        } catch (Exception ignored) {
            // reported to the caller
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();
        return false;
    }

    static String pack(List<String> fields) {
        StringBuilder sb = new StringBuilder();
        for (String s : fields) sb.append(s.length()).append(':').append(s);
        return sb.toString();
    }

    static List<String> unpack(String s) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            int c = s.indexOf(':', i);
            int n = Integer.parseInt(s.substring(i, c));
            out.add(s.substring(c + 1, c + 1 + n));
            i = c + 1 + n;
        }
        return out;
    }

    static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i] & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return "x";
        }
    }
}
