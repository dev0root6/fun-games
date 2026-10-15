package com.neonimposter;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Protocol helpers. Pure Java so the game core can be tested off-device. */
final class Wire {
    private Wire() {}

    static String enc(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    static String dec(String s) {
        try {
            return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "?";
        }
    }

    static int i(String[] f, int idx) {
        if (idx >= f.length) return 0;
        try {
            return Integer.parseInt(f[idx]);
        } catch (Exception e) {
            return 0;
        }
    }

    static String s(String[] f, int idx) {
        return idx < f.length ? dec(f[idx]) : "";
    }

    static String[] split(String line) {
        return line.split("\\|", -1);
    }

    /** Lowercase letters and digits only — used to compare words. */
    static String norm(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toLowerCase().toCharArray()) if (Character.isLetterOrDigit(c)) b.append(c);
        return b.toString();
    }

    /** Clue text: one line, squeezed spaces, capped length. */
    static String clean(String s, int max) {
        s = s.replace('\n', ' ').replace('|', ' ').trim().replaceAll("\\s+", " ");
        return s.length() > max ? s.substring(0, max) : s;
    }
}
