package com.neonmaze;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.EditText;
import android.window.OnBackInvokedDispatcher;

public class MainActivity extends Activity {
    GameView view;

    interface TextCallback {
        void done(String s);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        view = new GameView(this);
        setContentView(view);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> view.onBack());
        }
        hideBars();
    }

    @SuppressWarnings("deprecation")
    private void hideBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        view.onBack();
    }

    @Override
    protected void onResume() {
        super.onResume();
        view.resume();
    }

    @Override
    protected void onPause() {
        view.pause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        view.shutdown();
        super.onDestroy();
    }

    /** Native text prompt; the result is delivered on the game thread. */
    void askText(final String title, final String hint, final String initial, final int maxLen,
                 final boolean numeric, final TextCallback cb) {
        runOnUiThread(() -> {
            final EditText et = new EditText(this);
            et.setSingleLine();
            et.setHint(hint);
            et.setText(initial);
            et.setSelection(et.length());
            if (numeric) et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            if (maxLen > 0) et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLen)});
            int pad = (int) (20 * getResources().getDisplayMetrics().density);
            et.setPadding(pad, pad, pad, pad);
            AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(title)
                    .setView(et)
                    .setPositiveButton("OK", (d, w) -> {
                        final String s = et.getText().toString();
                        view.later(() -> cb.done(s));
                    })
                    .setNegativeButton("Cancel", null)
                    .setOnDismissListener(d -> hideBars())
                    .create();
            if (dlg.getWindow() != null) {
                dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            }
            dlg.show();
            et.requestFocus();
        });
    }
}
