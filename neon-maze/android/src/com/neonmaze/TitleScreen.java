package com.neonmaze;

import android.graphics.Canvas;
import android.graphics.Paint;

import java.util.Locale;
import java.util.Random;

final class TitleScreen extends Screen {
    private final Ui.Btn solo, lan, avatar, dl, dr, nameB, musicB, sfxB;
    private boolean asked;
    private final Random rnd = new Random();
    private float dt, glitchUntil, nextGlitch = 2f;

    TitleScreen(GameView v) {
        super(v);
        solo = add(new Ui.Btn("▶  SOLO RUN", 0.0f));
        lan = add(new Ui.Btn("⚡  LAN RACE", 0.33f));
        avatar = add(new Ui.Btn("☺  AVATAR & NAME", 0.66f));
        dl = add(new Ui.Btn("◀", 0.5f));
        dr = add(new Ui.Btn("▶", 0.5f));
        nameB = add(new Ui.Btn("", 0.9f));
        musicB = add(new Ui.Btn("", 0.75f));
        sfxB = add(new Ui.Btn("", 0.6f));
    }

    @Override
    void update(float dt) {
        this.dt = dt;
        if (!asked && !v.prefs.named && v.t > 1.2f) {
            asked = true;
            v.askName();
        }
        if (v.t > nextGlitch) {
            glitchUntil = v.t + 0.16f;
            nextGlitch = v.t + 2f + rnd.nextFloat() * 3f;
        }
    }

    @Override
    void click(Ui.Btn b) {
        Prefs p = v.prefs;
        if (b == solo) v.setScreen(new PlayScreen(v, p.diff));
        else if (b == lan) v.setScreen(new LobbyScreen(v));
        else if (b == avatar) v.setScreen(new AvatarScreen(v));
        else if (b == nameB) v.askName();
        else if (b == musicB) v.setMusic(!p.music);
        else if (b == sfxB) v.setSfx(!p.sfx);
        else if (b == dl || b == dr) {
            p.diff = (p.diff + (b == dl ? 3 : 1)) % 4;
            p.save();
        }
    }

    @Override
    void enter() {
        v.audio.song(Audio.MENU);
    }

    @Override
    void draw(Canvas c) {
        float t = v.t;
        int w = c.getWidth(), h = c.getHeight();
        c.drawColor(0xFF07040D);
        Bg.warp(c, dt, 0.32f, dp, 0.35f);
        Bg.synth(c, t, h * 0.8f, dp);

        // glitchy chromatic title
        boolean glitch = t < glitchUntil;
        Paint p = ui.text;
        float size = w * 0.27f;
        p.setTextSize(size);
        p.setTextAlign(Paint.Align.LEFT);
        float cw = p.measureText("M");
        String[] words = {"NEON", "MAZE"};
        float baseY = v.safeTop + 40 * dp + h * 0.04f + size * 0.8f;
        for (int line = 0; line < 2; line++) {
            float x = w / 2f - cw * 2;
            for (int i = 0; i < 4; i++) {
                String ch = words[line].substring(i, i + 1);
                float y = baseY + line * size * 0.95f + (float) Math.sin(t * 3 + i * 0.7f + line) * 6 * dp;
                float ab = glitch ? (rnd.nextFloat() * 10 - 5) * dp : 2.5f * dp;
                float gx = glitch ? (rnd.nextFloat() * 8 - 4) * dp : 0;
                p.clearShadowLayer();
                p.setColor(0x9900F0FF);
                c.drawText(ch, x - ab + gx, y, p);
                p.setColor(0x99FF00C8);
                c.drawText(ch, x + ab + gx, y, p);
                int col = Neon.hue(i * 0.08f + line * 0.2f + t * 0.3f, 1f);
                p.setColor(col);
                p.setShadowLayer(18 * dp, 0, 0, col);
                c.drawText(ch, x + gx, y, p);
                x += cw;
            }
        }
        p.clearShadowLayer();
        p.setTextAlign(Paint.Align.CENTER);

        float ay = baseY + size * 1.55f;
        Avatar.draw(c, ui.misc, v.prefs.avatar, v.prefs.hue, w / 2f, ay, 56 * dp, t, 0, 1f);
        ui.text.setTextSize(15 * dp);
        float nw = Math.max(150 * dp, ui.text.measureText("✎  " + v.prefs.name) + 36 * dp);
        nameB.label = "✎  " + v.prefs.name;
        nameB.hue = v.prefs.hue < 0 ? t * 0.3f : v.prefs.hue / 360f;
        nameB.at((w - nw) / 2, ay + 34 * dp, (w + nw) / 2, ay + 72 * dp);
        ui.button(c, nameB, t);

        float tb = 104 * dp, th = 38 * dp, ty = v.safeTop + 4 * dp;
        musicB.label = v.prefs.music ? "♫ MUSIC" : "♫ OFF";
        sfxB.label = v.prefs.sfx ? "♪ SFX" : "♪ OFF";
        musicB.at(12 * dp, ty, 12 * dp + tb, ty + th);
        sfxB.at(w - 12 * dp - tb, ty, w - 12 * dp, ty + th);
        ui.button(c, musicB, t);
        ui.button(c, sfxB, t);

        float bw = w * 0.74f, bh = 58 * dp, gap = 14 * dp;
        float y = ay + 86 * dp;
        for (Ui.Btn b : new Ui.Btn[]{solo, lan, avatar}) {
            b.at((w - bw) / 2, y, (w + bw) / 2, y + bh);
            ui.button(c, b, t);
            y += bh + gap;
        }
        float sb = 48 * dp;
        dl.at((w - bw) / 2, y, (w - bw) / 2 + sb, y + sb);
        dr.at((w + bw) / 2 - sb, y, (w + bw) / 2, y + sb);
        ui.button(c, dl, t);
        ui.button(c, dr, t);
        int d = v.prefs.diff;
        ui.label(c, PlayScreen.DIFF_NAMES[d], w / 2f, y + sb / 2 + 2 * dp, 20, Neon.hue(t * 0.4f + d * 0.25f, 1f),
                Paint.Align.CENTER, 10);
        int[] cfg = PlayScreen.DIFF_CFG[d];
        ui.label(c, cfg[1] + "×" + cfg[0] + " maze · torch " + cfg[2], w / 2f, y + sb / 2 + 20 * dp, 11, 0xFFAAAAAA,
                Paint.Align.CENTER, 0);

        ui.label(c, String.format(Locale.US, "BEST %07d", v.prefs.best), w / 2f, h - v.safeBottom - 14 * dp, 14,
                0xFFFFE14D, Paint.Align.CENTER, 8);
    }
}
