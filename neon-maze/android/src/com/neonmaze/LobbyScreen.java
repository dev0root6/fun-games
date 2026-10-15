package com.neonmaze;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Host or join a LAN race. */
final class LobbyScreen extends Screen {
    private static final int CHOOSE = 0, HOST = 1, BROWSE = 2, CONNECTING = 3, JOINED = 4;
    private static final int MAX_FOUND = 5;

    private int mode = CHOOSE;
    private Session s;
    private boolean handedOff;
    private String status = "";
    private float dt;
    private final Ui.Btn hostB, joinB, backB, startB, dl, dr, manualB;
    private final Ui.Btn[] foundB = new Ui.Btn[MAX_FOUND];
    private final String[] foundIp = new String[MAX_FOUND];
    private final RectF tmp = new RectF();

    LobbyScreen(GameView v) {
        super(v);
        hostB = add(new Ui.Btn("★  HOST A RACE", 0.0f));
        joinB = add(new Ui.Btn("⌁  JOIN A RACE", 0.5f));
        startB = add(new Ui.Btn("▶  START RACE", 0.3f));
        dl = add(new Ui.Btn("◀", 0.5f));
        dr = add(new Ui.Btn("▶", 0.5f));
        manualB = add(new Ui.Btn("⌨  ENTER HOST IP", 0.7f));
        backB = add(new Ui.Btn("BACK", 0.9f));
        for (int i = 0; i < MAX_FOUND; i++) foundB[i] = add(new Ui.Btn("", 0.1f * i));
    }

    private Session.Player mePlayer(int id) {
        Prefs p = v.prefs;
        return new Session.Player(id, p.name, p.avatar, p.hue);
    }

    private void startHosting() {
        s = new Session(Net.host(v.act), true);
        s.myId = 0;
        s.diff = v.prefs.diff;
        s.players.put(0, mePlayer(0));
        mode = HOST;
        status = "waiting for racers on your network…";
    }

    private void startBrowsing() {
        s = new Session(Net.client(v.act), false);
        s.diff = v.prefs.diff;
        mode = BROWSE;
        status = "scanning for races…";
    }

    private void connect(String ip) {
        ip = ip.trim();
        if (ip.isEmpty() || s == null) return;
        s.net.connect(ip);
        mode = CONNECTING;
        status = "connecting to " + ip + "…";
    }

    private void closeSession() {
        if (s != null && !handedOff) s.net.close();
        s = null;
    }

    @Override
    void leave() {
        closeSession();
    }

    @Override
    boolean back() {
        if (mode == CHOOSE) {
            v.setScreen(new TitleScreen(v));
        } else {
            closeSession();
            mode = CHOOSE;
            status = "";
        }
        return true;
    }

    @Override
    void click(Ui.Btn b) {
        if (b == backB) back();
        else if (b == hostB) startHosting();
        else if (b == joinB) startBrowsing();
        else if (b == manualB) {
            String hint = "e.g. 192.168.1.23";
            v.act.askText("Host IP address", hint, "", 40, true, this::connect);
        } else if ((b == dl || b == dr) && s != null && s.host) {
            s.diff = (s.diff + (b == dl ? 3 : 1)) % 4;
            v.prefs.diff = s.diff;
            v.prefs.save();
            s.net.broadcast(s.lobbyLine(), -1);
        } else if (b == startB && s != null && s.host) {
            s.net.accepting = false;
            long seed = new Random().nextLong();
            s.round = 1;
            s.net.broadcast("START|" + seed + "|" + s.diff + "|1", -1);
            handedOff = true;
            v.setScreen(new PlayScreen(v, s, seed));
        } else {
            for (int i = 0; i < MAX_FOUND; i++) if (b == foundB[i] && foundIp[i] != null) connect(foundIp[i]);
        }
    }

