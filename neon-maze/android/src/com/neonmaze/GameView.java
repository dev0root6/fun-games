package com.neonmaze;

import android.graphics.Canvas;
import android.os.Build;
import android.view.DisplayCutout;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowInsets;

import java.util.concurrent.ConcurrentLinkedQueue;

/** Owns the render/game thread. Touches and UI-thread callbacks are queued onto it. */
public class GameView extends SurfaceView implements SurfaceHolder.Callback, Runnable {
    final MainActivity act;
    final Ui ui;
    final Prefs prefs;
    final Audio audio = new Audio();
    final ConcurrentLinkedQueue<MotionEvent> touches = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    volatile int safeTop, safeBottom;
    float t;

    private Thread thread;
    private volatile boolean running, surfaceOk;
    private Screen screen;

    GameView(MainActivity a) {
        super(a);
        act = a;
        float dp = getResources().getDisplayMetrics().density;
        ui = new Ui(dp);
        prefs = new Prefs(a);
        safeTop = safeBottom = (int) (12 * dp);
        getHolder().addCallback(this);
        setFocusable(true);
        setHapticFeedbackEnabled(true);
        setOnApplyWindowInsetsListener((view, ins) -> {
            int top = 0, bot = 0;
            if (Build.VERSION.SDK_INT >= 28) {
                DisplayCutout dc = ins.getDisplayCutout();
                if (dc != null) {
                    top = dc.getSafeInsetTop();
                    bot = dc.getSafeInsetBottom();
                }
            }
            if (Build.VERSION.SDK_INT >= 30) {
                top = Math.max(top, ins.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top);
                bot = Math.max(bot, ins.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom);
            }
            safeTop = Math.max(top, (int) (12 * dp));
            safeBottom = Math.max(bot, (int) (12 * dp));
            return ins;
        });
        audio.musicOn = prefs.music;
        audio.sfxOn = prefs.sfx;
        screen = new TitleScreen(this);
        screen.enter();
    }

    /** Must be called on the game thread. */
    void setScreen(Screen s) {
        screen.leave();
        screen = s;
        s.enter();
    }

    void later(Runnable r) {
        tasks.add(r);
    }

    void tick() {
        audio.sfx(Audio.CLICK);
        haptic(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    void setMusic(boolean on) {
        prefs.music = audio.musicOn = on;
        prefs.save();
    }

    void setSfx(boolean on) {
        prefs.sfx = audio.sfxOn = on;
        prefs.save();
    }

    /** Opens the username prompt. The name is kept exactly as typed. */
    void askName() {
        act.askText("Your username", "shown to other racers", prefs.named ? prefs.name : "", 16, false, s -> {
            String n = s.trim().replace("|", "").replace("\n", "");
            if (!n.isEmpty()) {
                prefs.name = n;
                prefs.named = true;
                prefs.save();
            }
        });
    }

    void haptic(final int type) {
        act.runOnUiThread(() -> performHapticFeedback(type));
    }

    void onBack() {
        later(() -> {
            if (!screen.back()) act.runOnUiThread(act::finish);
        });
    }

    void resume() {
        audio.start();
        if (surfaceOk) start();
    }

    void pause() {
        audio.stop();
        stop();
    }

    void shutdown() {
        later(() -> screen.leave());
        stop();
        Runnable r;
        while ((r = tasks.poll()) != null) r.run();
    }

    private synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this, "neon-game");
        thread.start();
    }

    private synchronized void stop() {
        running = false;
        Thread th = thread;
        thread = null;
        if (th != null && th != Thread.currentThread()) {
            try {
                th.join(800);
            } catch (InterruptedException ignored) {
            }
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        surfaceOk = true;
        start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        surfaceOk = false;
        stop();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        touches.add(MotionEvent.obtain(e));
        return true;
    }

    @Override
    public void run() {
        long last = System.nanoTime();
        while (running) {
            long now = System.nanoTime();
            float dt = Math.min(0.05f, (now - last) / 1e9f);
            last = now;
            t += dt;
            Runnable r;
            while ((r = tasks.poll()) != null) r.run();
            MotionEvent e;
            while ((e = touches.poll()) != null) {
                screen.touch(e);
                e.recycle();
            }
            screen.update(dt);
            Canvas c = null;
            try {
                c = getHolder().lockHardwareCanvas();
            } catch (Exception ignored) {
            }
            if (c == null) {
                try {
                    Thread.sleep(16);
                } catch (InterruptedException ignored) {
                }
                continue;
            }
            try {
                screen.draw(c);
            } finally {
                try {
                    getHolder().unlockCanvasAndPost(c);
                } catch (Exception ignored) {
                }
            }
        }
    }
}
