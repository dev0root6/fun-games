package com.neonmaze;

import android.graphics.Color;

import java.util.Locale;

/** Color and formatting helpers. Only touched from the game thread. */
final class Neon {
    private static final float[] HSV = new float[3];

    private Neon() {}

    /** Hue in turns (0..1, wraps), value 0..1. */
    static int hue(float turns, float v) {
        return hueSat(turns, 0.88f, v);
    }

    static int hueSat(float turns, float s, float v) {
        float h = turns - (float) Math.floor(turns);
        HSV[0] = h * 360f;
        HSV[1] = s;
        HSV[2] = clamp(v, 0f, 1f);
        return Color.HSVToColor(HSV);
    }

    static int alpha(int color, float a) {
        return (color & 0x00FFFFFF) | ((int) (clamp(a, 0f, 1f) * 255) << 24);
    }

    static int gray(float v) {
        int c = (int) (clamp(v, 0f, 1f) * 255);
        return Color.rgb(c, c, c);
    }

    static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Avatar tint: a fixed hue in degrees, or -1 for a cycling rainbow. */
    static int avatarColor(int hueDeg, float t) {
        return hueDeg < 0 ? hue(t * 0.3f, 1f) : hue(hueDeg / 360f, 1f);
    }

    static String time(float sec) {
        int m = (int) (sec / 60);
        float s = sec - m * 60;
        return String.format(Locale.US, "%02d:%04.1f", m, s);
    }
}