    @Override
    void update(float dt) {
        this.dt = dt;
        if (s == null) return;
        if (s.host) s.net.beacon = s.beacon(v.prefs.name);
        Net.Msg m;
        while (s != null && (m = s.net.inbox.poll()) != null) {
            String[] f = m.f;
            switch (f[0]) {
                case "ERR":
                    status = f.length > 1 ? f[1] : "network error";
                    break;
                case "HELLO":
                    if (s.host && f.length >= 4) {
                        Session.Player p = new Session.Player(m.from, Net.dec(f[1]), Net.dec(f[2]), Net.parse(f[3]));
                        s.players.put(m.from, p);
                        s.net.sendTo(m.from, "WELCOME|" + m.from);
                        s.net.broadcast(s.lobbyLine(), -1);
                        status = p.name + " joined!";
                        v.audio.sfx(Audio.JOIN);
                        v.haptic(HapticFeedbackConstants.VIRTUAL_KEY);
                    }
                    break;
                case "LEFT":
                    if (s.host) {
                        Session.Player p = s.players.remove(m.from);
                        if (p != null) status = p.name + " left";
                        s.net.broadcast(s.lobbyLine(), -1);
                    }
                    break;
                case "CONNECTED": {
                    Prefs p = v.prefs;
                    s.net.send("HELLO|" + Net.enc(p.name) + "|" + Net.enc(p.avatar) + "|" + p.hue);
                    status = "handshaking…";
                    break;
                }
                case "WELCOME":
                    s.myId = Net.parse(f[1]);
                    mode = JOINED;
                    status = "you're in! waiting for the host to start…";
                    v.audio.sfx(Audio.JOIN);
                    v.haptic(HapticFeedbackConstants.VIRTUAL_KEY);
                    break;
                case "LOBBY":
                    s.applyLobby(f);
                    break;
                case "START":
                    if (!s.host && mode == JOINED && f.length >= 4) {
                        s.diff = Net.parse(f[2]);
                        s.round = Net.parse(f[3]);
                        handedOff = true;
                        v.setScreen(new PlayScreen(v, s, Net.parseLong(f[1])));
                        return;
                    }
                    break;
                case "FULL":
                    status = "that lobby is full or already racing";
                    s.net.disconnect();
                    mode = BROWSE;
                    break;
                case "CONNFAIL":
                    status = "couldn't connect: " + (f.length > 1 ? f[1] : "?");
                    mode = BROWSE;
                    break;
                case "HOSTGONE":
                    if (mode == JOINED || mode == CONNECTING) status = "host closed the lobby";
                    s.players.clear();
                    mode = BROWSE;
                    break;
                default:
                    break;
            }
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
        Bg.warp(c, dt, 0.18f, dp, 0.3f);
        for (Ui.Btn b : btns) b.visible = false;

        float top = v.safeTop + 10 * dp;
        ui.rainbow(c, "LAN RACE", w / 2f, top + 44 * dp, 40, t, 4);
        ui.label(c, "same Wi-Fi or hotspot · up to 4 racers", w / 2f, top + 74 * dp, 12, 0xFFBBBBCC, Paint.Align.CENTER, 0);
        ui.label(c, "everyone spawns in a different corner", w / 2f, top + 92 * dp, 12, 0xFFBBBBCC, Paint.Align.CENTER, 0);
        ui.label(c, "first to the portal wins", w / 2f, top + 110 * dp, 12, 0xFFFFE14D, Paint.Align.CENTER, 4);

        float bw = w * 0.76f, bh = 56 * dp, x0 = (w - bw) / 2;
        float y = top + 140 * dp;

        switch (mode) {
            case CHOOSE: {
                y = h * 0.4f;
                for (Ui.Btn b : new Ui.Btn[]{hostB, joinB}) {
                    b.visible = true;
                    b.at(x0, y, x0 + bw, y + bh);
                    ui.button(c, b, t);
                    y += bh + 16 * dp;
                }
                break;
            }
            case HOST: {
                List<String> ips = Net.localIps();
                String ipText = ips.isEmpty() ? "no network?" : android.text.TextUtils.join("  ·  ", ips);
                ui.label(c, "YOUR IP", w / 2f, y + 6 * dp, 11, 0xFF9999AA, Paint.Align.CENTER, 0);
                ui.label(c, ipText, w / 2f, y + 30 * dp, 18, Neon.hue(t * 0.3f, 1f), Paint.Align.CENTER, 8);
                y += 50 * dp;
                y = roster(c, t, w, y);
                y += 10 * dp;
                float sb = 46 * dp;
                dl.visible = dr.visible = true;
                dl.at(x0, y, x0 + sb, y + sb);
                dr.at(x0 + bw - sb, y, x0 + bw, y + sb);
                ui.button(c, dl, t);
                ui.button(c, dr, t);
                ui.label(c, PlayScreen.DIFF_NAMES[s.diff], w / 2f, y + sb / 2 + 7 * dp, 20,
                        Neon.hue(t * 0.4f + s.diff * 0.25f, 1f), Paint.Align.CENTER, 8);
                y += sb + 18 * dp;
                startB.visible = true;
                startB.label = s.players.size() < 2 ? "▶  START (solo test)" : "▶  START RACE  (" + s.players.size() + ")";
                startB.at(x0, y, x0 + bw, y + bh + 6 * dp);
                ui.button(c, startB, t);
                break;
            }
            case BROWSE:
            case CONNECTING: {
                radar(c, t, w / 2f, y + 70 * dp, 62 * dp);
                y += 156 * dp;
                List<Net.Found> list = new ArrayList<>();
                long now = System.currentTimeMillis();
                for (Net.Found f : s.net.found.values()) if (now - f.seen < 4000) list.add(f);
                Collections.sort(list, (a, b) -> a.ip.compareTo(b.ip));
                if (list.isEmpty()) {
                    ui.label(c, "no races found yet" + "...".substring(0, 1 + (int) (t * 3) % 3), w / 2f, y + 20 * dp, 14,
                            0xFFAAAAAA, Paint.Align.CENTER, 0);
                    y += 44 * dp;
                }
                for (int i = 0; i < MAX_FOUND; i++) {
                    foundIp[i] = null;
                    if (i >= list.size()) continue;
                    Net.Found f = list.get(i);
                    Ui.Btn b = foundB[i];
                    foundIp[i] = f.ip;
                    b.visible = true;
                    b.enabled = f.open && mode == BROWSE;
                    b.label = f.name + " · " + f.players + "/4 · " + f.diff + (f.open ? "" : " · racing");
                    b.at(x0, y, x0 + bw, y + bh);
                    ui.button(c, b, t);
                    y += bh + 10 * dp;
                }
                y += 6 * dp;
                manualB.visible = true;
                manualB.enabled = mode == BROWSE;
                manualB.at(x0, y, x0 + bw, y + bh * 0.85f);
                ui.button(c, manualB, t);
                break;
            }
            case JOINED: {
                y += 10 * dp;
                y = roster(c, t, w, y);
                y += 20 * dp;
                ui.label(c, "difficulty: " + PlayScreen.DIFF_NAMES[s.diff], w / 2f, y, 15,
                        Neon.hue(t * 0.4f, 1f), Paint.Align.CENTER, 6);
                ui.label(c, "you spawn: " + PlayScreen.CORNERS[s.slot(s.myId)], w / 2f, y + 24 * dp, 13,
                        0xFFFFFFFF, Paint.Align.CENTER, 0);
                break;
            }
            default:
                break;
        }

        if (!status.isEmpty()) {
            ui.label(c, status, w / 2f, h - v.safeBottom - 92 * dp, 13, 0xFFFFE14D, Paint.Align.CENTER, 4);
        }
        backB.visible = true;
        backB.label = mode == CHOOSE ? "BACK" : (mode == HOST ? "CLOSE LOBBY" : "LEAVE");
        float bb = w * 0.5f;
        backB.at((w - bb) / 2, h - v.safeBottom - 70 * dp, (w + bb) / 2, h - v.safeBottom - 20 * dp);
        ui.button(c, backB, t);
    }

    /** Player cards: avatar, name, spawn corner. Returns the y below them. */
    private float roster(Canvas c, float t, int w, float y) {
        float pad = 18 * dp, ch = 58 * dp;
        tmp.set(pad, y, w - pad, y + ch * 4 + 16 * dp);
        ui.panel(c, tmp, t, 0.9f);
        float yy = y + 8 * dp;
        List<Session.Player> ps = new ArrayList<>(s.players.values());
        Collections.sort(ps, (a, b) -> a.id - b.id);
        for (int i = 0; i < 4; i++) {
            float cy = yy + ch / 2;
            if (i < ps.size()) {
                Session.Player p = ps.get(i);
                Avatar.draw(c, ui.misc, p.avatar, p.hue, pad + 36 * dp, cy, 38 * dp, t, 0, 1f);
                String tag = (p.id == 0 ? "HOST" : "") + (p.id == s.myId ? (p.id == 0 ? " · YOU" : "YOU") : "");
                ui.label(c, p.name, pad + 70 * dp, cy + 1 * dp, 16, Neon.avatarColor(p.hue, t), Paint.Align.LEFT, 6);
                ui.label(c, PlayScreen.CORNERS[s.slot(p.id)] + (tag.isEmpty() ? "" : "  ·  " + tag),
                        pad + 70 * dp, cy + 19 * dp, 10, 0xFF9999AA, Paint.Align.LEFT, 0);
            } else {
                float a = 0.25f + 0.2f * (float) Math.sin(t * 3 + i);
                ui.stroke.setStrokeWidth(1.5f * dp);
                ui.stroke.setColor(Neon.alpha(0xFFFFFFFF, a));
                c.drawCircle(pad + 36 * dp, cy, 16 * dp, ui.stroke);
                ui.label(c, "open slot…", pad + 70 * dp, cy + 5 * dp, 13, Neon.alpha(0xFFFFFFFF, a + 0.1f),
                        Paint.Align.LEFT, 0);
            }
            yy += ch;
        }
        return tmp.bottom;
    }

    private void radar(Canvas c, float t, float cx, float cy, float r) {
        ui.stroke.setStrokeWidth(1.5f * dp);
        for (int i = 1; i <= 3; i++) {
            ui.stroke.setColor(0x4400FFC8);
            c.drawCircle(cx, cy, r * i / 3f, ui.stroke);
        }
        c.drawLine(cx - r, cy, cx + r, cy, ui.stroke);
        c.drawLine(cx, cy - r, cx, cy + r, ui.stroke);
        float a = t * 3f;
        for (int i = 0; i < 24; i++) {
            float aa = a - i * 0.04f;
            ui.stroke.setStrokeWidth(3 * dp);
            ui.stroke.setColor(Neon.alpha(0xFF00FFC8, 0.8f * (1 - i / 24f)));
            c.drawLine(cx, cy, cx + (float) Math.cos(aa) * r, cy + (float) Math.sin(aa) * r, ui.stroke);
        }
        if (s != null) {
            int i = 0;
            long now = System.currentTimeMillis();
            for (Net.Found f : s.net.found.values()) {
                if (now - f.seen > 4000) continue;
                float ang = (f.ip.hashCode() % 360) * 0.0174f, rr = r * (0.4f + 0.15f * (i % 3));
                ui.fill.setColor(Neon.hue(i * 0.3f, 1f));
                ui.fill.setShadowLayer(8 * dp, 0, 0, ui.fill.getColor());
                c.drawCircle(cx + (float) Math.cos(ang) * rr, cy + (float) Math.sin(ang) * rr, 5 * dp, ui.fill);
                ui.fill.clearShadowLayer();
                i++;
            }
        }
    }
}
