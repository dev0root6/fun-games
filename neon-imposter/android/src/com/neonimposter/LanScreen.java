package com.neonimposter;

import android.graphics.Canvas;
import android.graphics.Paint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Find a game on the LAN and connect to it. */
final class LanScreen extends Screen {
    private static final int MAX_FOUND = 5;
    private final Net net;
    private boolean connecting, handedOff;
    private String status = "scanning for games…";
    private float dt;
    private final Ui.Btn manualB, backB;
    private final Ui.Btn[] foundB = new Ui.Btn[MAX_FOUND];
    private final String[] foundIp = new String[MAX_FOUND];

    LanScreen(GameView v) {
        super(v);
        net = Net.client(v.act);
        manualB = add(new Ui.Btn("⌨  ENTER HOST IP", 0.7f));
        backB = add(new Ui.Btn("BACK", 0.9f));
        for (int i = 0; i < MAX_FOUND; i++) foundB[i] = add(new Ui.Btn("", 0.9f + 0.1f * i));
    }

    @Override
    void enter() {
        v.audio.song(Audio.MENU);
    }

    @Override
    void leave() {
        if (!handedOff) net.close();
    }

    @Override
    boolean back() {
        v.setScreen(new TitleScreen(v));
        return true;
    }

    private void connect(String ip) {
        ip = ip.trim();
        if (ip.isEmpty() || connecting) return;
        connecting = true;
        status = "connecting to " + ip + "…";
        net.connect(ip);
    }

    @Override
    void click(Ui.Btn b) {
        if (b == backB) back();
        else if (b == manualB) v.act.askText("Host IP address", "e.g. 192.168.1.23", "", 40, true, this::connect);
        else for (int i = 0; i < MAX_FOUND; i++) if (b == foundB[i] && foundIp[i] != null) connect(foundIp[i]);
    }

    @Override
    void update(float dt) {
        this.dt = dt;
        Net.Msg m;
        while ((m = net.inbox.poll()) != null) {
            switch (m.f[0]) {
                case "CONNECTED": {
                    Prefs p = v.prefs;
                    net.send("HELLO|" + Wire.enc(p.name) + "|" + Wire.enc(p.avatar) + "|" + p.hue);
                    status = "handshaking…";
                    break;
                }
                case "WELCOME":
                    handedOff = true;
                    v.audio.sfx(Audio.JOIN);
                    v.setScreen(new RoomScreen(v, net, Wire.i(m.f, 1)));
                    return;
                case "FULL":
                    status = "that game is full or already running";
                    net.disconnect();
                    connecting = false;
                    break;
                case "CONNFAIL":
                    status = "couldn't connect: " + (m.f.length > 1 ? m.f[1] : "?");
                    connecting = false;
                    break;
                case "HOSTGONE":
                    connecting = false;
                    break;
                case "ERR":
                    status = m.f.length > 1 ? m.f[1] : "network error";
                    break;
                default:
                    break;
            }
        }
    }

    @Override
    void draw(Canvas c) {
        float t = v.t;
        int w = c.getWidth(), h = c.getHeight();
        c.drawColor(0xFF07040D);
        Bg.warp(c, dt, 0.15f, dp, 0.3f);
        for (Ui.Btn b : foundB) b.visible = false;
        float top = v.safeTop + 10 * dp;
        ui.rainbow(c, "JOIN GAME", w / 2f, top + 44 * dp, 34, t, 3);
        ui.label(c, "same Wi-Fi or hotspot as the host", w / 2f, top + 72 * dp, 12, 0xFFBBBBCC, Paint.Align.CENTER, 0);

        float cx = w / 2f, cy = top + 170 * dp, r = 70 * dp;
        ui.stroke.setStrokeWidth(1.5f * dp);
        for (int i = 1; i <= 3; i++) {
            ui.stroke.setColor(0x44FF2B6A);
            c.drawCircle(cx, cy, r * i / 3f, ui.stroke);
        }
        float a = t * 3f;
        for (int i = 0; i < 24; i++) {
            float aa = a - i * 0.04f;
            ui.stroke.setStrokeWidth(3 * dp);
            ui.stroke.setColor(Neon.alpha(0xFFFF2B6A, 0.8f * (1 - i / 24f)));
            c.drawLine(cx, cy, cx + (float) Math.cos(aa) * r, cy + (float) Math.sin(aa) * r, ui.stroke);
        }

        float bw = w * 0.8f, bh = 56 * dp, x0 = (w - bw) / 2, y = cy + r + 34 * dp;
        List<Net.Found> list = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Net.Found f : net.found.values()) if (now - f.seen < 4000) list.add(f);
        Collections.sort(list, (p, q) -> p.ip.compareTo(q.ip));
        if (list.isEmpty()) {
            ui.label(c, "no games found yet" + "...".substring(0, 1 + (int) (t * 3) % 3), w / 2f, y + 16 * dp, 14,
                    0xFFAAAAAA, Paint.Align.CENTER, 0);
            y += 40 * dp;
        }
        for (int i = 0; i < MAX_FOUND && i < list.size(); i++) {
            Net.Found f = list.get(i);
            Ui.Btn b = foundB[i];
            foundIp[i] = f.ip;
            b.visible = true;
            b.enabled = f.open && !connecting;
            b.label = f.name + " · " + f.players + "/10" + (f.open ? "" : " · " + f.diff);
            b.at(x0, y, x0 + bw, y + bh);
            ui.button(c, b, t);
            y += bh + 10 * dp;
        }
        manualB.enabled = !connecting;
        manualB.at(x0, y + 6 * dp, x0 + bw, y + 6 * dp + bh * 0.85f);
        ui.button(c, manualB, t);

        ui.label(c, status, w / 2f, h - v.safeBottom - 92 * dp, 13, 0xFFFFE14D, Paint.Align.CENTER, 4);
        float bb = w * 0.5f;
        backB.at((w - bb) / 2, h - v.safeBottom - 70 * dp, (w + bb) / 2, h - v.safeBottom - 20 * dp);
        ui.button(c, backB, t);
    }
}
