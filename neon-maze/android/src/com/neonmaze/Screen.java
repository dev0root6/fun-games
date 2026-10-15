package com.neonmaze;

import android.graphics.Canvas;
import android.view.MotionEvent;

import java.util.ArrayList;

abstract class Screen {
    final GameView v;
    final Ui ui;
    final float dp;
    final ArrayList<Ui.Btn> btns = new ArrayList<>();

    Screen(GameView v) {
        this.v = v;
        this.ui = v.ui;
        this.dp = v.ui.dp;
    }

    Ui.Btn add(Ui.Btn b) {
        btns.add(b);
        return b;
    }

    void enter() {}

    void leave() {}

    void update(float dt) {}

    abstract void draw(Canvas c);

    /** Return false to let the app close. */
    boolean back() {
        return false;
    }

    void click(Ui.Btn b) {}

    void raw(MotionEvent e) {}

    final void touch(MotionEvent e) {
        int a = e.getActionMasked();
        int idx = e.getActionIndex();
        float x = e.getX(idx), y = e.getY(idx);
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
            for (Ui.Btn b : btns) {
                if (b.hit(x, y)) {
                    b.pressed = true;
                    v.tick();
                }
            }
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_POINTER_UP) {
            Ui.Btn hit = null;
            for (Ui.Btn b : btns) {
                if (b.pressed && b.hit(x, y)) hit = b;
                b.pressed = false;
            }
            if (hit != null) {
                click(hit);
                return;
            }
        } else if (a == MotionEvent.ACTION_CANCEL) {
            for (Ui.Btn b : btns) b.pressed = false;
        }
        raw(e);
    }

    boolean anyPressed() {
        for (Ui.Btn b : btns) if (b.pressed) return true;
        return false;
    }
}
