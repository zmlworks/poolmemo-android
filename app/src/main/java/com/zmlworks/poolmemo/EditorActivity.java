package com.zmlworks.poolmemo;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-window dark editor: settings only. Sync (merge-safe, see {@link SyncEngine}) on
 * 15 s idle, pause/stop and resume. Unsent text is kept in an encrypted local draft.
 */
public class EditorActivity extends Activity {
    private static final long IDLE_MS = 15_000L;
    /** Default editor size (sp) — a bit larger than v0.1. */
    private static final float EDITOR_SP = 20f;

    private BulletEditText editor;
    private String id, key;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private static final long DRAFT_MS = 1_000L;
    private static final long VERIFY_MS = 8_000L;
    private static final long RETRY_MS = 30_000L;
    private static final long TOAST_GAP_MS = 5 * 60_000L;

    private LocalStore store;
    private SyncEngine engine;
    /** Wire of the server version the editor text is based on (null = none yet). */
    private String editorBaseWire;
    private String editorBaseText = "";
    private boolean syncing, syncAgain;
    private boolean applying;
    private boolean resumeReload;
    private long lastErrToast;
    private final Runnable idleSave = this::requestSync;
    private final Runnable draftSave = this::persistDraft;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        id = Prefs.getId(this);
        key = Prefs.getKey(this);
        if (id == null || id.isEmpty() || key == null || key.isEmpty()) {
            goSetup(true);
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Ui.BG2);
        int p = Ui.dp(this, 8);
        bar.setPadding(p, p, p, p);

        Button set = new Button(this);
        set.setText(R.string.settings);
        set.setOnClickListener(v -> goSetup(true));
        bar.addView(set);
        root.addView(bar);

        editor = new BulletEditText(this);
        editor.setBackgroundColor(Ui.EDIT_BG);
        editor.setTextColor(Ui.TEXT);
        editor.setHintTextColor(Ui.DIM);
        editor.setHint("メモを入力…");
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setPadding(0, p, p, p);
        editor.setTextSize(EDITOR_SP);
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (applying) return;
                handler.removeCallbacks(idleSave);
                handler.postDelayed(idleSave, IDLE_MS);
                handler.removeCallbacks(draftSave);
                handler.postDelayed(draftSave, DRAFT_MS);
            }
        });
        root.addView(editor, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        Ui.edgeToEdge(this, root);
        openLocal();
        requestSync();
    }

    private void goSetup(boolean force) {
        Intent i = new Intent(this, MainActivity.class);
        i.putExtra("force_setup", force);
        startActivity(i);
        finish();
    }

    /** Show the local copy (unsent draft or last synced text) immediately. */
    private void openLocal() {
        final String fid = id, fkey = key;
        store = new LocalStore(getFilesDir(), fid, fkey);
        SyncEngine.State st = store.loadState();
        engine = new SyncEngine(new SyncEngine.Remote() {
            @Override public String fetch() throws Exception { return PoolSketch.fetch(fid); }
            @Override public void save(String wire) throws Exception { PoolSketch.save(fid, wire); }
        }, new SyncEngine.Codec() {
            @Override public String encrypt(String plain) throws Exception { return Crypto.encrypt(plain, fkey); }
            @Override public String decrypt(String wire) throws Exception { return Crypto.decrypt(wire, fkey); }
        }, store::saveState, st);
        String text = "";
        editorBaseWire = null;
        editorBaseText = "";
        if (st != null && st.base != null) {
            editorBaseWire = st.base.wire;
            editorBaseText = st.base.text;
            text = st.base.text;
        }
        LocalStore.Draft d = store.loadDraft();
        if (d != null) {
            if (d.baseWire != null && d.baseWire.equals(editorBaseWire)) {
                text = d.text;
            } else {
                // Draft was based on an older/unknown server version: merge, never drop.
                SyncEngine.Snap db = engine.findByWire(d.baseWire);
                text = Merge3.merge(db != null ? db.text : "", d.text, text);
            }
        }
        applying = true;
        editor.setText(text);
        editor.setSelection(0);
        applying = false;
    }

    private boolean dirty() {
        return !editor.getText().toString().equals(editorBaseText);
    }

    private void persistDraft() {
        handler.removeCallbacks(draftSave);
        if (store == null || editor == null) return;
        store.saveDraft(editorBaseWire, editor.getText().toString());
    }

    /** Fetch, merge, write if needed, verify. Coalesces concurrent requests. */
    private void requestSync() {
        if (engine == null) return;
        handler.removeCallbacks(idleSave);
        if (syncing) { syncAgain = true; return; }
        syncing = true;
        syncAgain = false;
        final String local = editor.getText().toString();
        final String baseWire = editorBaseWire;
        final SyncEngine eng = engine;
        io.execute(() -> {
            SyncEngine.Result r = null;
            Exception err = null;
            try {
                r = eng.sync(local, baseWire);
            } catch (Exception e) {
                err = e;
            }
            final SyncEngine.Result fr = r;
            final Exception fe = err;
            handler.post(() -> onSynced(eng, fr, fe));
        });
    }

    private void onSynced(SyncEngine eng, SyncEngine.Result r, Exception e) {
        syncing = false;
        if (eng != engine || isDestroyed()) return;
        if (r != null && r.base != null) {
            String current = editor.getText().toString();
            // Edits typed while the sync ran are rebased on top of the merged result.
            String next = current.equals(r.synced) ? r.base.text
                    : Merge3.merge(r.synced, current, r.base.text);
            editorBaseWire = r.base.wire;
            editorBaseText = r.base.text;
            replaceKeepingCursor(next);
            persistDraft();
            if (r.conflict) toast(getString(R.string.merged_conflict), true);
            if (r.wrote) {
                handler.removeCallbacks(verify);
                handler.postDelayed(verify, VERIFY_MS);
            }
        } else if (e != null) {
            persistDraft();
            if (e instanceof IllegalArgumentException || e instanceof javax.crypto.AEADBadTagException) {
                toast(getString(R.string.decrypt_failed), true);
            } else {
                toast(getString(R.string.save_failed) + ": " + e.getMessage(), false);
                if (dirty()) handler.postDelayed(idleSave, RETRY_MS);
            }
        }
        if (syncAgain) requestSync(); // a trigger arrived while syncing (pause, resume, …)
    }

    private final Runnable verify = this::requestSync;

    private void toast(String msg, boolean always) {
        long now = System.currentTimeMillis();
        if (!always && now - lastErrToast < TOAST_GAP_MS) return;
        lastErrToast = now;
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    /** Replace only the changed middle so cursor, scroll and IME stay where they are. */
    private void replaceKeepingCursor(String next) {
        Editable ed = editor.getText();
        String cur = ed.toString();
        if (cur.equals(next)) return;
        int pre = 0, max = Math.min(cur.length(), next.length());
        while (pre < max && cur.charAt(pre) == next.charAt(pre)) pre++;
        int suf = 0;
        while (suf < cur.length() - pre && suf < next.length() - pre
                && cur.charAt(cur.length() - 1 - suf) == next.charAt(next.length() - 1 - suf)) suf++;
        applying = true;
        try {
            ed.replace(pre, cur.length() - suf, next, pre, next.length() - suf);
        } finally {
            applying = false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (resumeReload) requestSync(); // reload = merge, never blind overwrite
        resumeReload = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (engine == null) return;
        persistDraft();
        if (dirty()) requestSync();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (engine == null) return;
        persistDraft();
        if (dirty()) requestSync();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (engine != null) persistDraft();
        io.shutdown(); // let a running save finish
        super.onDestroy();
    }
}
