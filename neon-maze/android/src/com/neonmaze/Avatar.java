package com.neonmaze;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import java.text.BreakIterator;

/**
 * Player avatars. An avatar is a string: "#name" for a drawn neon shape,
 * anything else (emoji, letters) is rendered as text.
 */
final class Avatar {
    static final String[] SHAPES = {"#blob", "#orb", "#ship", "#star", "#gem", "#ghost", "#chomp", "#ring"};
    static final String[] SHAPE_NAMES = {"BLOB", "ORB", "SHIP", "STAR", "GEM", "GHOST", "CHOMP", "RING"};
    static final String[] EMOJI = {
            "🚀", "👻", "🐱", "🦊", "🐸", "🤖", "👽", "🐉",
            "🔥", "💀", "🐼", "🦄", "⚡", "🍕", "🎃", "🐙",
            "🦖", "🐝", "🌈", "💎", "🍄", "🐧", "👾", "🥷",
    };
    /** Hue in degrees; -1 means rainbow. */
    static final int[] HUES = {0, 25, 50, 95, 140, 175, 195, 220, 255, 285, 315, -1};

    private static final Path path = new Path();
    private static final RectF rf = new RectF();

    private Avatar() {}

    /** Keep at most two user-perceived characters (emoji ZWJ sequences count as one). */
    static String clip(String s) {
        s = s.trim().replace("|", "").replace("\n", "");
        if (s.isEmpty()) return "";
        BreakIterator it = BreakIterator.getCharacterInstance();
        it.setText(s);
        int end = it.next(2);
        return end == BreakIterator.DONE ? s : s.substring(0, end);
    }

