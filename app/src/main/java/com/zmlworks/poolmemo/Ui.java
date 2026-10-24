package com.zmlworks.poolmemo;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;

/** Edge-to-edge + dark-gray palette（濃いグレー地・下文字）. */
final class Ui {
    private Ui() {}

    static final int BG = 0xFF2A2A2A, BG2 = 0xFF1E1E1E, TEXT = 0xFFC8C8C8,
            DIM = 0xFF808080, PANEL = 0xFF353535, GRID = 0xFF404040,
            HI = 0xFF8A9AAA, EDIT_BG = 0xFF222222;

    @SuppressWarnings("deprecation")
    static void edgeToEdge(Activity a, View root) {
        Window w = a.getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        } else {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        final int l = root.getPaddingLeft(), t = root.getPaddingTop(),
                r = root.getPaddingRight(), b = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, in) -> {
            int il, it, ir, ib;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = in.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.ime() | WindowInsets.Type.displayCutout());
                il = i.left; it = i.top; ir = i.right; ib = i.bottom;
            } else {
                il = in.getSystemWindowInsetLeft(); it = in.getSystemWindowInsetTop();
                ir = in.getSystemWindowInsetRight(); ib = in.getSystemWindowInsetBottom();
            }
            v.setPadding(l + il, t + it, r + ir, b + ib);
            return in;
        });
        root.requestApplyInsets();
    }

    static int dp(android.content.Context c, float dp) {
        return Math.round(dp * c.getResources().getDisplayMetrics().density);
    }
}
