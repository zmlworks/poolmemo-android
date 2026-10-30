package com.zmlworks.poolmemo;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Open / create memo: PoolSketch ID + encryption key. */
public class MainActivity extends Activity {
    private EditText idBox, keyBox;
    private TextView hint;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String existingId = Prefs.getId(this);
        String existingKey = Prefs.getKey(this);
        if (existingId != null && !existingId.isEmpty() && existingKey != null && !existingKey.isEmpty()
                && getIntent().getBooleanExtra("force_setup", false) == false) {
            startActivity(new Intent(this, EditorActivity.class));
            finish();
            return;
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        root.setPadding(p, p, p, p);

        TextView title = new TextView(this);
        title.setText(R.string.open_or_create);
        title.setTextColor(Ui.TEXT);
        title.setTextSize(20);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("暗号化クラウドメモ（PoolSketch）");
        sub.setTextColor(Ui.DIM);
        sub.setTextSize(13);
        sub.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 16));
        root.addView(sub);

        root.addView(label(R.string.pool_id));
        idBox = field(false);
        idBox.setHint(R.string.id_hint);
        if (existingId != null) idBox.setText(existingId);
        root.addView(idBox, lp());

        root.addView(label(R.string.enc_key));
        keyBox = field(false);
        keyBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        if (existingKey != null) keyBox.setText(existingKey);
        root.addView(keyBox, lp());

        hint = new TextView(this);
        hint.setTextColor(Ui.DIM);
        hint.setTextSize(12);
        hint.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        root.addView(hint);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button gen = new Button(this);
        gen.setText(R.string.generate);
        gen.setOnClickListener(v -> generate());
        row.addView(gen, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button copy = new Button(this);
        copy.setText(R.string.copy_key);
        copy.setOnClickListener(v -> copyKey());
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mlp.setMarginStart(Ui.dp(this, 8));
        row.addView(copy, mlp);
        root.addView(row);

        Button open = new Button(this);
        open.setText(R.string.open);
        open.setOnClickListener(v -> openMemo());
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.topMargin = Ui.dp(this, 12);
        root.addView(open, olp);

        // recent
        java.util.List<String> recent = Prefs.recentIds(this);
        if (!recent.isEmpty()) {
            TextView rh = new TextView(this);
            rh.setText(R.string.recent);
            rh.setTextColor(Ui.DIM);
            rh.setPadding(0, Ui.dp(this, 20), 0, Ui.dp(this, 6));
            root.addView(rh);
            for (String rid : recent) {
                Button btn = new Button(this);
                btn.setText(rid);
                btn.setOnClickListener(v -> idBox.setText(rid));
                root.addView(btn, lp());
            }
        }

        scroll.addView(root);
        setContentView(scroll);
        Ui.edgeToEdge(this, scroll);
    }

    private TextView label(int res) {
        TextView t = new TextView(this);
        t.setText(res);
        t.setTextColor(Ui.DIM);
        t.setTextSize(12);
        t.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 2));
        return t;
    }

    private EditText field(boolean multi) {
        EditText e = new EditText(this);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.DIM);
        e.setBackgroundColor(Ui.EDIT_BG);
        e.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        e.setSingleLine(!multi);
        return e;
    }

    private LinearLayout.LayoutParams lp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = Ui.dp(this, 4);
        return p;
    }

    private void generate() {
        String id = Crypto.generateId();
        String key = Crypto.generateKeyB64();
        idBox.setText(id);
        keyBox.setText(key);
        hint.setText(R.string.key_hint);
        Toast.makeText(this, R.string.key_generated, Toast.LENGTH_LONG).show();
    }

    private void copyKey() {
        String k = keyBox.getText().toString();
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("key", k);
        // Hide the key from the clipboard preview / keyboard suggestions (Android 13+).
        android.os.PersistableBundle extras = new android.os.PersistableBundle();
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true); // ClipDescription.EXTRA_IS_SENSITIVE
        clip.getDescription().setExtras(extras);
        cm.setPrimaryClip(clip);
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
    }

    private void openMemo() {
        String id = idBox.getText().toString().trim();
        String key = keyBox.getText().toString().trim();
        if (id.isEmpty() || key.isEmpty()) {
            Toast.makeText(this, R.string.need_id_key, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!Crypto.validId(id)) {
            Toast.makeText(this, R.string.invalid_id, Toast.LENGTH_SHORT).show();
            return;
        }
        Prefs.setCurrent(this, id, key);
        startActivity(new Intent(this, EditorActivity.class));
        finish();
    }
}
