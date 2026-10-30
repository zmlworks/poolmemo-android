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
    private static final long DRAFT_MS = 1_000L;
    private static final long VERIFY_MS = 8_000L;
    private static final long RETRY_MS = 30_000L;
    private static final long TOAST_GAP_MS = 5 * 60_000L;

    private MemoSession session;
    /** Set once the local copy is shown (loading decrypts off the UI thread). */
    private SyncEngine engine;
    /** Last sync failed / was not confirmed: text may not be on the server yet. */
    private boolean pending;
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
        editor.setHint(R.string.loading);
        editor.setEnabled(false);
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
        session = MemoSession.get(this, id, key);
        openLocal();
    }

    private void goSetup(boolean force) {
        Intent i = new Intent(this, MainActivity.class);
        i.putExtra("force_setup", force);
        startActivity(i);
        finish();
    }

    /** Show the local copy (unsent draft or last synced text) as soon as it is decrypted. */
    private void openLocal() {
        final MemoSession ses = session;
        MemoSession.NET.execute(() -> {
            String text;
            String baseWire = null, baseText = "";
            LocalStore.Opened report;
            try {
                SyncEngine eng = ses.engine();
                report = ses.takeOpenReport();
                SyncEngine.Snap base = eng.base();
                text = "";
                if (base != null) { baseWire = base.wire; baseText = base.text; text = base.text; }
                LocalStore.Draft d = ses.store.loadDraft();
                if (d != null) {
                    if (d.baseWire != null && d.baseWire.equals(baseWire)) {
                        text = d.text;
                    } else {
                        // Draft was based on an older/unknown server version: merge, never drop.
                        SyncEngine.Snap db = eng.findByWire(d.baseWire);
                        text = Merge3.merge(db != null ? db.text : "", d.text, text);
                    }
                }
            } catch (RuntimeException e) {
                handler.post(() -> {
                    if (!isDestroyed()) toast(getString(R.string.load_failed) + ": " + e.getMessage(), true);
                });
                return;
            }
            final String ft = text, fbw = baseWire, fbt = baseText;
            final LocalStore.Opened fr = report;
            handler.post(() -> onLocalLoaded(ses, ft, fbw, fbt, fr));
        });
    }

    private void onLocalLoaded(MemoSession ses, String text, String baseWire, String baseText,
                               LocalStore.Opened report) {
        if (isDestroyed() || ses != session) return;
        engine = ses.engine(); // already created: returns immediately
        editorBaseWire = baseWire;
        editorBaseText = baseText;
        applying = true;
        editor.setText(text);
        editor.setSelection(0);
        applying = false;
        editor.setHint("メモを入力…");
        editor.setEnabled(true);
        if (report != null) {
            if (report.otherKeyData) {
                new android.app.AlertDialog.Builder(this)
                        .setTitle(R.string.other_key_title)
                        .setMessage(R.string.other_key_msg)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
            if (!report.movedAside.isEmpty()) {
                toast(getString(R.string.moved_aside, String.join(", ", report.movedAside)), true);
            }
        }
        requestSync();
    }

    private boolean dirty() {
        return !editor.getText().toString().equals(editorBaseText);
    }

    private void persistDraft() {
        handler.removeCallbacks(draftSave);
        if (engine == null || editor == null) return;
        final LocalStore st = session.store;
        final String bw = editorBaseWire, text = editor.getText().toString();
        MemoSession.DISK.execute(() -> {
            if (!st.saveDraft(bw, text)) {
                handler.post(() -> {
                    if (!isDestroyed()) toast(getString(R.string.local_save_failed), false);
                    else Toast.makeText(getApplicationContext(), R.string.local_save_failed, Toast.LENGTH_LONG).show();
                });
            }
        });
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
        MemoSession.NET.execute(() -> {
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
            if (r.unverified) {
                // Other devices kept writing: our text is not confirmed on the server yet.
                pending = true;
                handler.removeCallbacks(idleSave);
                handler.postDelayed(idleSave, RETRY_MS);
            } else {
                pending = false;
                if (r.wrote) {
                    handler.removeCallbacks(verify);
                    handler.postDelayed(verify, VERIFY_MS);
                }
            }
        } else if (e != null) {
            pending = true;
            persistDraft();
            if (e instanceof SyncEngine.KeyMismatchException) {
                toast(getString(R.string.decrypt_failed), true);
            } else if (e instanceof SyncEngine.SaveRejectedException) {
                toast(getString(R.string.save_rejected), true);
                handler.removeCallbacks(idleSave);
                handler.postDelayed(idleSave, RETRY_MS);
            } else if (e instanceof SyncEngine.TooLargeException) {
                toast(getString(R.string.too_large, ((SyncEngine.TooLargeException) e).length,
                        SyncEngine.MAX_WIRE), true);
            } else {
                toast(getString(R.string.save_failed) + ": " + e.getMessage(), false);
                handler.removeCallbacks(idleSave);
                handler.postDelayed(idleSave, RETRY_MS);
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
        if (dirty() || pending) {
            requestSync();
            // Safety net if the process goes away before that sync finishes / succeeds.
            SyncJobService.schedule(getApplicationContext());
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (engine != null) persistDraft(); // queued on the shared executor; outlives us
        super.onDestroy();
    }
}
