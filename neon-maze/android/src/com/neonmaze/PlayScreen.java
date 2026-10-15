package com.neonmaze;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/** The maze itself — solo runs and LAN races share this screen. */
final class PlayScreen extends Screen {
    static final String[] DIFF_NAMES = {"EASY", "NORMAL", "HARD", "INSANE"};
    /** cells wide, cells tall, torch radius — same table as the terminal game. */
    static final int[][] DIFF_CFG = {{12, 7, 9}, {18, 10, 7}, {24, 13, 5}, {32, 17, 3}};
    static final String[] CORNERS = {"TOP-LEFT", "BOTTOM-RIGHT", "BOTTOM-LEFT", "TOP-RIGHT"};

    private static final int COUNTDOWN = 0, PLAY = 1, CLEAR = 2, FINISHED = 3, RESULT = 4;
    private static final String[] BONKS = {"BONK", "OUCH", "THAT'S A WALL", "NOPE", "*thud*", "walls bite back"};
    private static final String MARQUEE = "★ NEON MAZE ★   find the spinning portal   ·   ◆ grab gems for points   ·   "
            + "✦ torch surges widen your light   ·   drag anywhere to steer   ·   ";
    private static final int[] DX = {1, 0, -1, 0}, DY = {0, 1, 0, -1};

    private static final class Pt {
        float x, y, vx, vy, life, max, hue, g;
        String text;
    }

    private final Session s;
    private final boolean multi;
    private final Random rnd = new Random();
    private int diff, level;

    private Maze mz;
    private int px, py, dir, R, visR = -1;
    private float drawX, drawY, camX, camY;
    private boolean camInit;
    private float[] vis, trail;
    private int[] spill;
    private boolean[] seen;
    private int seenVersion, miniVersion = -1, miniCount;
    private float[] miniPts = new float[0];

    private int moves, gems, score, displayScore;
    private boolean fog = true, paused, hostGone;
    private float levelStart, pausedAcc, pauseStart, boostUntil;
    private float shake, flash, energy, msgUntil, lastDt;
    private String msg = "";
    private int phase;
    private float phaseAt, clearTime;
    private int timeBonus, pathBonus, gemBonus;
    private int winnerId = -1;
    private float winnerTime;

    private final ArrayList<Pt> worldParts = new ArrayList<>(), screenParts = new ArrayList<>();
    private float nextFirework;
    private int lastW, lastH;

    // joystick
    private int jId = -1, jDir = -1;
    private float jx0, jy0, jx, jy, jRepeat;
    private boolean jBlocked;

    // layout
    private final RectF hud = new RectF(), view = new RectF(), mini = new RectF(), race = new RectF();
    private float T, bottomY;
    private final RectF tmp = new RectF();
    private final Path path = new Path();
    private final Paint fogP = new Paint();
    private final Matrix fogM = new Matrix();
    private float fogKey = -1;
    private final Paint miniP = new Paint();
    private float miniKey = -1;

    private final Ui.Btn pauseB, resumeB, fogB, rerollB, quitB, nextB, menuB, rematchB, leaveB, musicB, sfxB;
    private int lastCount = -1;

    /** Solo run. */
    PlayScreen(GameView v, int diff) {
        super(v);
        this.s = null;
        this.multi = false;
        this.diff = diff;
        this.level = 1;
        pauseB = add(new Ui.Btn("II", 0.8f));
        resumeB = add(new Ui.Btn("RESUME", 0.3f));
        fogB = add(new Ui.Btn("FOG: ON", 0.5f));
        rerollB = add(new Ui.Btn("NEW MAZE", 0.65f));
        quitB = add(new Ui.Btn("QUIT TO MENU", 0f));
        nextB = add(new Ui.Btn("NEXT LEVEL ▶", 0.3f));
        menuB = add(new Ui.Btn("MENU", 0f));
        rematchB = add(new Ui.Btn("REMATCH ⟳", 0.3f));
        leaveB = add(new Ui.Btn("LEAVE", 0f));
        musicB = add(new Ui.Btn("", 0.75f));
        sfxB = add(new Ui.Btn("", 0.6f));
        startLevel(rnd.nextLong());
    }

    /** LAN race. */
    PlayScreen(GameView v, Session s, long seed) {
        super(v);
        this.s = s;
        this.multi = true;
        this.diff = s.diff;
        this.level = s.round;
        pauseB = add(new Ui.Btn("II", 0.8f));
        resumeB = add(new Ui.Btn("RESUME", 0.3f));
        fogB = add(new Ui.Btn("FOG: ON", 0.5f));
        rerollB = add(new Ui.Btn("NEW MAZE", 0.65f));
        quitB = add(new Ui.Btn("LEAVE RACE", 0f));
        nextB = add(new Ui.Btn("NEXT LEVEL ▶", 0.3f));
        menuB = add(new Ui.Btn("MENU", 0f));
        rematchB = add(new Ui.Btn("REMATCH ⟳", 0.3f));
        leaveB = add(new Ui.Btn(s.host ? "END" : "LEAVE", 0f));
        musicB = add(new Ui.Btn("", 0.75f));
        sfxB = add(new Ui.Btn("", 0.6f));
        startLevel(seed);
    }

    // ── state ─────────────────────────────────────────────────────────────

    private void startLevel(long seed) {
        int[] cfg = DIFF_CFG[diff];
        // portrait screen: the maze is taller than it is wide
        int cw = cfg[1] + (level - 1) * 2, ch = cfg[0] + (level - 1) * 3;
        mz = new Maze(cw, ch, seed, multi);
        int slot = multi ? s.slot(s.myId) : 0;
        px = mz.originX[slot];
        py = mz.originY[slot];
        dir = px > mz.W / 2 ? 2 : 0;
        drawX = px;
        drawY = py;
        camInit = false;
        vis = new float[mz.W * mz.H];
        spill = new int[mz.W * mz.H];
        trail = new float[mz.W * mz.H];
        Arrays.fill(trail, -1f);
        seen = new boolean[mz.W * mz.H];
        miniVersion = -1;
        R = cfg[2];
        boostUntil = 0;
        moves = gems = 0;
        if (multi) score = displayScore = 0;
        worldParts.clear();
        screenParts.clear();
        paused = false;
        winnerId = -1;
        updateVis();
        if (multi) {
            for (Session.Player p : s.players.values()) {
                int sl = s.slot(p.id);
                p.x = mz.originX[sl];
                p.y = mz.originY[sl];
                p.dx = p.x;
                p.dy = p.y;
                p.score = 0;
            }
        }
        phase = COUNTDOWN;
        phaseAt = v.t;
        jDir = -1;
    }

    private int radius() {
        return R + (v.t < boostUntil ? 3 : 0);
    }

    private float elapsed() {
        if (phase == COUNTDOWN) return 0;
        float end = (!multi && paused) ? pauseStart : v.t;
        return Math.max(0, end - levelStart - pausedAcc);
    }

