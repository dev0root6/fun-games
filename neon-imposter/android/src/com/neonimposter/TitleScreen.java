package com.neonimposter;

import android.graphics.Canvas;
import android.graphics.Paint;

import java.util.Random;

final class TitleScreen extends Screen {
    private final Ui.Btn host, join, avatar, howto, nameB, musicB, sfxB;
    private final Random rnd = new Random();
    private float dt, glitchUntil, nextGlitch = 2f;
    private boolean asked;

    TitleScreen(GameView v) {
        super(v);
        host = add(new Ui.Btn("★  HOST GAME", 0.95f));
        join = add(new Ui.Btn("⌁  JOIN GAME", 0.5f));
        avatar = add(new Ui.Btn("☺  AVATAR & NAME", 0.75f));
        howto = add(new Ui.Btn("?  HOW TO PLAY", 0.15f));
        nameB = add(new Ui.Btn("", 0.9f));
        musicB = add(new Ui.Btn("", 0.75f));
        sfxB = add(new Ui.Btn("", 0.6f));
    }

    @Override
    void enter() {
        v.audio.song(Audio.MENU);
    }

    @Override
    void update(float dt) {
        this.dt = dt;
        if (v.t > nextGlitch) {
            glitchUntil = v.t + 0.18f;
            nextGlitch = v.t + 1.5f + rnd.nextFloat() * 3f;
        }
        if (!asked && !v.prefs.named && v.t > 1.2f) {
            asked = true;
            v.askName();
        }
    }

    @Override
    void click(Ui.Btn b) {
        Prefs p = v.prefs;
        if (b == host) v.setScreen(new RoomScreen(v, null, -1));
        else if (b == join) v.setScreen(new LanScreen(v));
        else if (b == avatar) v.setScreen(new AvatarScreen(v));
        else if (b == howto) v.setScreen(new HowToScreen(v));
        else if (b == nameB) v.askName();
        else if (b == musicB) v.setMusic(!p.music);
        else if (b == sfxB) v.setSfx(!p.sfx);
    }

    @Override
    void draw(Canvas c) {
        float t = v.t;
        int w = c.getWidth(), h = c.getHeight();
        c.drawColor(0xFF07040D);
        Bg.warp(c, dt, 0.25f, dp, 0.35f);
        Bg.synth(c, t, h * 0.82f, dp);

        boolean glitch = t < glitchUntil;
        Paint p = ui.text;
        p.setTextAlign(Paint.Align.LEFT);
        String[] words = {"NEON", "IMPOSTER"};
        float y = v.safeTop + 60 * dp;
        for (int line = 0; line < 2; line++) {
            String wd = words[line];
            p.setTextSize(100);
            float size = Math.min(w * 0.24f, w * 0.88f / (p.measureText("M") / 100f * wd.length()));
            p.setTextSize(size);
            float cw = p.measureText("M");
            float x = w / 2f - cw * wd.length() / 2f;
            y += size * 0.85f;
            for (int i = 0; i < wd.length(); i++) {
                String ch = wd.substring(i, i + 1);
                float yy = y + (float) Math.sin(t * 3 + i * 0.7f + line) * 5 * dp;
                float ab = glitch ? (rnd.nextFloat() * 12 - 6) * dp : 2.5f * dp;
                float gx = glitch ? (rnd.nextFloat() * 10 - 5) * dp : 0;
                p.clearShadowLayer();
                p.setColor(0x9900F0FF);
                c.drawText(ch, x - ab + gx, yy, p);
                p.setColor(0x99FF0040);
                c.drawText(ch, x + ab + gx, yy, p);
                // the odd letter out glows red — one of them is the imposter
                boolean sus = line == 1 && i == (int) (t / 2.5f) % wd.length();
                int col = sus ? Neon.hue(0f, 1f) : Neon.hue(0.5f + i * 0.05f + line * 0.15f + t * 0.15f, 1f);
                p.setColor(col);
                p.setShadowLayer(18 * dp, 0, 0, col);
                c.drawText(ch, x + gx, yy, p);
                x += cw;
            }
            y += 6 * dp;
        }
        p.clearShadowLayer();
        p.setTextAlign(Paint.Align.CENTER);
        ui.label(c, "a LAN word game of lies", w / 2f, y + 22 * dp, 13, 0xFFBBBBCC, Paint.Align.CENTER, 0);

        float tb = 104 * dp, th = 38 * dp, ty = v.safeTop + 4 * dp;
        musicB.label = v.prefs.music ? "♫ MUSIC" : "♫ OFF";
        sfxB.label = v.prefs.sfx ? "♪ SFX" : "♪ OFF";
        musicB.at(12 * dp, ty, 12 * dp + tb, ty + th);
        sfxB.at(w - 12 * dp - tb, ty, w - 12 * dp, ty + th);
        ui.button(c, musicB, t);
        ui.button(c, sfxB, t);

        float ay = y + 78 * dp;
        Avatar.draw(c, ui.misc, v.prefs.avatar, v.prefs.hue, w / 2f, ay, 56 * dp, t, 0, 1f);
        ui.text.setTextSize(15 * dp);
        float nw = Math.max(150 * dp, ui.text.measureText("✎  " + v.prefs.name) + 36 * dp);
        nameB.label = "✎  " + v.prefs.name;
        nameB.hue = v.prefs.hue < 0 ? t * 0.3f : v.prefs.hue / 360f;
        nameB.at((w - nw) / 2, ay + 34 * dp, (w + nw) / 2, ay + 72 * dp);
        ui.button(c, nameB, t);

        float bw = w * 0.74f, bh = 56 * dp, gap = 12 * dp;
        float by = ay + 90 * dp;
        for (Ui.Btn b : new Ui.Btn[]{host, join, avatar, howto}) {
            b.at((w - bw) / 2, by, (w + bw) / 2, by + bh);
            ui.button(c, b, t);
            by += bh + gap;
        }
        ui.label(c, "3–10 players · same Wi-Fi or hotspot", w / 2f, h - v.safeBottom - 14 * dp, 12, 0xFFFFE14D,
                Paint.Align.CENTER, 6);
    }
}
