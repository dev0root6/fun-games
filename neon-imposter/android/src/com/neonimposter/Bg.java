package com.neonimposter;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;

import java.util.Random;

/** Warp starfield and synthwave sun/grid shared by the menu screens. */
final class Bg {
    private static final int N = 170;
    private static final float[] sx = new float[N], sy = new float[N], sz = new float[N];
    private static final Random rnd = new Random();
    private static final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static LinearGradient sunShader;
    private static float sunKey = -1;

    static {
        for (int i = 0; i < N; i++) reset(i, 0.05f + rnd.nextFloat() * 0.95f);
    }

    private Bg() {}

    private static void reset(int i, float z) {
        sx[i] = rnd.nextFloat() * 2 - 1;
        sy[i] = rnd.nextFloat() * 2 - 1;
        sz[i] = z;
    }

    static void warp(Canvas c, float dt, float speed, float dp, float cyFrac) {
        int w = c.getWidth(), h = c.getHeight();
        float cx = w / 2f, cy = h * cyFrac, scale = Math.max(w, h) * 0.45f;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < N; i++) {
            float z0 = sz[i];
            sz[i] -= dt * speed;
            if (sz[i] <= 0.02f) {
                reset(i, 1f);
                continue;
            }
            float z = sz[i];
            float x = cx + sx[i] / z * scale, y = cy + sy[i] / z * scale;
            float px = cx + sx[i] / Math.min(1f, z0 + 0.03f) * scale, py = cy + sy[i] / Math.min(1f, z0 + 0.03f) * scale;
            if (x < -20 || y < -20 || x > w + 20 || y > h + 20) continue;
            float b = 1f - z;
            p.setStrokeWidth((0.6f + b * 2.6f) * dp);
            p.setColor(Neon.alpha(i % 9 == 0 ? Neon.hue(i * 0.13f, 1f) : 0xFFFFFFFF, b * 0.95f));
            c.drawLine(px, py, x, y, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    static void synth(Canvas c, float t, float horizon, float dp) {
        int w = c.getWidth(), h = c.getHeight();
        float r = w * 0.32f, cx = w / 2f, cy = horizon;
        if (sunShader == null || sunKey != horizon) {
            sunShader = new LinearGradient(0, cy - r, 0, cy, 0xFFFFE14D, 0xFFFF2BD9, Shader.TileMode.CLAMP);
            sunKey = horizon;
        }
        p.setShader(sunShader);
        p.setShadowLayer(30 * dp, 0, 0, 0xFFFF2BD9);
        c.save();
        c.clipRect(0, 0, w, cy);
        c.drawCircle(cx, cy, r, p);
        c.restore();
        p.clearShadowLayer();
        p.setShader(null);
        p.setColor(0xFF07040D);
        for (int i = 0; i < 7; i++) {
            float yy = cy - r * 0.08f - i * r * 0.11f;
            float th = r * 0.045f * (1f - i / 8f);
            c.drawRect(cx - r, yy - th, cx + r, yy + th, p);
        }
        // floor
        p.setColor(0xFF07040D);
        c.drawRect(0, cy, w, h, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f * dp);
        int lines = 14;
        float scroll = (t * 0.6f) % 1f;
        for (int i = 0; i < lines; i++) {
            float k = (i + scroll) / lines;
            float y = cy + (h - cy) * k * k;
            p.setColor(Neon.alpha(0xFFFF2BD9, 0.15f + 0.7f * k));
            c.drawLine(0, y, w, y, p);
        }
        for (int i = -12; i <= 12; i++) {
            float xb = cx + i * w * 0.14f;
            p.setColor(Neon.alpha(0xFF2BF5FF, 0.45f));
            c.drawLine(cx + i * w * 0.012f, cy, xb, h, p);
        }
        p.setStyle(Paint.Style.FILL);
    }
}
