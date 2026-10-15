package com.neonmaze;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

final class AvatarScreen extends Screen {
    private final RectF[] shapeR = new RectF[Avatar.SHAPES.length];
    private final RectF[] emojiR = new RectF[Avatar.EMOJI.length];
    private final RectF[] hueR = new RectF[Avatar.HUES.length];
    private final RectF nameR = new RectF(), tmp = new RectF();
    private final Ui.Btn custom, done, nameB;
    private float downX, downY;

    AvatarScreen(GameView v) {
        super(v);
        for (int i = 0; i < shapeR.length; i++) shapeR[i] = new RectF();
        for (int i = 0; i < emojiR.length; i++) emojiR[i] = new RectF();
        for (int i = 0; i < hueR.length; i++) hueR[i] = new RectF();
        custom = add(new Ui.Btn("✎  CUSTOM", 0.15f));
        done = add(new Ui.Btn("DONE", 0.35f));
        nameB = add(new Ui.Btn("", 0.9f));
    }

    @Override
    boolean back() {
        v.setScreen(new TitleScreen(v));
        return true;
    }

    @Override
    void click(Ui.Btn b) {
        if (b == nameB) {
            v.askName();
        } else if (b == done) {
            back();
        } else if (b == custom) {
            String cur = v.prefs.avatar.startsWith("#") ? "" : v.prefs.avatar;
            v.act.askText("Custom avatar", "any emoji or 1–2 letters", cur, 16, false, s -> {
                String a = Avatar.clip(s);
                if (!a.isEmpty()) {
                    v.prefs.avatar = a;
                    v.prefs.save();
                }
            });
        }
    }

