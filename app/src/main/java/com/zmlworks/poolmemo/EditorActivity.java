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
import java.util.concurrent.atomic.AtomicBoolean;

/** Full-window dark editor: settings only, 15s idle save, reload on resume. */
public class EditorActivity extends Activity {
    private static final long IDLE_MS = 15_000L;
    /** Default editor size (sp) — a bit larger than v0.1. */
    private static final float EDITOR_SP = 20f;

    private BulletEditText editor;
    private String id, key;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean saving = new AtomicBoolean(false);
    private boolean dirty;
    private boolean loading;
    private boolean resumeReload;
    private final Runnable idleSave = this::saveNow;

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
                if (loading) return;
                dirty = true;
                handler.removeCallbacks(idleSave);
                handler.postDelayed(idleSave, IDLE_MS);
            }
        });
        root.addView(editor, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        Ui.edgeToEdge(this, root);
        loadRemote(false);
    }

    private void goSetup(boolean force) {
        Intent i = new Intent(this, MainActivity.class);
        i.putExtra("force_setup", force);
        startActivity(i);
        finish();
    }

    private void loadRemote(boolean toastOk) {
        io.execute(() -> {
            try {
                String wire = PoolSketch.fetch(id);
                final String plain;
                if (wire == null || wire.trim().isEmpty()) {
                    plain = "";
                    runOnUiThread(() -> applyText(plain));
                    return;
                }
                plain = Crypto.decrypt(wire, key);
                runOnUiThread(() -> {
                    applyText(plain);
                    if (toastOk) Toast.makeText(this, R.string.loaded, Toast.LENGTH_SHORT).show();
                });
            } catch (IllegalArgumentException e) {
                runOnUiThread(() ->
                        Toast.makeText(this, R.string.decrypt_failed, Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this, getString(R.string.load_failed) + ": " + e.getMessage(),
                                Toast.LENGTH_LONG).show());
            }
        });
    }

    private void applyText(String plain) {
        loading = true;
        editor.setText(plain);
        editor.setSelection(Math.min(plain.length(), editor.length()));
        dirty = false;
        loading = false;
    }

    private void saveNow() {
        if (saving.getAndSet(true)) return;
        handler.removeCallbacks(idleSave);
        final String plain = editor.getText().toString();
        io.execute(() -> {
            try {
                String wire = Crypto.encrypt(plain, key);
                PoolSketch.save(id, wire);
                dirty = false;
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this, getString(R.string.save_failed) + ": " + e.getMessage(),
                                Toast.LENGTH_LONG).show());
            } finally {
                saving.set(false);
            }
        });
    }

    /** Save then reload (used when returning to foreground with dirty buffer). */
    private void saveThenReload() {
        if (saving.getAndSet(true)) return;
        handler.removeCallbacks(idleSave);
        final String plain = editor.getText().toString();
        io.execute(() -> {
            try {
                String wire = Crypto.encrypt(plain, key);
                PoolSketch.save(id, wire);
                dirty = false;
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this, getString(R.string.save_failed) + ": " + e.getMessage(),
                                Toast.LENGTH_LONG).show());
            } finally {
                saving.set(false);
                runOnUiThread(() -> loadRemote(false));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (resumeReload) {
            if (dirty) saveThenReload();
            else loadRemote(false);
        }
        resumeReload = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(idleSave);
        if (dirty) saveNow();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (dirty) saveNow();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(idleSave);
        io.shutdownNow();
        super.onDestroy();
    }
}
