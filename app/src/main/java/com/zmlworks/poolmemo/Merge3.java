package com.zmlworks.poolmemo;

import java.util.ArrayList;
import java.util.List;

/**
 * Line-based 3-way merge that never drops text.
 * <ul>
 *   <li>Changes in different places merge automatically.</li>
 *   <li>Both sides inserting at the same place: both kept (local first, then other).</li>
 *   <li>Same lines changed differently: both versions kept, separated by {@link #MARKER}.</li>
 *   <li>One side deleted lines the other side edited: the edit is kept.</li>
 * </ul>
 * Must produce byte-identical output to merge3.h (Windows).
 */
final class Merge3 {
    /** Line inserted between "this device" and "other device" versions on a real conflict. */
    static final String MARKER = "〔同時編集：上＝この端末／下＝別の端末〕";
    /** Above this many DP cells the changed middle is treated as one block (still lossless). */
    static final long MAX_CELLS = 1_500_000L;

    private Merge3() {}

    static String merge(String base, String local, String other) {
        if (local.equals(other)) return local;
        if (base.equals(local)) return other;
        if (base.equals(other)) return local;
        String[] o = lines(base), a = lines(local), b = lines(other);
        List<int[]> ha = diff(o, a), hb = diff(o, b);
        List<String> out = new ArrayList<>();
        int ia = 0, ib = 0, pos = 0;
        while (ia < ha.size() || ib < hb.size()) {
            boolean takeA;
            if (ia >= ha.size()) takeA = false;
            else if (ib >= hb.size()) takeA = true;
            else takeA = before(ha.get(ia), hb.get(ib));
            int[] first = takeA ? ha.get(ia) : hb.get(ib);
            int ros = first[0], roe = first[1];
            int sa = ia, sb = ib;
            if (takeA) ia++; else ib++;
            // grow region with overlapping hunks from either side
            boolean grew = true;
            while (grew) {
                grew = false;
                while (ia < ha.size() && overlaps(ha.get(ia), ros, roe)) { roe = Math.max(roe, ha.get(ia)[1]); ia++; grew = true; }
                while (ib < hb.size() && overlaps(hb.get(ib), ros, roe)) { roe = Math.max(roe, hb.get(ib)[1]); ib++; grew = true; }
            }
            for (int k = pos; k < ros; k++) out.add(o[k]);
            List<String> ca = side(o, a, ha, sa, ia, ros, roe);
            List<String> cb = side(o, b, hb, sb, ib, ros, roe);
            if (sa == ia) out.addAll(cb);
            else if (sb == ib) out.addAll(ca);
            else conflict(out, ca, cb, ros == roe);
            pos = roe;
        }
        for (int k = pos; k < o.length; k++) out.add(o[k]);
        return join(out);
    }

    private static boolean before(int[] x, int[] y) {
        if (x[0] != y[0]) return x[0] < y[0];
        return x[1] <= y[1]; // insertion (empty range) first; ties prefer local
    }

    private static boolean overlaps(int[] h, int ros, int roe) {
        if (h[0] < roe) return true;
        // two insertions at exactly the same point
        return h[0] == h[1] && ros == roe && h[0] == ros;
    }

    private static List<String> side(String[] o, String[] x, List<int[]> hs, int from, int to, int ros, int roe) {
        List<String> r = new ArrayList<>();
        int p = ros;
        for (int i = from; i < to; i++) {
            int[] h = hs.get(i);
            for (int k = p; k < h[0]; k++) r.add(o[k]);
            for (int k = h[2]; k < h[3]; k++) r.add(x[k]);
            p = h[1];
        }
        for (int k = p; k < roe; k++) r.add(o[k]);
        return r;
    }

    private static void conflict(List<String> out, List<String> ca, List<String> cb, boolean insertion) {
        if (ca.equals(cb)) { out.addAll(ca); return; }
        if (ca.isEmpty()) { out.addAll(cb); return; }
        if (cb.isEmpty()) { out.addAll(ca); return; }
        int pre = 0;
        while (pre < ca.size() && pre < cb.size() && ca.get(pre).equals(cb.get(pre))) pre++;
        int suf = 0;
        while (suf < ca.size() - pre && suf < cb.size() - pre
                && ca.get(ca.size() - 1 - suf).equals(cb.get(cb.size() - 1 - suf))) suf++;
        out.addAll(ca.subList(0, pre));
        List<String> ma = ca.subList(pre, ca.size() - suf), mb = cb.subList(pre, cb.size() - suf);
        out.addAll(ma);
        if (!insertion && !ma.isEmpty() && !mb.isEmpty()) out.add(MARKER);
        out.addAll(mb);
        out.addAll(ca.subList(ca.size() - suf, ca.size()));
    }

    static String[] lines(String s) {
        if (s.isEmpty()) return new String[0];
        return s.split("\n", -1);
    }

    static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(l.get(i));
        }
        return sb.toString();
    }

    /** Hunks {oStart,oEnd,xStart,xEnd} turning o into x, ascending. */
    static List<int[]> diff(String[] o, String[] x) {
        int n = o.length, m = x.length;
        int pre = 0;
        while (pre < n && pre < m && o[pre].equals(x[pre])) pre++;
        int suf = 0;
        while (suf < n - pre && suf < m - pre && o[n - 1 - suf].equals(x[m - 1 - suf])) suf++;
        int on = n - pre - suf, xn = m - pre - suf;
        List<int[]> hunks = new ArrayList<>();
        if (on == 0 && xn == 0) return hunks;
        if (on == 0 || xn == 0 || (long) on * xn > MAX_CELLS) {
            hunks.add(new int[]{pre, pre + on, pre, pre + xn});
            return hunks;
        }
        // LCS table on the middle, suffix-oriented so we can walk forward
        int[] t = new int[(on + 1) * (xn + 1)];
        int w = xn + 1;
        for (int i = on - 1; i >= 0; i--) {
            for (int j = xn - 1; j >= 0; j--) {
                if (o[pre + i].equals(x[pre + j])) t[i * w + j] = t[(i + 1) * w + j + 1] + 1;
                else t[i * w + j] = Math.max(t[(i + 1) * w + j], t[i * w + j + 1]);
            }
        }
        int i = 0, j = 0, hs = -1, hx = -1;
        while (i < on || j < xn) {
            if (i < on && j < xn && o[pre + i].equals(x[pre + j])) {
                if (hs >= 0) { hunks.add(new int[]{pre + hs, pre + i, pre + hx, pre + j}); hs = -1; }
                i++; j++;
            } else {
                if (hs < 0) { hs = i; hx = j; }
                if (j >= xn || (i < on && t[(i + 1) * w + j] >= t[i * w + j + 1])) i++;
                else j++;
            }
        }
        if (hs >= 0) hunks.add(new int[]{pre + hs, pre + on, pre + hx, pre + xn});
        return hunks;
    }
}