    /**
     * @param dir 0 right, 1 down, 2 left, 3 up
     */
    static void draw(Canvas c, Paint p, String av, int hueDeg, float cx, float cy, float size,
                     float t, int dir, float alpha) {
        int col = Neon.alpha(Neon.avatarColor(hueDeg, t), alpha);
        float r = size * 0.42f;
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(col);
        p.setShadowLayer(r * 0.7f, 0, 0, col);

        if (av == null || av.isEmpty()) av = "#blob";
        if (!av.startsWith("#")) {
            p.clearShadowLayer();
            p.setColor(Neon.alpha(col, alpha * 0.28f));
            c.drawCircle(cx, cy, r * 1.05f, p);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(size * (av.codePointCount(0, av.length()) > 1 && !isEmojiLike(av) ? 0.55f : 0.78f));
            p.setColor(Neon.alpha(0xFFFFFFFF, alpha));
            p.setShadowLayer(r * 0.6f, 0, 0, col);
            Paint.FontMetrics fm = p.getFontMetrics();
            c.drawText(av, cx, cy - (fm.ascent + fm.descent) / 2f, p);
            p.clearShadowLayer();
            return;
        }

        switch (av) {
            case "#orb": {
                p.clearShadowLayer();
                p.setShader(new RadialGradient(cx - r * 0.3f, cy - r * 0.3f, r * 1.3f,
                        new int[]{Neon.alpha(0xFFFFFFFF, alpha), col, Neon.alpha(col, 0)},
                        new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
                c.drawCircle(cx, cy, r * 1.1f, p);
                p.setShader(null);
                float a = t * 4f;
                p.setColor(Neon.alpha(0xFFFFFFFF, alpha));
                c.drawCircle(cx + (float) Math.cos(a) * r * 1.15f, cy + (float) Math.sin(a) * r * 0.5f, r * 0.14f, p);
                break;
            }
            case "#ship": {
                c.save();
                c.rotate(dir * 90f, cx, cy);
                path.reset();
                path.moveTo(cx + r, cy);
                path.lineTo(cx - r * 0.8f, cy + r * 0.75f);
                path.lineTo(cx - r * 0.35f, cy);
                path.lineTo(cx - r * 0.8f, cy - r * 0.75f);
                path.close();
                c.drawPath(path, p);
                p.clearShadowLayer();
                float fl = 0.5f + 0.5f * (float) Math.abs(Math.sin(t * 25));
                p.setColor(Neon.alpha(0xFFFFA020, alpha));
                path.reset();
                path.moveTo(cx - r * 0.45f, cy - r * 0.25f);
                path.lineTo(cx - r * (0.75f + 0.5f * fl), cy);
                path.lineTo(cx - r * 0.45f, cy + r * 0.25f);
                path.close();
                c.drawPath(path, p);
                c.restore();
                break;
            }
            case "#star": {
                star(cx, cy, r * 1.1f, r * 0.45f, 5, t * 1.2f);
                c.drawPath(path, p);
                break;
            }
            case "#gem": {
                path.reset();
                path.moveTo(cx, cy - r);
                path.lineTo(cx + r * 0.8f, cy - r * 0.15f);
                path.lineTo(cx, cy + r);
                path.lineTo(cx - r * 0.8f, cy - r * 0.15f);
                path.close();
                c.drawPath(path, p);
                p.clearShadowLayer();
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(r * 0.08f);
                p.setColor(Neon.alpha(0xFFFFFFFF, alpha * 0.7f));
                c.drawLine(cx - r * 0.8f, cy - r * 0.15f, cx + r * 0.8f, cy - r * 0.15f, p);
                c.drawLine(cx, cy - r, cx - r * 0.3f, cy - r * 0.15f, p);
                c.drawLine(cx, cy - r, cx + r * 0.3f, cy - r * 0.15f, p);
                c.drawLine(cx - r * 0.3f, cy - r * 0.15f, cx, cy + r, p);
                c.drawLine(cx + r * 0.3f, cy - r * 0.15f, cx, cy + r, p);
                p.setStyle(Paint.Style.FILL);
                break;
            }
            case "#ghost": {
                path.reset();
                rf.set(cx - r, cy - r, cx + r, cy + r);
                path.arcTo(rf, 180, 180);
                path.lineTo(cx + r, cy + r * 0.85f);
                int bumps = 4;
                float bw = 2 * r / bumps;
                float wob = (float) Math.sin(t * 8) * r * 0.12f;
                for (int i = 0; i < bumps; i++) {
                    float x0 = cx + r - i * bw;
                    path.quadTo(x0 - bw / 2, cy + r * 0.55f + (i % 2 == 0 ? wob : -wob), x0 - bw, cy + r * 0.85f);
                }
                path.close();
                c.drawPath(path, p);
                p.clearShadowLayer();
                float ex = DIR_X[dir] * r * 0.15f, ey = DIR_Y[dir] * r * 0.15f;
                p.setColor(Neon.alpha(0xFFFFFFFF, alpha));
                c.drawCircle(cx - r * 0.35f, cy - r * 0.15f, r * 0.25f, p);
                c.drawCircle(cx + r * 0.35f, cy - r * 0.15f, r * 0.25f, p);
                p.setColor(Neon.alpha(0xFF101030, alpha));
                c.drawCircle(cx - r * 0.35f + ex, cy - r * 0.15f + ey, r * 0.12f, p);
                c.drawCircle(cx + r * 0.35f + ex, cy - r * 0.15f + ey, r * 0.12f, p);
                break;
            }
            case "#chomp": {
                float mouth = 38f * (float) Math.abs(Math.sin(t * 9));
                rf.set(cx - r, cy - r, cx + r, cy + r);
                c.drawArc(rf, dir * 90f + mouth, 360f - 2 * mouth, true, p);
                break;
            }
            case "#ring": {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(r * 0.3f);
                c.drawCircle(cx, cy, r * 0.8f, p);
                p.setStyle(Paint.Style.FILL);
                p.clearShadowLayer();
                p.setColor(Neon.alpha(0xFFFFFFFF, alpha));
                c.drawCircle(cx, cy, r * (0.2f + 0.08f * (float) Math.sin(t * 6)), p);
                break;
            }
            default: { // #blob — the terminal original ◖◗
                float pulse = 1f + 0.06f * (float) Math.sin(t * 8);
                c.drawCircle(cx, cy, r * pulse, p);
                p.clearShadowLayer();
                p.setColor(Neon.alpha(0xFF07040D, alpha));
                c.drawRect(cx - r * 0.07f, cy - r * 1.1f, cx + r * 0.07f, cy + r * 1.1f, p);
                break;
            }
        }
        p.clearShadowLayer();
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
    }

    static final int[] DIR_X = {1, 0, -1, 0};
    static final int[] DIR_Y = {0, 1, 0, -1};

    private static boolean isEmojiLike(String s) {
        int cp = s.codePointAt(0);
        return cp > 0x2000;
    }

    /** Builds a star into the shared path. */
    static Path star(float cx, float cy, float ro, float ri, int points, float rot) {
        path.reset();
        for (int i = 0; i < points * 2; i++) {
            double a = rot + Math.PI * i / points - Math.PI / 2;
            float rr = (i % 2 == 0) ? ro : ri;
            float x = cx + (float) Math.cos(a) * rr, y = cy + (float) Math.sin(a) * rr;
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        path.close();
        return path;
    }
}