    @Override
    void raw(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            downX = e.getX();
            downY = e.getY();
            return;
        }
        if (a != MotionEvent.ACTION_UP) return;
        float x = e.getX(), y = e.getY();
        if (Math.hypot(x - downX, y - downY) > 20 * dp) return;
        Prefs p = v.prefs;
        for (int i = 0; i < shapeR.length; i++) {
            if (shapeR[i].contains(x, y)) select(Avatar.SHAPES[i]);
        }
        for (int i = 0; i < emojiR.length; i++) {
            if (emojiR[i].contains(x, y)) select(Avatar.EMOJI[i]);
        }
        for (int i = 0; i < hueR.length; i++) {
            if (hueR[i].contains(x, y)) {
                p.hue = Avatar.HUES[i];
                p.save();
                v.tick();
            }
        }
    }

    private void select(String av) {
        v.prefs.avatar = av;
        v.prefs.save();
        v.tick();
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
        Bg.warp(c, 0.008f, 0.3f, dp, 0.2f);
        Prefs p = v.prefs;

        float top = v.safeTop + 8 * dp;
        float avail = h - top - v.safeBottom;
        float k = Math.min(1f, avail / (800 * dp));
        float pad = 16 * dp, gw = w - 2 * pad;

        ui.rainbow(c, "CHOOSE YOUR AVATAR", w / 2f, top + 26 * dp * k, 20, t, 2);

        // preview
        float pcy = top + 100 * dp * k;
        float ring = 62 * dp * k;
        ui.stroke.setStrokeWidth(3 * dp);
        for (int i = 0; i < 2; i++) {
            ui.stroke.setColor(Neon.alpha(Neon.avatarColor(p.hue, t + i), 0.6f));
            tmp.set(w / 2f - ring - i * 7 * dp, pcy - ring - i * 7 * dp, w / 2f + ring + i * 7 * dp, pcy + ring + i * 7 * dp);
            c.drawArc(tmp, t * (i == 0 ? 90 : -140), 260, false, ui.stroke);
        }
        int dir = ((int) (t / 1.2f)) % 4;
        Avatar.draw(c, ui.misc, p.avatar, p.hue, w / 2f, pcy, 100 * dp * k, t, dir, 1f);
        float ny = pcy + ring + 34 * dp * k;
        nameB.label = "✎  USERNAME:  " + p.name;
        nameB.hue = p.hue < 0 ? t * 0.3f : p.hue / 360f;
        nameB.at(w * 0.1f, ny - 26 * dp, w * 0.9f, ny + 12 * dp);
        ui.button(c, nameB, t);

        // shapes
        float y = ny + 30 * dp * k;
        ui.label(c, "SHAPES", pad, y, 12, 0xFF9999AA, Paint.Align.LEFT, 0);
        y += 8 * dp;
        float cw = gw / 4, chh = 64 * dp * k;
        for (int i = 0; i < shapeR.length; i++) {
            float x = pad + (i % 4) * cw, yy = y + (i / 4) * chh;
            shapeR[i].set(x + 3 * dp, yy + 3 * dp, x + cw - 3 * dp, yy + chh - 3 * dp);
            cell(c, shapeR[i], p.avatar.equals(Avatar.SHAPES[i]), t);
            Avatar.draw(c, ui.misc, Avatar.SHAPES[i], p.hue, shapeR[i].centerX(), shapeR[i].centerY() - 7 * dp * k,
                    34 * dp * k, t, 0, 1f);
            ui.label(c, Avatar.SHAPE_NAMES[i], shapeR[i].centerX(), shapeR[i].bottom - 6 * dp, 9, 0xFFBBBBCC,
                    Paint.Align.CENTER, 0);
        }
        y += 2 * chh + 22 * dp * k;

        // emoji
        ui.label(c, "EMOJI", pad, y, 12, 0xFF9999AA, Paint.Align.LEFT, 0);
        y += 8 * dp;
        float ew = gw / 6, eh = 54 * dp * k;
        for (int i = 0; i < emojiR.length; i++) {
            float x = pad + (i % 6) * ew, yy = y + (i / 6) * eh;
            emojiR[i].set(x + 3 * dp, yy + 3 * dp, x + ew - 3 * dp, yy + eh - 3 * dp);
            cell(c, emojiR[i], p.avatar.equals(Avatar.EMOJI[i]), t);
            Avatar.draw(c, ui.misc, Avatar.EMOJI[i], p.hue, emojiR[i].centerX(), emojiR[i].centerY(), 40 * dp * k, t, 0, 1f);
        }
        y += 4 * eh + 14 * dp * k;

        boolean isCustom = !p.avatar.startsWith("#") && indexOf(Avatar.EMOJI, p.avatar) < 0;
        custom.label = isCustom ? "✎  CUSTOM:  " + p.avatar : "✎  CUSTOM  (type any emoji / letters)";
        custom.at(pad, y, w - pad, y + 48 * dp * k);
        ui.button(c, custom, t);
        y += 48 * dp * k + 26 * dp * k;

        // colors
        ui.label(c, "GLOW COLOR", pad, y, 12, 0xFF9999AA, Paint.Align.LEFT, 0);
        y += 8 * dp;
        float sw = gw / hueR.length;
        float rr = Math.min(sw * 0.38f, 15 * dp);
        for (int i = 0; i < hueR.length; i++) {
            float cx = pad + sw * i + sw / 2, cy = y + sw / 2 + 4 * dp;
            hueR[i].set(cx - sw / 2, cy - sw / 2 - 6 * dp, cx + sw / 2, cy + sw / 2 + 6 * dp);
            int hue = Avatar.HUES[i];
            if (hue < 0) {
                for (int s = 0; s < 6; s++) {
                    ui.fill.setColor(Neon.hue(s / 6f + t * 0.3f, 1f));
                    tmp.set(cx - rr, cy - rr, cx + rr, cy + rr);
                    c.drawArc(tmp, s * 60, 60, true, ui.fill);
                }
            } else {
                int col = Neon.hue(hue / 360f, 1f);
                ui.fill.setColor(col);
                ui.fill.setShadowLayer(8 * dp, 0, 0, col);
                c.drawCircle(cx, cy, rr, ui.fill);
                ui.fill.clearShadowLayer();
            }
            if (p.hue == hue) {
                ui.stroke.setStrokeWidth(2.5f * dp);
                ui.stroke.setColor(0xFFFFFFFF);
                c.drawCircle(cx, cy, rr + 5 * dp, ui.stroke);
            }
        }
        y += sw + 24 * dp * k;

        float bw = w * 0.6f;
        float by = Math.max(y, h - v.safeBottom - 64 * dp);
        done.at((w - bw) / 2, by, (w + bw) / 2, by + 54 * dp);
        ui.button(c, done, t);
    }

    private void cell(Canvas c, RectF r, boolean sel, float t) {
        ui.fill.setShader(null);
        ui.fill.setColor(sel ? 0x55FFFFFF : 0x22FFFFFF);
        c.drawRoundRect(r, 10 * dp, 10 * dp, ui.fill);
        if (sel) {
            int col = Neon.hue(t * 0.5f, 1f);
            ui.stroke.setStrokeWidth(2.5f * dp);
            ui.stroke.setColor(col);
            ui.stroke.setShadowLayer(10 * dp, 0, 0, col);
            c.drawRoundRect(r, 10 * dp, 10 * dp, ui.stroke);
            ui.stroke.clearShadowLayer();
        }
    }

    private static int indexOf(String[] arr, String s) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(s)) return i;
        return -1;
    }
}