    private boolean los(int x1, int y1) {
        int x0 = px, y0 = py;
        int dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
        int err = dx + dy, x = x0, y = y0;
        while (x != x1 || y != y1) {
            if ((x != x0 || y != y0) && mz.wall(x, y)) return false;
            int e2 = 2 * err;
            if (e2 >= dy) {
                err += dy;
                x += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y += sy;
            }
        }
        return true;
    }

    private void updateVis() {
        int r = radius();
        visR = r;
        Arrays.fill(vis, -1f);
        for (int y = py - r; y <= py + r; y++) {
            for (int x = px - r; x <= px + r; x++) {
                if (x < 0 || y < 0 || x >= mz.W || y >= mz.H) continue;
                float d = (float) Math.hypot(x - px, y - py);
                if (d <= r && los(x, y)) reveal(x, y, d);
            }
        }
        // light spills one tile around every lit floor tile, so side openings and corridor walls show up
        int n = 0;
        for (int y = py - r; y <= py + r; y++) {
            for (int x = px - r; x <= px + r; x++) {
                if (x < 0 || y < 0 || x >= mz.W || y >= mz.H) continue;
                int k = y * mz.W + x;
                if (vis[k] >= 0 && mz.g[k] == 0) spill[n++] = k;
            }
        }
        for (int i = 0; i < n; i++) {
            int x0 = spill[i] % mz.W, y0 = spill[i] / mz.W;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int x = x0 + dx, y = y0 + dy;
                    if (x < 0 || y < 0 || x >= mz.W || y >= mz.H || vis[y * mz.W + x] >= 0) continue;
                    float d = (float) Math.hypot(x - px, y - py);
                    if (d <= r + 1) reveal(x, y, Math.min(d, r));
                }
            }
        }
    }

    private void reveal(int x, int y, float d) {
        int k = y * mz.W + x;
        vis[k] = d;
        if (!seen[k]) {
            seen[k] = true;
            seenVersion++;
        }
    }

    private void say(String text, float dur) {
        msg = text;
        msgUntil = v.t + dur;
    }

    private void burst(float x, float y, float hue, int n, String text) {
        for (int i = 0; i < n; i++) {
            Pt p = new Pt();
            double a = rnd.nextDouble() * Math.PI * 2;
            float sp = 2 + rnd.nextFloat() * 5;
            p.x = x;
            p.y = y;
            p.vx = (float) Math.cos(a) * sp;
            p.vy = (float) Math.sin(a) * sp;
            p.life = p.max = 0.8f;
            p.hue = hue + (rnd.nextFloat() - 0.5f) * 0.16f;
            p.g = 6;
            worldParts.add(p);
        }
        if (text != null) {
            Pt p = new Pt();
            p.x = x;
            p.y = y - 0.3f;
            p.vy = -1.6f;
            p.life = p.max = 1.2f;
            p.hue = hue;
            p.text = text;
            worldParts.add(p);
        }
    }

    private void firework(float x, float y) {
        float hue = rnd.nextFloat();
        for (int i = 0; i < 40; i++) {
            Pt p = new Pt();
            double a = rnd.nextDouble() * Math.PI * 2;
            float sp = (60 + rnd.nextFloat() * 260) * dp;
            p.x = x;
            p.y = y;
            p.vx = (float) Math.cos(a) * sp;
            p.vy = (float) Math.sin(a) * sp;
            p.life = p.max = 1.1f + rnd.nextFloat() * 0.6f;
            p.hue = hue + (rnd.nextFloat() - 0.5f) * 0.1f;
            p.g = 220 * dp;
            screenParts.add(p);
        }
    }

    private void step(int d) {
        if (phase != PLAY || paused) return;
        dir = d;
        int nx = px + DX[d], ny = py + DY[d];
        if (mz.wall(nx, ny)) {
            shake = 0.2f;
            flash = 0.25f;
            energy = Math.min(1f, energy + 0.4f);
            say(BONKS[rnd.nextInt(BONKS.length)], 0.8f);
            v.audio.sfx(Audio.BONK);
            v.haptic(HapticFeedbackConstants.LONG_PRESS);
            jBlocked = true;
            return;
        }
        px = nx;
        py = ny;
        moves++;
        v.audio.sfx(Audio.STEP);
        int k = py * mz.W + px;
        trail[k] = v.t;
        energy = Math.min(1f, energy + 0.15f);
        if (mz.gems.remove(k)) {
            gems++;
            score += 100;
            energy = 1f;
            burst(px + 0.5f, py + 0.5f, rnd.nextFloat(), 14, "+100");
            v.audio.sfx(Audio.GEM);
            v.haptic(HapticFeedbackConstants.VIRTUAL_KEY);
            if (multi) send("GEM|" + s.myId + "|" + px + "|" + py);
        }
        if (mz.powers.remove(k)) {
            boostUntil = v.t + 12;
            energy = 1f;
            burst(px + 0.5f, py + 0.5f, 0.14f, 24, "SURGE!");
            v.audio.sfx(Audio.POWER);
            say("✦ TORCH SURGE ✦   light radius +3", 2.5f);
            v.haptic(HapticFeedbackConstants.VIRTUAL_KEY);
            if (multi) send("POW|" + s.myId + "|" + px + "|" + py);
        }
        updateVis();
        if (multi) {
            Session.Player me = s.me();
            if (me != null) {
                me.x = px;
                me.y = py;
                me.score = score;
            }
            send("MOVE|" + s.myId + "|" + px + "|" + py + "|" + score + "|" + dir);
        }
        if (px == mz.exitX && py == mz.exitY) reachExit();
    }

    private void reachExit() {
        float t = v.t;
        clearTime = elapsed();
        jDir = -1;
        if (!multi) {
            int par = Math.max(1, mz.par[0]);
            timeBonus = Math.max(0, (int) (1500 - clearTime * 10)) * level;
            pathBonus = Math.max(0, (int) ((2f - moves / (float) par) * 400));
            gemBonus = gems == mz.totalGems ? 750 : 0;
            score += timeBonus + pathBonus + gemBonus;
            if (score > v.prefs.best) {
                v.prefs.best = score;
                v.prefs.save();
            }
            phase = CLEAR;
            phaseAt = t;
            v.audio.sfx(Audio.WIN);
            nextFirework = t;
            v.haptic(HapticFeedbackConstants.LONG_PRESS);
            return;
        }
        phase = FINISHED;
        phaseAt = t;
        int ms = (int) (clearTime * 1000);
        if (s.host) hostFinish(s.myId, ms);
        else s.net.send("FIN|" + s.myId + "|" + ms);
    }

    // ── network ───────────────────────────────────────────────────────────

    private void send(String line) {
        if (s.host) s.net.broadcast(line, -1);
        else s.net.send(line);
    }

    private void hostFinish(int id, int ms) {
        if (winnerId >= 0) return;
        s.net.broadcast("WIN|" + id + "|" + ms, -1);
        showWinner(id, ms);
    }

    private void showWinner(int id, int ms) {
        winnerId = id;
        winnerTime = ms / 1000f;
        v.audio.sfx(id == s.myId ? Audio.WIN : Audio.LOSE);
        phase = RESULT;
        phaseAt = v.t;
        nextFirework = v.t;
        paused = false;
        jDir = -1;
        v.haptic(HapticFeedbackConstants.LONG_PRESS);
    }

    private void rematch() {
        long seed = rnd.nextLong();
        s.round++;
        level = s.round;
        s.net.broadcast("START|" + seed + "|" + s.diff + "|" + s.round, -1);
        startLevel(seed);
    }

    private void pollNet() {
        Net.Msg m;
        while ((m = s.net.inbox.poll()) != null) {
            String[] f = m.f;
            switch (f[0]) {
                case "MOVE": {
                    if (f.length < 6) break;
                    int id = Net.parse(f[1]);
                    if (s.host && id != m.from) break;
                    Session.Player p = s.players.get(id);
                    if (p != null && id != s.myId) {
                        p.x = Net.parse(f[2]);
                        p.y = Net.parse(f[3]);
                        p.score = Net.parse(f[4]);
                        p.dir = Net.parse(f[5]) & 3;
                    }
                    if (s.host) s.net.broadcast(String.join("|", f), m.from);
                    break;
                }
                case "GEM":
                case "POW": {
                    if (f.length < 4) break;
                    int x = Net.parse(f[2]), y = Net.parse(f[3]);
                    if (x < 0 || y < 0 || x >= mz.W || y >= mz.H) break;
                    int k = y * mz.W + x;
                    boolean had = f[0].equals("GEM") ? mz.gems.remove(k) : mz.powers.remove(k);
                    if (had && vis[k] >= 0) burst(x + 0.5f, y + 0.5f, 0.0f, 10, null);
                    if (s.host) s.net.broadcast(String.join("|", f), m.from);
                    break;
                }
                case "FIN":
                    if (s.host && f.length >= 3) hostFinish(m.from, Net.parse(f[2]));
                    break;
                case "WIN":
                    if (!s.host && f.length >= 3) showWinner(Net.parse(f[1]), Net.parse(f[2]));
                    break;
                case "START":
                    if (!s.host && f.length >= 4) {
                        s.diff = Net.parse(f[2]);
                        s.round = Net.parse(f[3]);
                        diff = s.diff;
                        level = s.round;
                        startLevel(Net.parseLong(f[1]));
                    }
                    break;
                case "LEFT": {
                    int id = f.length > 1 ? Net.parse(f[1]) : m.from;
                    Session.Player p = s.players.remove(id);
                    if (p != null) say(p.name + " left the race", 2.5f);
                    if (s.host) {
                        s.net.kick(id);
                        s.net.broadcast("LEFT|" + id, -1);
                    }
                    break;
                }
                case "HELLO":
                    if (s.host) {
                        s.net.sendTo(m.from, "FULL");
                        s.net.kick(m.from);
                    }
                    break;
                case "HOSTGONE":
                    hostGone = true;
                    paused = false;
                    break;
                default:
                    break;
            }
        }
    }

    // ── loop ──────────────────────────────────────────────────────────────

    @Override
    void update(float dt) {
        float t = v.t;
        lastDt = dt;
        if (multi) pollNet();
        if (phase == COUNTDOWN && multi) {
            int n = (int) Math.ceil(3f - (t - phaseAt));
            if (n != lastCount && n >= 1) v.audio.sfx(Audio.BEEP);
            lastCount = n;
        }
        if (phase == COUNTDOWN && t - phaseAt >= (multi ? 3f : 1f)) {
            v.audio.sfx(Audio.GO);
            lastCount = -1;
            phase = PLAY;
            phaseAt = t;
            levelStart = t;
            pausedAcc = 0;
        }
        if (radius() != visR) updateVis();
        if (phase == PLAY && !paused && jDir >= 0 && !jBlocked) {
            jRepeat -= dt;
            if (jRepeat <= 0) {
                step(jDir);
                jRepeat = 0.115f;
            }
        }
        float k = Math.min(1f, dt * 16f);
        drawX += (px - drawX) * k;
        drawY += (py - drawY) * k;
        if (multi) {
            for (Session.Player p : s.players.values()) {
                p.dx += (p.x - p.dx) * k;
                p.dy += (p.y - p.dy) * k;
            }
        }
        energy *= Math.max(0f, 1f - dt * 2.2f);
        shake = Math.max(0, shake - dt);
        flash = Math.max(0, flash - dt);
        if (displayScore < score) displayScore += Math.max(1, (score - displayScore) / 8);

        for (int i = worldParts.size() - 1; i >= 0; i--) {
            Pt p = worldParts.get(i);
            p.life -= dt;
            if (p.life <= 0) {
                worldParts.remove(i);
                continue;
            }
            p.vy += p.g * dt;
            p.x += p.vx * dt;
            p.y += p.vy * dt;
        }
        for (int i = screenParts.size() - 1; i >= 0; i--) {
            Pt p = screenParts.get(i);
            p.life -= dt;
            if (p.life <= 0) {
                screenParts.remove(i);
                continue;
            }
            p.vy += p.g * dt;
            p.vx *= 0.985f;
            p.x += p.vx * dt;
            p.y += p.vy * dt;
        }
        boolean party = phase == CLEAR || (phase == RESULT && !hostGone);
        if (party && t >= nextFirework && lastW > 0) {
            firework(lastW * (0.1f + rnd.nextFloat() * 0.8f), lastH * (0.1f + rnd.nextFloat() * 0.45f));
            if (t - phaseAt > 0.6f) v.audio.sfx(Audio.POP);
            nextFirework = t + 0.18f + rnd.nextFloat() * 0.35f;
        }
    }

    @Override
    void raw(MotionEvent e) {
        int a = e.getActionMasked();
        int idx = e.getActionIndex();
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
            float x = e.getX(idx), y = e.getY(idx);
            if (jId == -1 && !anyPressed() && !paused && view.contains(x, y)
                    && (phase == PLAY || phase == COUNTDOWN)) {
                jId = e.getPointerId(idx);
                jx0 = jx = x;
                jy0 = jy = y;
                jDir = -1;
                jBlocked = false;
            }
        } else if (a == MotionEvent.ACTION_MOVE) {
            if (jId < 0) return;
            int pi = e.findPointerIndex(jId);
            if (pi < 0) return;
            jx = e.getX(pi);
            jy = e.getY(pi);
            float dx = jx - jx0, dy = jy - jy0, dist = (float) Math.hypot(dx, dy);
            if (dist > 24 * dp) {
                int d = Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? 0 : 2) : (dy > 0 ? 1 : 3);
                if (d != jDir) {
                    jDir = d;
                    jBlocked = false;
                    step(d);
                    jRepeat = 0.2f;
                }
                float lim = 64 * dp;
                if (dist > lim) {
                    jx0 = jx - dx / dist * lim;
                    jy0 = jy - dy / dist * lim;
                }
            } else {
                jDir = -1;
            }
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL
                || (a == MotionEvent.ACTION_POINTER_UP && e.getPointerId(idx) == jId)) {
            jId = -1;
            jDir = -1;
        }
    }

    private void togglePause() {
        if (paused) {
            paused = false;
            if (!multi) pausedAcc += v.t - pauseStart;
        } else {
            paused = true;
            pauseStart = v.t;
            jId = -1;
            jDir = -1;
        }
    }

    private void toMenu() {
        v.setScreen(new TitleScreen(v));
    }

    @Override
    void leave() {
        if (multi) s.net.close();
    }

    @Override
    boolean back() {
        if (hostGone || phase == CLEAR || phase == RESULT) {
            toMenu();
        } else {
            togglePause();
        }
        return true;
    }

    @Override
    void click(Ui.Btn b) {
        if (b == pauseB || b == resumeB) togglePause();
        else if (b == fogB) {
            fog = !fog;
            say(fog ? "fog restored" : "fog lifted. we saw nothing.", 2f);
        } else if (b == musicB) {
            v.setMusic(!v.prefs.music);
        } else if (b == sfxB) {
            v.setSfx(!v.prefs.sfx);
        } else if (b == rerollB) {
            startLevel(rnd.nextLong());
            say("maze rerolled", 2f);
        } else if (b == quitB || b == menuB || b == leaveB) toMenu();
        else if (b == nextB) {
            level++;
            startLevel(rnd.nextLong());
            say("LEVEL " + level + " — the maze grows", 3f);
        } else if (b == rematchB) rematch();
    }

    // ── drawing ───────────────────────────────────────────────────────────

    @Override
    void enter() {
        v.audio.song(Audio.GAME);
    }

    @Override
    void draw(Canvas c) {
        float t = v.t;
        int w = c.getWidth(), h = c.getHeight();
        lastW = w;
        lastH = h;
        c.drawColor(0xFF07040D);
        for (Ui.Btn b : btns) b.visible = false;

        float pad = 10 * dp, top = v.safeTop + 4 * dp;
        hud.set(pad, top, w - pad, top + 132 * dp);
        float y = hud.bottom + 6 * dp;
        if (multi) {
            race.set(pad, y, w - pad, y + 50 * dp);
            y = race.bottom + 6 * dp;
        }
        bottomY = h - v.safeBottom - 42 * dp;
        view.set(0, y, w, bottomY - 4 * dp);
        T = w / 13f;
        float ms = hud.height() - 16 * dp;
        mini.set(hud.right - 8 * dp - ms, hud.top + 8 * dp, hud.right - 8 * dp, hud.bottom - 8 * dp);

        drawMaze(c, t);
        drawHud(c, t);
        if (multi) drawRace(c, t);
        drawBottom(c, t, w);
        drawJoystick(c, t);

        if (phase == COUNTDOWN || (phase == PLAY && t - phaseAt < 0.7f)) drawCountdown(c, t, w);
        if (hostGone) drawHostGone(c, t, w, h);
        else if (phase == CLEAR) drawClear(c, t, w, h);
        else if (phase == RESULT) drawResult(c, t, w, h);
        else if (phase == FINISHED) drawFinished(c, t, w);
        else if (paused) drawPause(c, t, w, h);

        for (Pt p : screenParts) {
            float l = p.life / p.max;
            ui.fill.setShader(null);
            ui.fill.setColor(Neon.alpha(Neon.hue(p.hue, 1f), Math.min(1f, l + 0.2f)));
            c.drawCircle(p.x, p.y, (1f + 2.2f * l) * dp, ui.fill);
        }
    }

    private void drawMaze(Canvas c, float t) {
        Paint fill = ui.fill, st = ui.stroke;
        fill.setShader(null);
        c.save();
        c.clipRect(view);
        if (shake > 0) {
            float sh = shake * 40 * dp;
            c.translate((rnd.nextFloat() - 0.5f) * sh, (rnd.nextFloat() - 0.5f) * sh);
        }
        float vtw = view.width() / T, vth = view.height() / T;
        float tx = mz.W <= vtw ? (mz.W - 1) / 2f : Neon.clamp(drawX, vtw / 2 - 0.5f, mz.W - vtw / 2 - 0.5f);
        float ty = mz.H <= vth ? (mz.H - 1) / 2f : Neon.clamp(drawY, vth / 2 - 0.5f, mz.H - vth / 2 - 0.5f);
        if (!camInit) {
            camX = tx;
            camY = ty;
            camInit = true;
        } else {
            float k = Math.min(1f, lastDt * 9f);
            camX += (tx - camX) * k;
            camY += (ty - camY) * k;
        }
        float ox = view.centerX() - camX * T - T / 2, oy = view.centerY() - camY * T - T / 2;
        int x0 = (int) Math.floor((view.left - ox) / T) - 1, x1 = (int) Math.ceil((view.right - ox) / T) + 1;
        int y0 = (int) Math.floor((view.top - oy) / T) - 1, y1 = (int) Math.ceil((view.bottom - oy) / T) + 1;
        int r = radius();
        float speed = t < boostUntil ? 0.8f : 0.2f;

        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                float L = ox + x * T, Tp = oy + y * T;
                if (x < 0 || y < 0 || x >= mz.W || y >= mz.H) {
                    star(c, x, y, L, Tp, t);
                    continue;
                }
                int k = y * mz.W + x;
                float f;
                boolean lit;
                if (fog) {
                    lit = vis[k] >= 0;
                    f = lit ? vis[k] / Math.max(1, r) : 1f;
                } else {
                    lit = true;
                    f = Math.min(1f, (float) Math.hypot(x - px, y - py) / (r * 3f));
                }
                boolean wall = mz.g[k] == 1;
                if (!lit && !seen[k]) {
                    star(c, x, y, L, Tp, t);
                    continue;
                }
                if (wall) {
                    float a = lit ? 1f - f * 0.72f : 0.3f;
                    float hue = (x + y) * 0.035f - t * speed;
                    int col = lit ? Neon.hue(hue, 1f) : Neon.gray(0.55f);
                    fill.setColor(lit ? Neon.alpha(Neon.hue(hue, 0.24f), a) : 0x1FFFFFFF);
                    c.drawRect(L, Tp, L + T + 0.5f, Tp + T + 0.5f, fill);
                    // neon edges only where the wall meets open floor
                    st.setStrokeCap(Paint.Cap.ROUND);
                    for (int side = 0; side < 4; side++) {
                        int nx = x + DX[side], ny = y + DY[side];
                        if (nx < 0 || ny < 0 || nx >= mz.W || ny >= mz.H || mz.g[ny * mz.W + nx] == 1) continue;
                        float ax, ay, bx, by;
                        if (side == 0) { ax = L + T; ay = Tp; bx = L + T; by = Tp + T; }
                        else if (side == 2) { ax = L; ay = Tp; bx = L; by = Tp + T; }
                        else if (side == 1) { ax = L; ay = Tp + T; bx = L + T; by = Tp + T; }
                        else { ax = L; ay = Tp; bx = L + T; by = Tp; }
                        if (lit) {
                            st.setStrokeWidth(8 * dp);
                            st.setColor(Neon.alpha(col, a * 0.22f));
                            c.drawLine(ax, ay, bx, by, st);
                        }
                        st.setStrokeWidth((lit ? 2.2f : 1.2f) * dp);
                        st.setColor(Neon.alpha(col, lit ? a : 0.3f));
                        c.drawLine(ax, ay, bx, by, st);
                    }
                    continue;
                }
                float cx = L + T / 2, cy = Tp + T / 2;
                if (lit) {
                    fill.setColor(Neon.alpha(Neon.hue((x + y) * 0.035f - t * speed, 1f), (1f - f) * 0.09f));
                    c.drawRect(L, Tp, L + T + 0.5f, Tp + T + 0.5f, fill);
                }
                if (x == mz.exitX && y == mz.exitY) {
                    portal(c, cx, cy, t, lit ? 1f : 0.4f);
                } else if (mz.gems.contains(k)) {
                    gem(c, cx, cy, t, x, lit ? 1f - f * 0.5f : 0.3f, lit);
                } else if (mz.powers.contains(k)) {
                    if (lit) power(c, cx, cy, t);
                } else if (trail[k] >= 0) {
                    float age = t - trail[k];
                    if (age < 20) {
                        float a = (1 - age / 20f) * (lit ? 1 - f * 0.5f : 0.4f);
                        fill.setColor(Neon.alpha(Neon.hue(trail[k] * 0.15f, 1f), a));
                        c.drawCircle(cx, cy, T * (age < 2 ? 0.12f : 0.08f), fill);
                    } else {
                        fill.setColor(0x33FFFFFF);
                        c.drawCircle(cx, cy, T * 0.06f, fill);
                    }
                } else if (lit && f < 0.6f) {
                    fill.setColor(Neon.alpha(0xFFFFFFFF, (0.6f - f) * 0.3f));
                    c.drawCircle(cx, cy, 1.4f * dp, fill);
                }
            }
        }

        // other racers
        if (multi) {
            for (Session.Player p : s.players.values()) {
                if (p.id == s.myId) continue;
                int k = p.y * mz.W + p.x;
                if (fog && (k < 0 || k >= vis.length || vis[k] < 0)) continue;
                float cx = ox + p.dx * T + T / 2, cy = oy + p.dy * T + T / 2;
                Avatar.draw(c, ui.misc, p.avatar, p.hue, cx, cy, T * 0.9f, t, p.dir, 0.95f);
                ui.label(c, p.name, cx, cy - T * 0.62f, 10, Neon.avatarColor(p.hue, t), Paint.Align.CENTER, 4);
            }
        }

        // me
        float mx = ox + drawX * T + T / 2, my = oy + drawY * T + T / 2;
        int myCol = Neon.avatarColor(v.prefs.hue, t);
        st.setStrokeWidth(2 * dp);
        st.setColor(Neon.alpha(myCol, 0.35f + 0.25f * (float) Math.sin(t * 5)));
        c.drawCircle(mx, my, T * (0.55f + 0.05f * (float) Math.sin(t * 5)), st);
        Avatar.draw(c, ui.misc, v.prefs.avatar, v.prefs.hue, mx, my, T * 0.95f, t, dir, 1f);

        for (Pt p : worldParts) {
            float l = p.life / p.max;
            float sx = ox + p.x * T, sy = oy + p.y * T;
            if (p.text != null) {
                ui.label(c, p.text, sx, sy, 15, Neon.alpha(Neon.hue(p.hue, 1f), l), Paint.Align.CENTER, 6);
            } else {
                fill.setColor(Neon.alpha(Neon.hue(p.hue, 1f), Math.min(1f, l + 0.2f)));
                c.drawCircle(sx, sy, (1.5f + 2.5f * l) * dp, fill);
            }
        }

        if (fog) {
            float rad = (r + 0.6f) * T;
            if (rad != fogKey) {
                fogP.setShader(new RadialGradient(0, 0, rad,
                        new int[]{0x00000000, 0x00000000, 0x99050208, 0xD0050208},
                        new float[]{0f, 0.55f, 0.92f, 1f}, Shader.TileMode.CLAMP));
                fogKey = rad;
            }
            fogM.setTranslate(mx, my);
            fogP.getShader().setLocalMatrix(fogM);
            c.drawRect(view.left - 60 * dp, view.top - 60 * dp, view.right + 60 * dp, view.bottom + 60 * dp, fogP);
        }
        c.restore();
    }

    private void star(Canvas c, int x, int y, float L, float Tp, float t) {
        int hsh = ((x * 73856093) ^ (y * 19349663)) & 0x7fffffff;
        if (hsh % 19 != 0) return;
        float tw = ((float) Math.sin(t * 2 + (hsh % 100)) + 1) / 2;
        float sx = L + ((hsh >> 8) % 100) / 100f * T, sy = Tp + ((hsh >> 16) % 100) / 100f * T;
        ui.fill.setColor(Neon.alpha(0xFFFFFFFF, 0.1f + tw * 0.5f));
        c.drawCircle(sx, sy, (0.6f + tw * 1.2f) * dp, ui.fill);
    }

    private void portal(Canvas c, float cx, float cy, float t, float a) {
        Paint st = ui.stroke;
        st.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 3; i++) {
            float rr = T * (0.42f - i * 0.11f);
            int col = Neon.alpha(Neon.hue(t * 0.8f + 0.5f + i * 0.15f, 1f), a);
            st.setStrokeWidth((3.2f - i * 0.6f) * dp);
            st.setColor(col);
            if (a > 0.5f) st.setShadowLayer(8 * dp, 0, 0, col);
            tmp.set(cx - rr, cy - rr, cx + rr, cy + rr);
            float rot = t * (i % 2 == 0 ? 220 : -300) + i * 60;
            c.drawArc(tmp, rot, 250, false, st);
        }
        st.clearShadowLayer();
        ui.fill.setColor(Neon.alpha(0xFFFFFFFF, a * (0.5f + 0.4f * (float) Math.sin(t * 6))));
        c.drawCircle(cx, cy, T * 0.08f, ui.fill);
    }

    private void gem(Canvas c, float cx, float cy, float t, int x, float a, boolean lit) {
        float sx = 0.25f + 0.75f * Math.abs((float) Math.cos(t * 3 + x));
        float r = T * 0.26f;
        path.reset();
        path.moveTo(cx, cy - r);
        path.lineTo(cx + r * 0.8f * sx, cy);
        path.lineTo(cx, cy + r);
        path.lineTo(cx - r * 0.8f * sx, cy);
        path.close();
        if (lit) {
            int col = Neon.alpha(Neon.hue(t * 1.5f + x * 0.1f, 1f), a);
            ui.fill.setColor(col);
            ui.fill.setShadowLayer(10 * dp, 0, 0, col);
            c.drawPath(path, ui.fill);
            ui.fill.clearShadowLayer();
        } else {
            ui.stroke.setStrokeWidth(1.2f * dp);
            ui.stroke.setColor(Neon.alpha(0xFFFFFFFF, a));
            c.drawPath(path, ui.stroke);
        }
    }

    private void power(Canvas c, float cx, float cy, float t) {
        int col = ((int) (t * 6) % 2 == 0) ? 0xFFFFE14D : 0xFFFFFFFF;
        Path p = Avatar.star(cx, cy, T * (0.3f + 0.05f * (float) Math.sin(t * 8)), T * 0.1f, 4, t);
        ui.fill.setColor(col);
        ui.fill.setShadowLayer(12 * dp, 0, 0, 0xFFFFE14D);
        c.drawPath(p, ui.fill);
        ui.fill.clearShadowLayer();
    }

    private void drawHud(Canvas c, float t) {
        ui.panel(c, hud, t, 0.92f);
        // equalizer behind the stats
        c.save();
        c.clipRect(hud);
        int bars = 36;
        float bw = hud.width() / bars, amp = 0.25f + 0.75f * energy;
        for (int i = 0; i < bars; i++) {
            float vv = ((float) Math.sin(i * 0.31f + t * 5) + (float) Math.sin(i * 0.13f - t * 2.7f) + 2) / 4 * amp;
            int col = flash > 0 ? 0xFFFF2040 : Neon.hue(i / (float) bars + t * 0.15f, 1f);
            ui.fill.setColor(Neon.alpha(col, 0.16f));
            c.drawRect(hud.left + i * bw + 1, hud.bottom - vv * hud.height(), hud.left + (i + 1) * bw - 1, hud.bottom, ui.fill);
        }
        c.restore();

        float x = hud.left + 14 * dp, y = hud.top + 24 * dp, lh = 20 * dp;
        Paint.Align L = Paint.Align.LEFT;
        String head = (multi ? "RACE " : "LVL ") + level + " · " + DIFF_NAMES[diff];
        ui.label(c, head, x, y, 15, Neon.hue(t * 0.2f, 1f), L, 6);
        ui.label(c, "TIME  " + Neon.time(elapsed()), x, y + lh, 13, 0xFFFFFFFF, L, 0);
        int par = mz.par[multi ? s.slot(s.myId) : 0];
        ui.label(c, "MOVES " + moves + " / par " + par, x, y + lh * 2, 13, 0xFFFFFFFF, L, 0);
        ui.label(c, String.format(Locale.US, "SCORE %07d", displayScore), x, y + lh * 3, 13, 0xFFFFE14D, L, 5);

        float barX = x + 64 * dp, barW = mini.left - 12 * dp - barX;
        float gy = y + lh * 3 + 18 * dp;
        ui.label(c, "◆ " + gems + "/" + mz.totalGems, x, gy, 12, Neon.hue(t, 1f), L, 4);
        ui.bar(c, barX, gy - 9 * dp, barW, 9 * dp, gems / (float) Math.max(1, mz.totalGems), t);
        float ty = gy + 17 * dp;
        boolean boosted = t < boostUntil;
        ui.label(c, boosted ? String.format(Locale.US, "✦ %.0fs", boostUntil - t) : "TORCH", x, ty, 12,
                boosted ? 0xFFFFE14D : 0xFFAAAAAA, L, boosted ? 6 : 0);
        ui.bar(c, barX, ty - 9 * dp, barW, 9 * dp, radius() / 12f, t);

        // compass to the exit
        float ccx = mini.left - 30 * dp, ccy = hud.top + 34 * dp;
        float ang = (float) Math.atan2(mz.exitY - py, mz.exitX - px);
        int col = Neon.hue(t * 0.8f + 0.5f, 1f);
        c.save();
        c.rotate((float) Math.toDegrees(ang), ccx, ccy);
        path.reset();
        path.moveTo(ccx + 14 * dp, ccy);
        path.lineTo(ccx - 9 * dp, ccy - 9 * dp);
        path.lineTo(ccx - 4 * dp, ccy);
        path.lineTo(ccx - 9 * dp, ccy + 9 * dp);
        path.close();
        ui.fill.setColor(col);
        ui.fill.setShadowLayer(8 * dp, 0, 0, col);
        c.drawPath(path, ui.fill);
        ui.fill.clearShadowLayer();
        c.restore();
        int dist = mz.distExit[py * mz.W + px];
        ui.label(c, dist + "", ccx, ccy + 30 * dp, 12, 0xFFFFFFFF, Paint.Align.CENTER, 0);
        ui.label(c, "EXIT", ccx, ccy + 43 * dp, 9, 0xFF888888, Paint.Align.CENTER, 0);

        drawMini(c, t);
    }

    private void drawMini(Canvas c, float t) {
        ui.fill.setShader(null);
        ui.fill.setColor(0xCC000000);
        c.drawRoundRect(mini, 8 * dp, 8 * dp, ui.fill);
        float sc = Math.min(mini.width() / mz.W, mini.height() / mz.H);
        float ox = mini.centerX() - mz.W * sc / 2, oy = mini.centerY() - mz.H * sc / 2;
        int ver = fog ? seenVersion : -2;
        if (ver != miniVersion) {
            miniVersion = ver;
            if (miniPts.length < mz.W * mz.H * 2) miniPts = new float[mz.W * mz.H * 2];
            int n = 0;
            for (int k = 0; k < mz.g.length; k++) {
                if (mz.g[k] == 1 && (!fog || seen[k])) {
                    miniPts[n++] = ox + (k % mz.W) * sc + sc / 2;
                    miniPts[n++] = oy + (k / mz.W) * sc + sc / 2;
                }
            }
            miniCount = n;
        }
        float key = mini.left * 31 + mini.top;
        if (key != miniKey) {
            miniKey = key;
            int[] cols = {Neon.hue(0.55f, 1f), Neon.hue(0.8f, 1f), Neon.hue(0.95f, 1f), Neon.hue(0.12f, 1f)};
            miniP.setShader(new LinearGradient(mini.left, mini.top, mini.right, mini.bottom, cols, null, Shader.TileMode.CLAMP));
            miniP.setStrokeCap(Paint.Cap.SQUARE);
        }
        miniP.setStrokeWidth(Math.max(1f, sc));
        miniP.setAlpha(200);
        c.drawPoints(miniPts, 0, miniCount, miniP);
        if (!fog || seen[mz.exitY * mz.W + mz.exitX]) {
            ui.fill.setColor(Neon.hue(t, 1f));
            c.drawCircle(ox + mz.exitX * sc + sc / 2, oy + mz.exitY * sc + sc / 2, Math.max(2.5f * dp, sc), ui.fill);
        }
        if (multi) {
            for (Session.Player p : s.players.values()) {
                if (p.id == s.myId) continue;
                ui.fill.setColor(Neon.avatarColor(p.hue, t));
                c.drawCircle(ox + p.x * sc + sc / 2, oy + p.y * sc + sc / 2, Math.max(2.2f * dp, sc), ui.fill);
            }
        }
        if ((int) (t * 4) % 2 == 0) {
            ui.fill.setColor(0xFFFFFFFF);
            c.drawCircle(ox + px * sc + sc / 2, oy + py * sc + sc / 2, Math.max(2.5f * dp, sc * 1.2f), ui.fill);
        }
    }

    private void drawRace(Canvas c, float t) {
        ui.panel(c, race, t + 1.5f, 0.85f);
        float x0 = race.left + 56 * dp, x1 = race.right - 34 * dp, cy = race.centerY();
        ui.label(c, "RACE", race.left + 12 * dp, cy + 5 * dp, 12, 0xFFAAAAAA, Paint.Align.LEFT, 0);
        ui.stroke.setStrokeWidth(2 * dp);
        ui.stroke.setColor(0x55FFFFFF);
        c.drawLine(x0, cy, x1, cy, ui.stroke);
        ui.label(c, "🏁", x1 + 16 * dp, cy + 7 * dp, 18, 0xFFFFFFFF, Paint.Align.CENTER, 0);
        int lane = 0;
        for (Session.Player p : s.players.values()) {
            int sl = s.slot(p.id);
            int k = p.y * mz.W + p.x;
            float prog = 0;
            if (k >= 0 && k < mz.distExit.length && mz.par[sl] > 0) {
                prog = 1f - mz.distExit[k] / (float) mz.par[sl];
            }
            prog = Neon.clamp(prog, 0f, 1f);
            float ax = x0 + (x1 - x0) * prog;
            float ay = cy + (lane % 2 == 0 ? -1 : 1) * (lane / 2 + 0.5f) * 7 * dp;
            Avatar.draw(c, ui.misc, p.avatar, p.hue, ax, ay, 26 * dp, t, 0, p.id == s.myId ? 1f : 0.85f);
            lane++;
        }
    }

    private void drawBottom(Canvas c, float t, int w) {
        float cy = bottomY + 20 * dp;
        pauseB.visible = phase == PLAY || phase == COUNTDOWN;
        pauseB.at(w - 58 * dp, bottomY + 2 * dp, w - 10 * dp, bottomY + 38 * dp);
        float maxX = w - 66 * dp;
        c.save();
        c.clipRect(0, bottomY, maxX, bottomY + 40 * dp);
        if (t < msgUntil) {
            int col = flash > 0 ? 0xFFFF3050 : Neon.hue(t * 2, 1f);
            ui.label(c, msg, maxX / 2, cy + 6 * dp, 16, col, Paint.Align.CENTER, 10);
        } else {
            Paint p = ui.text;
            p.setTextAlign(Paint.Align.LEFT);
            p.setTextSize(13 * dp);
            float cw = p.measureText("M");
            float total = cw * MARQUEE.length();
            float off = (t * 70 * dp) % total;
            float x = -off;
            int i = 0;
            while (x < maxX) {
                char ch = MARQUEE.charAt(i % MARQUEE.length());
                if (x > -cw && ch != ' ') {
                    p.setColor(Neon.hue(x / w * 0.6f - t * 0.3f, 0.85f));
                    c.drawText(String.valueOf(ch), x, cy + 5 * dp, p);
                }
                x += cw;
                i++;
            }
            p.setTextAlign(Paint.Align.CENTER);
        }
        c.restore();
        ui.button(c, pauseB, t);
    }

    private void drawJoystick(Canvas c, float t) {
        if (jId < 0) return;
        ui.stroke.setStrokeWidth(2 * dp);
        ui.stroke.setColor(Neon.alpha(Neon.hue(t * 0.3f, 1f), 0.35f));
        c.drawCircle(jx0, jy0, 64 * dp, ui.stroke);
        ui.fill.setColor(Neon.alpha(Neon.hue(t * 0.3f, 1f), 0.3f));
        c.drawCircle(jx, jy, 22 * dp, ui.fill);
    }

    private void dim(Canvas c, int w, int h, float a) {
        ui.fill.setShader(null);
        ui.fill.setColor(Neon.alpha(0xFF000000, a));
        c.drawRect(0, 0, w, h, ui.fill);
    }

    private void drawCountdown(Canvas c, float t, int w) {
        float cy = view.centerY();
        if (phase == PLAY) {
            float a = 1f - (t - phaseAt) / 0.7f;
            ui.label(c, "GO!", w / 2f, cy, 72 + (1 - a) * 40, Neon.alpha(Neon.hue(t, 1f), a), Paint.Align.CENTER, 20);
            return;
        }
        float dur = multi ? 3f : 1f, rem = dur - (t - phaseAt);
        if (multi) {
            String n = String.valueOf((int) Math.ceil(rem));
            float frac = rem - (float) Math.floor(rem);
            ui.label(c, n, w / 2f, cy + 30 * dp, 90 + frac * 50, Neon.hue(rem * 0.3f, 1f), Paint.Align.CENTER, 24);
            ui.label(c, "spawn: " + CORNERS[s.slot(s.myId)], w / 2f, cy + 90 * dp, 15, 0xFFFFFFFF, Paint.Align.CENTER, 6);
            ui.label(c, "first to the portal wins", w / 2f, cy + 114 * dp, 13, 0xFFAAAAAA, Paint.Align.CENTER, 0);
        } else {
            ui.rainbow(c, "GET READY", w / 2f, cy, 34, t, 4);
        }
    }

    private void drawPause(Canvas c, float t, int w, int h) {
        dim(c, w, h, 0.55f);
        float bw = w * 0.72f, bh = 56 * dp, gap = 14 * dp;
        int n = multi ? 5 : 6;
        float total = n * bh + (n - 1) * gap + 110 * dp;
        tmp.set((w - bw) / 2 - 18 * dp, h / 2f - total / 2, (w + bw) / 2 + 18 * dp, h / 2f + total / 2);
        ui.panel(c, tmp, t, 0.95f);
        ui.rainbow(c, "P A U S E D", w / 2f, tmp.top + 56 * dp, 26, t, 3);
        float y = tmp.top + 84 * dp;
        fogB.label = fog ? "FOG: ON" : "FOG: OFF (cheater)";
        musicB.label = "♫ MUSIC: " + (v.prefs.music ? "ON" : "OFF");
        sfxB.label = "♪ SOUND FX: " + (v.prefs.sfx ? "ON" : "OFF");
        Ui.Btn[] list = multi ? new Ui.Btn[]{resumeB, musicB, sfxB, fogB, quitB}
                : new Ui.Btn[]{resumeB, musicB, sfxB, fogB, rerollB, quitB};
        for (Ui.Btn b : list) {
            b.visible = true;
            b.at((w - bw) / 2, y, (w + bw) / 2, y + bh);
            ui.button(c, b, t);
            y += bh + gap;
        }
        if (multi) ui.label(c, "the race keeps running!", w / 2f, tmp.bottom + 24 * dp, 12, 0xFFFF8080, Paint.Align.CENTER, 0);
    }

    private void drawClear(Canvas c, float t, int w, int h) {
        dim(c, w, h, Math.min(0.6f, (t - phaseAt) * 1.5f));
        float age = t - phaseAt;
        ui.rainbow(c, "CLEAR!", w / 2f, h * 0.22f, 64, t, 8);
        String[][] lines = {
                {"TIME", Neon.time(clearTime)},
                {"MOVES", moves + " / par " + mz.par[0]},
                {"GEMS", gems + "/" + mz.totalGems},
                {"TIME BONUS", "+" + timeBonus},
                {"PATH BONUS", "+" + pathBonus},
                {"ALL GEMS", gemBonus > 0 ? "+" + gemBonus : "—"},
                {"SCORE", null},
        };
        int revealed = (int) (age / 0.28f);
        float pw = w * 0.84f, lh = 30 * dp;
        tmp.set((w - pw) / 2, h * 0.28f, (w + pw) / 2, h * 0.28f + lines.length * lh + 40 * dp);
        ui.panel(c, tmp, t, 0.95f);
        ui.label(c, " LEVEL " + level + " DONE ", w / 2f, tmp.top - 8 * dp, 13, 0xFFFFFFFF, Paint.Align.CENTER, 6);
        for (int i = 0; i < lines.length && i < revealed; i++) {
            float y = tmp.top + 36 * dp + i * lh;
            ui.label(c, lines[i][0], tmp.left + 18 * dp, y, 14, 0xFF9999AA, Paint.Align.LEFT, 0);
            boolean sc = lines[i][1] == null;
            String val = sc ? String.format(Locale.US, "%07d", displayScore) : lines[i][1];
            ui.label(c, val, tmp.right - 18 * dp, y, sc ? 18 : 15, sc ? Neon.hue(t * 1.5f, 1f) : 0xFFFFFFFF,
                    Paint.Align.RIGHT, sc ? 8 : 0);
        }
        if (revealed > lines.length + 1) {
            float bw = w * 0.38f, bh = 56 * dp, y = tmp.bottom + 26 * dp;
            nextB.visible = menuB.visible = true;
            nextB.at(w / 2f - bw - 6 * dp, y, w / 2f - 6 * dp, y + bh);
            menuB.at(w / 2f + 6 * dp, y, w / 2f + bw + 6 * dp, y + bh);
            ui.button(c, nextB, t);
            ui.button(c, menuB, t);
            if (score >= v.prefs.best && score > 0) {
                ui.rainbow(c, "★ NEW BEST ★", w / 2f, y + bh + 40 * dp, 18, t, 2);
            }
        }
    }

    private void drawFinished(Canvas c, float t, int w) {
        float cy = view.centerY();
        ui.rainbow(c, "YOU MADE IT!", w / 2f, cy - 10 * dp, 32, t, 4);
        ui.label(c, "photo finish" + "...".substring(0, 1 + (int) (t * 3) % 3), w / 2f, cy + 30 * dp, 15,
                0xFFCCCCCC, Paint.Align.CENTER, 0);
    }

    private void drawResult(Canvas c, float t, int w, int h) {
        dim(c, w, h, Math.min(0.7f, (t - phaseAt) * 1.5f));
        Session.Player win = s.players.get(winnerId);
        String wname = win != null ? win.name : "???";
        boolean me = winnerId == s.myId;
        ui.label(c, "🏆", w / 2f, h * 0.17f, 56, 0xFFFFFFFF, Paint.Align.CENTER, 0);
        ui.rainbow(c, me ? "YOU WIN!" : "WINNER", w / 2f, h * 0.25f, 44, t, 6);

        float acx = w / 2f, acy = h * 0.36f, size = 110 * dp;
        int col = win != null ? Neon.avatarColor(win.hue, t) : 0xFFFFFFFF;
        ui.stroke.setStrokeWidth(4 * dp);
        for (int i = 0; i < 3; i++) {
            ui.stroke.setColor(Neon.alpha(Neon.hue(t * 0.5f + i * 0.33f, 1f), 0.8f));
            tmp.set(acx - size * 0.7f - i * 8 * dp, acy - size * 0.7f - i * 8 * dp,
                    acx + size * 0.7f + i * 8 * dp, acy + size * 0.7f + i * 8 * dp);
            c.drawArc(tmp, t * (120 + i * 70) * (i % 2 == 0 ? 1 : -1), 220, false, ui.stroke);
        }
        if (win != null) Avatar.draw(c, ui.misc, win.avatar, win.hue, acx, acy, size, t, 0, 1f);
        ui.label(c, wname, w / 2f, acy + size * 0.95f, 24, col, Paint.Align.CENTER, 10);
        ui.label(c, "reached the portal in " + Neon.time(winnerTime), w / 2f, acy + size * 0.95f + 26 * dp, 13,
                0xFFCCCCCC, Paint.Align.CENTER, 0);

        float y = acy + size * 0.95f + 64 * dp;
        for (Session.Player p : s.players.values()) {
            Avatar.draw(c, ui.misc, p.avatar, p.hue, w * 0.22f, y - 6 * dp, 26 * dp, t, 0, 1f);
            ui.label(c, p.name + (p.id == s.myId ? " (you)" : ""), w * 0.3f, y, 14,
                    p.id == winnerId ? 0xFFFFE14D : 0xFFFFFFFF, Paint.Align.LEFT, 0);
            ui.label(c, "◆ " + (p.score / 100), w * 0.86f, y, 14, Neon.hue(t, 1f), Paint.Align.RIGHT, 0);
            y += 32 * dp;
        }

        float bw = w * 0.38f, bh = 56 * dp, by = h - v.safeBottom - 90 * dp;
        if (s.host) {
            rematchB.visible = leaveB.visible = true;
            rematchB.at(w / 2f - bw - 6 * dp, by, w / 2f - 6 * dp, by + bh);
            leaveB.at(w / 2f + 6 * dp, by, w / 2f + bw + 6 * dp, by + bh);
            ui.button(c, rematchB, t);
            ui.button(c, leaveB, t);
        } else {
            leaveB.visible = true;
            leaveB.at((w - bw) / 2, by, (w + bw) / 2, by + bh);
            ui.button(c, leaveB, t);
            ui.label(c, "waiting for host to start a rematch" + "...".substring(0, 1 + (int) (t * 3) % 3),
                    w / 2f, by - 18 * dp, 12, 0xFFAAAAAA, Paint.Align.CENTER, 0);
        }
    }

    private void drawHostGone(Canvas c, float t, int w, int h) {
        dim(c, w, h, 0.7f);
        ui.rainbow(c, "HOST LEFT", w / 2f, h * 0.4f, 40, t, 4);
        ui.label(c, "the race is over", w / 2f, h * 0.4f + 36 * dp, 14, 0xFFAAAAAA, Paint.Align.CENTER, 0);
        float bw = w * 0.5f, bh = 56 * dp, y = h * 0.4f + 70 * dp;
        menuB.visible = true;
        menuB.at((w - bw) / 2, y, (w + bw) / 2, y + bh);
        ui.button(c, menuB, t);
    }
}
