package com.zmlworks.poolmemo;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * App-private, encrypted (memo key, PM1 format) local copies:
 *  sync-&lt;h&gt;.dat  — last known server base + recent versions (for 3-way merge)
 *  draft-&lt;h&gt;.dat — the editor text not yet confirmed on the server, plus its base wire
 */
final class LocalStore {
    private final File dir;
    private final String id, key, tag;

    LocalStore(File dir, String id, String key) {
        this.dir = dir; this.id = id; this.key = key;
        this.tag = hash(id);
    }

    static final class Draft {
        final String baseWire, text;
        Draft(String baseWire, String text) { this.baseWire = baseWire; this.text = text; }
    }

    // ----- state -----

    SyncEngine.State loadState() {
        try {
            List<String> f = read("sync-" + tag + ".dat");
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

    void saveState(SyncEngine.State st) {
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
        write("sync-" + tag + ".dat", f);
    }

    // ----- draft -----

    Draft loadDraft() {
        try {
            List<String> f = read("draft-" + tag + ".dat");
            if (f == null || f.size() != 4 || !"D1".equals(f.get(0)) || !id.equals(f.get(1))) return null;
            return new Draft(f.get(2).isEmpty() ? null : f.get(2), f.get(3));
        } catch (Exception e) {
            return null;
        }
    }

    void saveDraft(String baseWire, String text) {
        List<String> f = new ArrayList<>();
        f.add("D1"); f.add(id); f.add(baseWire == null ? "" : baseWire); f.add(text);
        write("draft-" + tag + ".dat", f);
    }

    // ----- io -----

    private List<String> read(String name) throws Exception {
        File file = new File(dir, name);
        if (!file.exists()) return null;
        String wire = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        return unpack(Crypto.decrypt(wire, key));
    }

    private synchronized void write(String name, List<String> fields) {
        try {
            String wire = Crypto.encrypt(pack(fields), key);
            File tmp = new File(dir, name + ".tmp");
            try (FileOutputStream os = new FileOutputStream(tmp)) {
                os.write(wire.getBytes(StandardCharsets.UTF_8));
                os.getFD().sync();
            }
            File dst = new File(dir, name);
            if (!tmp.renameTo(dst)) {
                //noinspection ResultOfMethodCallIgnored
                dst.delete();
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(dst);
            }
        } catch (Exception ignored) {
            // best effort; the editor still holds the text
        }
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

    private static String hash(String s) {
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
