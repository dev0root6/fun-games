package com.neonimposter;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.Typeface;

/** Shared paints and neon widgets. */
final class Ui {
    final float dp;
    final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint misc = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Typeface mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);

    private final SweepGradient sweep;
    private final Matrix matrix = new Matrix();
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);

    static final class Btn {
        final RectF r = new RectF();
        String label;
        float hue;
        boolean enabled = true, visible = true, pressed;

        Btn(String label, float hue) {
            this.label = label;
            this.hue = hue;
        }

        Btn at(float l, float t, float rr, float b) {
            r.set(l, t, rr, b);
            return this;
        }

        boolean hit(float x, float y) {
            return visible && enabled && r.contains(x, y);
        }
    }

    Ui(float dp) {
        this.dp = dp;
        stroke.setStyle(Paint.Style.STROKE);
        text.setTypeface(mono);
        text.setTextAlign(Paint.Align.CENTER);
        int[] cols = new int[13];
        for (int i = 0; i < cols.length; i++) cols[i] = Neon.hue(i / 12f, 1f);
        sweep = new SweepGradient(0, 0, cols, null);
        border.setStyle(Paint.Style.STROKE);
        border.setShader(sweep);
    }

    void centered(Canvas c, String s, float cx, float cy, Paint p) {
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2f, p);
    }

    /** Plain label. sizeSp is in dp units; glow is a blur radius in dp (0 for none). */
    void label(Canvas c, String s, float x, float y, float size, int color, Paint.Align align, float glow) {
        text.setTextAlign(align);
        text.setTextSize(size * dp);
        text.setColor(color);
        if (glow > 0) text.setShadowLayer(glow * dp, 0, 0, color);
        c.drawText(s, x, y, text);
        text.clearShadowLayer();
        text.setTextAlign(Paint.Align.CENTER);
    }

    /** Each character gets its own hue; optional sine wave bounce. */
    void rainbow(Canvas c, String s, float cx, float y, float size, float t, float wave) {
        text.setTextSize(size * dp);
        text.setTextAlign(Paint.Align.LEFT);
        float w = text.measureText(s);
        float x = cx - w / 2f;
        for (int i = 0; i < s.length(); i++) {
            String ch = s.substring(i, i + 1);
            int col = Neon.hue(i * 0.06f + t * 0.35f, 1f);
            text.setColor(col);
            text.setShadowLayer(9 * dp, 0, 0, col);
            float dy = wave > 0 ? (float) Math.sin(t * 4 + i * 0.6f) * wave * dp : 0;
            c.drawText(ch, x, y + dy, text);
            x += text.measureText(ch);
        }
        text.clearShadowLayer();
        text.setTextAlign(Paint.Align.CENTER);
    }

    /** Dark rounded panel with a rainbow border that rotates over time. */
    void panel(Canvas c, RectF r, float t, float alpha) {
        float rad = 16 * dp;
        fill.setShader(null);
        fill.setColor(Neon.alpha(0xFF0A0614, alpha));
        c.drawRoundRect(r, rad, rad, fill);
        matrix.setRotate(t * 90f);
        matrix.postTranslate(r.centerX(), r.centerY());
        sweep.setLocalMatrix(matrix);
        border.setStrokeWidth(2.5f * dp);
        border.setAlpha(255);
        c.drawRoundRect(r, rad, rad, border);
        border.setStrokeWidth(8 * dp);
        border.setAlpha(40);
        c.drawRoundRect(r, rad, rad, border);
    }

    void button(Canvas c, Btn b, float t) {
        if (!b.visible) return;
        float rad = Math.min(16 * dp, b.r.height() / 2f);
        int col = b.enabled ? Neon.hue(b.hue + t * 0.12f, 1f) : Neon.gray(0.35f);
        fill.setShader(null);
        fill.setColor(b.pressed ? Neon.alpha(col, 0.4f) : 0xD90A0614);
        c.drawRoundRect(b.r, rad, rad, fill);
        stroke.setStrokeWidth(2.5f * dp);
        stroke.setColor(col);
        if (b.enabled) stroke.setShadowLayer(10 * dp, 0, 0, col);
        c.drawRoundRect(b.r, rad, rad, stroke);
        stroke.clearShadowLayer();
        text.setTextSize(Math.min(b.r.height() * 0.4f, 19 * dp));
        text.setColor(b.enabled ? Color.WHITE : Neon.gray(0.5f));
        if (b.enabled) text.setShadowLayer(8 * dp, 0, 0, col);
        centered(c, b.label, b.r.centerX(), b.r.centerY(), text);
        text.clearShadowLayer();
    }

    /** Horizontal bar: rainbow fill on the left, dim remainder. */
    void bar(Canvas c, float x, float y, float w, float h, float frac, float t) {
        frac = Neon.clamp(frac, 0f, 1f);
        fill.setShader(null);
        fill.setColor(0x33FFFFFF);
        c.drawRoundRect(x, y, x + w, y + h, h / 2, h / 2, fill);
        int segs = 24;
        float sw = w / segs;
        for (int i = 0; i < segs; i++) {
            if ((i + 0.5f) / segs > frac) break;
            int col = Neon.hue(i * 0.03f + t * 0.4f, 1f);
            fill.setColor(col);
            c.drawRect(x + i * sw + 1, y + 1, x + (i + 1) * sw - 1, y + h - 1, fill);
        }
    }
}
