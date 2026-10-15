package com.neonimposter;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Random;

final class Prefs {
    private final SharedPreferences sp;
    String name, avatar;
    int hue, diff, best;
    boolean music, sfx, named;

    Prefs(Context c) {
        sp = c.getSharedPreferences("neonimposter", Context.MODE_PRIVATE);
        name = sp.getString("name", "AGENT-" + (10 + new Random().nextInt(90)));
        avatar = sp.getString("avatar", "#blob");
        hue = sp.getInt("hue", -1);
        diff = sp.getInt("diff", 1);
        best = sp.getInt("best", 0);
        music = sp.getBoolean("music", true);
        sfx = sp.getBoolean("sfx", true);
        named = sp.getBoolean("named", false);
    }

    void save() {
        sp.edit().putString("name", name).putString("avatar", avatar).putInt("hue", hue)
                .putInt("diff", diff).putInt("best", best)
                .putBoolean("music", music).putBoolean("sfx", sfx)
                .putBoolean("named", named).apply();
    }
}
