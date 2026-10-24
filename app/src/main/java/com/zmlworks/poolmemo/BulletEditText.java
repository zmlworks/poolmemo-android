package com.zmlworks.poolmemo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Editable;
import android.text.Layout;
import android.text.Spannable;
import android.text.TextWatcher;
import android.text.style.LeadingMarginSpan;
import android.util.AttributeSet;
import android.widget.EditText;

/**
 * 「・」はテキストに含めず描画のみ。段落先頭行だけ LeadingMarginSpan でインデントし、
 * 折り返し行は余白 0・点なし。左ガター帯（専用 padding）は使わない。
 */
public class BulletEditText extends EditText {
    private final Paint bullet = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int indentPx;
    private boolean applyingSpans;

    public BulletEditText(Context c) {
        super(c);
        init();
    }

    public BulletEditText(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        bullet.setColor(Ui.DIM);
        bullet.setTextAlign(Paint.Align.LEFT);
        int pad = Math.round(getTextSize() * 0.35f);
        if (pad < 8) pad = 8;
        // 通常の小さな padding のみ（ガター専用の大きな左余白は付けない）
        super.setPadding(pad, pad, pad, pad);
        updateIndent();
        addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                applyLeadingMargins(s);
            }
        });
    }

    private void updateIndent() {
        bullet.setTextSize(getTextSize());
        float w = bullet.measureText("・");
        indentPx = Math.round(w + getTextSize() * 0.35f);
        if (indentPx < 12) indentPx = 12;
    }

    @Override
    public void setTextSize(float size) {
        super.setTextSize(size);
        onFontChanged();
    }

    @Override
    public void setTextSize(int unit, float size) {
        super.setTextSize(unit, size);
        onFontChanged();
    }

    private void onFontChanged() {
        updateIndent();
        int pad = Math.round(getTextSize() * 0.35f);
        if (pad < 8) pad = 8;
        super.setPadding(pad, pad, pad, pad);
        Editable e = getText();
        if (e != null) applyLeadingMargins(e);
        invalidate();
    }

    private void applyLeadingMargins(Editable s) {
        if (applyingSpans || s == null) return;
        applyingSpans = true;
        try {
            LeadingMarginSpan[] old = s.getSpans(0, s.length(), LeadingMarginSpan.class);
            for (LeadingMarginSpan sp : old) {
                s.removeSpan(sp);
            }
            int len = s.length();
            int start = 0;
            while (start <= len) {
                int end = start;
                while (end < len && s.charAt(end) != '\n') {
                    end++;
                }
                int spanEnd = end;
                if (spanEnd == start) {
                    // 空段落: 次の \n があればそれを含めて span
                    if (start < len) {
                        spanEnd = start + 1;
                    } else {
                        break; // 末尾の空 — Layout 上は点のみ描画
                    }
                }
                s.setSpan(new LeadingMarginSpan.Standard(indentPx, 0),
                        start, spanEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (end >= len) break;
                start = end + 1;
            }
        } finally {
            applyingSpans = false;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Layout layout = getLayout();
        if (layout == null) return;
        CharSequence text = getText();
        int scrollY = getScrollY();
        int first = layout.getLineForVertical(scrollY);
        int last = layout.getLineForVertical(scrollY + getHeight());
        // 先頭行インデント領域の左端付近に点を描く（ガター帯ではなく同一テキスト領域）
        float x = getPaddingLeft() + getTextSize() * 0.05f;
        for (int i = first; i <= last && i < layout.getLineCount(); i++) {
            int start = layout.getLineStart(i);
            boolean hard = (start == 0) || (start > 0 && text.charAt(start - 1) == '\n');
            if (hard) {
                int baseline = layout.getLineBaseline(i) + getTotalPaddingTop() - scrollY;
                canvas.drawText("・", x + getScrollX(), baseline, bullet);
            }
        }
    }
}
