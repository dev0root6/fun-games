package com.neonimposter;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

final class HowToScreen extends Screen {
    private static final String[][] STEPS = {
            {"1  SECRET", "Everyone gets the same secret word — except the imposter, who only sees the category (or nothing)."},
            {"2  CLUES", "Take turns giving ONE short clue about the word. Too obvious helps the imposter. Too vague looks sus."},
            {"3  DISCUSS", "Argue. Accuse. Defend. Who sounded like they had no idea?"},
            {"4  VOTE", "Everyone votes. Most votes gets ejected. A tie or skip means another clue round."},
            {"5  LAST CHANCE", "Caught imposter can still win by guessing the secret word."},
            {"WIN", "Crew wins by ejecting every imposter. Imposters win by surviving, guessing the word, or getting an innocent ejected."},
            {"SCORE", "Crew win: +1 (+1 more if you voted right). Imposter win: +3."},
    };
    private final Ui.Btn back;
    private final RectF tmp = new RectF();

    HowToScreen(GameView v) {
        super(v);
        back = add(new Ui.Btn("GOT IT", 0.35f));
    }

    @Override
    void enter() {
        v.audio.song(Audio.MENU);
    }

    @Override
    boolean back() {
        v.setScreen(new TitleScreen(v));
        return true;
    }

    @Override
    void click(Ui.Btn b) {
        back();
    }

    @Override
    void draw(Canvas c) {
        float t = v.t;
        int w = c.getWidth(), h = c.getHeight();
        c.drawColor(0xFF07040D);
        Bg.warp(c, 0.01f, 0.2f, dp, 0.3f);
        float top = v.safeTop + 10 * dp;
        ui.rainbow(c, "HOW TO PLAY", w / 2f, top + 40 * dp, 30, t, 3);
        float pad = 18 * dp;
        tmp.set(pad, top + 64 * dp, w - pad, h - v.safeBottom - 90 * dp);
        ui.panel(c, tmp, t, 0.92f);
        float y = tmp.top + 34 * dp;
        float maxW = tmp.width() - 36 * dp;
        Paint p = ui.text;
        for (String[] s : STEPS) {
            ui.label(c, s[0], tmp.left + 18 * dp, y, 15, s[0].equals("WIN") ? 0xFFFFE14D : Neon.hue(0.5f + y / h, 1f),
                    Paint.Align.LEFT, 6);
            y += 22 * dp;
            p.setTextSize(13 * dp);
            for (String line : wrap(s[1], p, maxW)) {
                ui.label(c, line, tmp.left + 18 * dp, y, 13, 0xFFDDDDEE, Paint.Align.LEFT, 0);
                y += 18 * dp;
            }
            y += 12 * dp;
        }
        float bw = w * 0.5f;
        back.at((w - bw) / 2, h - v.safeBottom - 74 * dp, (w + bw) / 2, h - v.safeBottom - 20 * dp);
        ui.button(c, back, t);
    }

    static java.util.List<String> wrap(String text, Paint p, float maxW) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String test = line.length() == 0 ? word : line + " " + word;
            if (p.measureText(test) > maxW && line.length() > 0) {
                out.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(test);
            }
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }
}
