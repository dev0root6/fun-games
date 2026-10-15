package com.neonimposter;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A game room: the lobby plus every phase of a round. The host runs {@link Room}
 * (the rules) locally; everyone, host included, draws from their own {@link Board}.
 */
final class RoomScreen extends Screen {
    private static final String[] SET_NAMES = {"CATEGORY", "IMPOSTERS", "CLUE ROUNDS", "DISCUSSION", "IMPOSTER HINT"};

    private final Net net;
    private final boolean host;
    private final Room room;
    private final Board board = new Board();
    private final Random rnd = new Random();

    private boolean hostGone;
    private float lastBack = -10, dt, phaseShownAt;
    private int lastTickSec = -1;
    private boolean ejectSfx, verdictSfx;

    // reveal card
    private final RectF card = new RectF();
    private boolean cardHeld;
    private float flip;

    // vote cards
    private final RectF[] voteR = new RectF[10];
    private final int[] voteId = new int[10];
    private int voteN;

    private static final class Spark {
        float x, y, vx, vy, life, max, hue;
    }

    private final ArrayList<Spark> sparks = new ArrayList<>();
    private float nextFirework;

    private final RectF tmp = new RectF(), feedR = new RectF();
    private final Ui.Btn readyB, clueB, chatB, skipDiscB, skipVoteB, guessB, startB, againB, lobbyB, leaveB, roleB;
    private final Ui.Btn[] setL = new Ui.Btn[5], setR = new Ui.Btn[5];

    /** net == null: host a new room. Otherwise we already joined as {@code myId}. */
    RoomScreen(GameView v, Net joined, int myId) {
        super(v);
        for (int i = 0; i < voteR.length; i++) voteR[i] = new RectF();
        readyB = add(new Ui.Btn("I'M READY", 0.35f));
        clueB = add(new Ui.Btn("✎  GIVE YOUR CLUE", 0.15f));
        chatB = add(new Ui.Btn("💬  SAY SOMETHING", 0.55f));
        skipDiscB = add(new Ui.Btn("VOTE NOW ▶", 0.95f));
        skipVoteB = add(new Ui.Btn("SKIP VOTE", 0.6f));
        guessB = add(new Ui.Btn("✎  GUESS THE WORD", 0f));
        startB = add(new Ui.Btn("▶  START GAME", 0.35f));
        againB = add(new Ui.Btn("▶  PLAY AGAIN", 0.35f));
        lobbyB = add(new Ui.Btn("⚙  LOBBY", 0.6f));
        leaveB = add(new Ui.Btn("LEAVE", 0.9f));
        roleB = add(new Ui.Btn("👁 ROLE", 0.95f));
        for (int i = 0; i < 5; i++) {
            setL[i] = add(new Ui.Btn("◀", 0.5f));
            setR[i] = add(new Ui.Btn("▶", 0.5f));
        }
        board.events = this::onEvent;

        if (joined == null) {
            host = true;
            net = Net.host(v.act);
            board.myId = 0;
            room = new Room((to, line) -> {
                long now = System.currentTimeMillis();
                if (to == -1) {
                    net.broadcast(line, -1);
                    board.apply(line, now);
                } else if (to == 0) {
                    board.apply(line, now);
                } else {
                    net.sendTo(to, line);
                }
            }, rnd.nextLong());
            Prefs p = v.prefs;
            room.join(0, p.name, p.avatar, p.hue);
        } else {
            host = false;
            net = joined;
            room = null;
            board.myId = myId;
        }
    }

    // ── plumbing ──────────────────────────────────────────────────────────

    private void act(String line) {
        if (host) room.handle(0, Wire.split(line), System.currentTimeMillis());
        else net.send(line);
    }

    private void onEvent(String ev, int arg) {
        Audio a = v.audio;
        switch (ev) {
            case "PHASE":
                phaseShownAt = v.t;
                ejectSfx = verdictSfx = false;
                lastTickSec = -1;
                switch (board.phase) {
                    case Room.LOBBY:
                    case Room.OVER:
                        a.song(Audio.MENU);
                        break;
                    case Room.REVEAL:
                        a.song(Audio.SUS);
                        a.sfx(Audio.REVEAL);
                        flip = 0;
                        cardHeld = false;
                        break;
                    case Room.VOTE:
                    case Room.TALLY:
                        a.song(Audio.VOTE);
                        break;
                    default:
                        a.song(Audio.SUS);
                        break;
                }
                if (board.phase.equals(Room.CLUE) && board.turnId == board.myId) myTurn();
                break;
            case "TURN":
                if (board.turnId == board.myId) myTurn();
                break;
            case "CLUE":
                a.sfx(Audio.CLUE);
                break;
            case "CHAT":
                a.sfx(Audio.CHAT);
                break;
            case "VOTED":
                a.sfx(Audio.VOTED);
                break;
            case "ERR":
                a.sfx(Audio.ERR);
                v.haptic(HapticFeedbackConstants.LONG_PRESS);
                break;
            case "OVER":
                a.sfx(arg == 1 ? Audio.WIN_IMP : Audio.WIN_CREW);
                nextFirework = v.t;
                break;
            default:
                break;
        }
    }

    private void myTurn() {
        v.audio.sfx(Audio.TURN);
        v.haptic(HapticFeedbackConstants.LONG_PRESS);
    }

    @Override
    void enter() {
        v.audio.song(Audio.MENU);
    }

    @Override
    void leave() {
        net.close();
    }

    @Override
    boolean back() {
        if (hostGone || board.phase.equals(Room.LOBBY) || v.t - lastBack < 2f) {
            v.setScreen(new TitleScreen(v));
        } else {
            lastBack = v.t;
            board.toast(host ? "press back again to END the game for everyone" : "press back again to leave",
                    System.currentTimeMillis(), 2000);
        }
        return true;
    }

    @Override
    void update(float dt) {
        this.dt = dt;
        long now = System.currentTimeMillis();
        Net.Msg m;
        while ((m = net.inbox.poll()) != null) {
            String[] f = m.f;
            if (host) {
                switch (f[0]) {
                    case "HELLO":
                        if (!room.inGame() && room.ps.size() < Net.MAX_PLAYERS) {
                            net.sendTo(m.from, "WELCOME|" + m.from);
                            room.join(m.from, Wire.s(f, 1), Wire.s(f, 2), Wire.i(f, 3));
                            v.audio.sfx(Audio.JOIN);
                        } else {
                            net.sendTo(m.from, "FULL");
                            net.kick(m.from);
                        }
                        break;
                    case "LEFT":
                        room.leave(m.from);
                        break;
                    case "ERR":
                        board.toast(f.length > 1 ? f[1] : "network error", now, 4000);
                        break;
                    default:
                        room.handle(m.from, f, now);
                        break;
                }
            } else if (f[0].equals("HOSTGONE")) {
                hostGone = true;
            } else if (!f[0].equals("CONNECTED") && !f[0].equals("WELCOME")) {
                board.apply(TextUtils.join("|", f), now);
            }
        }
        if (host) {
            room.tick(now);
            net.accepting = !room.inGame() && room.ps.size() < Net.MAX_PLAYERS;
            net.beacon = "NEONIMP|1|" + Wire.enc(v.prefs.name) + "|" + room.ps.size() + "|"
                    + (room.inGame() ? "PLAYING" : "LOBBY") + "|" + (net.accepting ? "1" : "0");
        }

        // countdown ticks for the last five seconds
        String ph = board.phase;
        if (board.phaseSecs > 0 && !ph.equals(Room.TALLY)) {
            int sec = (int) Math.ceil(board.remainingMs(now) / 1000.0);
            if (sec <= 5 && sec > 0 && sec != lastTickSec) v.audio.sfx(Audio.TICK);
            lastTickSec = sec;
        }
        if (ph.equals(Room.TALLY)) {
            float age = v.t - phaseShownAt;
            if (age > 2.5f && !ejectSfx) {
                ejectSfx = true;
                if (board.ejected >= 0) v.audio.sfx(Audio.EJECT);
            }
            if (age > 4.8f && !verdictSfx) {
                verdictSfx = true;
                if (board.ejected >= 0) v.audio.sfx(board.ejectedWasImp ? Audio.STING : Audio.SAFE);
            }
        }

        flip += ((cardHeld ? 1f : 0f) - flip) * Math.min(1f, dt * 12f);

        for (int i = sparks.size() - 1; i >= 0; i--) {
            Spark s = sparks.get(i);
            s.life -= dt;
            if (s.life <= 0) {
                sparks.remove(i);
                continue;
            }
            s.vy += 220 * dp * dt;
            s.vx *= 0.985f;
            s.x += s.vx * dt;
            s.y += s.vy * dt;
        }
    }

    @Override
    void click(Ui.Btn b) {
        long now = System.currentTimeMillis();
        if (b == readyB) {
            act("READY");
        } else if (b == clueB) {
            String title = board.amImp ? "Your clue — blend in!" : "Clue for \"" + board.myWord + "\"";
            v.act.askText(title, "one word or a short phrase", "", Room.CLUE_MAX, false,
                    s -> act("CLUE|" + Wire.enc(s)));
        } else if (b == chatB) {
            v.act.askText("Say something", "accuse, defend, bluff…", "", 60, false, s -> act("CHAT|" + Wire.enc(s)));
        } else if (b == skipDiscB) {
            act("SKIPDISCUSS");
        } else if (b == skipVoteB) {
            board.myVote = -1;
            act("VOTE|-1");
        } else if (b == guessB) {
            String hint = board.myCat.isEmpty() ? "what was everyone describing?" : "category: " + board.myCat;
            v.act.askText("Guess the secret word", hint, "", 40, false, s -> act("GUESS|" + Wire.enc(s)));
        } else if (b == startB || b == againB) {
            if (host && !room.start(now)) board.toast("need at least " + Room.MIN_PLAYERS + " players", now, 2500);
        } else if (b == lobbyB) {
            if (host) room.toLobby(null);
        } else if (b == leaveB) {
            v.setScreen(new TitleScreen(v));
        } else if (host) {
            for (int i = 0; i < 5; i++) {
                if (b == setL[i]) room.setting(i, -1);
                if (b == setR[i]) room.setting(i, 1);
            }
        }
    }

    @Override
    void raw(MotionEvent e) {
        int a = e.getActionMasked();
        float x = e.getX(e.getActionIndex()), y = e.getY(e.getActionIndex());
        if (a == MotionEvent.ACTION_DOWN && board.phase.equals(Room.REVEAL) && card.contains(x, y)) {
            cardHeld = true;
            v.tick();
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            cardHeld = false;
        }
        if (a == MotionEvent.ACTION_UP && board.phase.equals(Room.VOTE) && !board.amOut()) {
            for (int i = 0; i < voteN; i++) {
                int id = voteId[i];
                Board.VP bp = board.players.get(id);
                if (voteR[i].contains(x, y) && bp != null && !bp.out && id != board.myId) {
                    board.myVote = id;
                    act("VOTE|" + id);
                    v.tick();
                }
            }
        }
    }

    // ── drawing ───────────────────────────────────────────────────────────

    @Override
    void draw(Canvas c) {
        float t = v.t;
        long now = System.currentTimeMillis();
        int w = c.getWidth(), h = c.getHeight();
        c.drawColor(0xFF07040D);
        Bg.warp(c, dt, 0.08f, dp, 0.3f);
        for (Ui.Btn b : btns) b.visible = false;
        voteN = 0;

        String ph = board.phase;
        if (ph.equals(Room.VOTE) || ph.equals(Room.TALLY)) {
            ui.stroke.setStrokeWidth(60 * dp);
            ui.stroke.setColor(Neon.alpha(0xFFFF1040, 0.08f + 0.06f * (float) Math.sin(t * 4)));
            c.drawRect(0, 0, w, h, ui.stroke);
        }

        switch (ph) {
            case Room.LOBBY:
                drawLobby(c, t, w, h);
                break;
            case Room.REVEAL:
                drawReveal(c, t, w, h, now);
                break;
            case Room.CLUE:
                drawClue(c, t, w, h, now);
                break;
            case Room.DISCUSS:
                drawDiscuss(c, t, w, h, now);
                break;
            case Room.VOTE:
                drawVote(c, t, w, h, now);
                break;
            case Room.TALLY:
                drawTally(c, t, w, h);
                break;
            case Room.GUESS:
                drawGuess(c, t, w, h, now);
                break;
            case Room.OVER:
                drawOver(c, t, w, h);
                break;
            default:
                break;
        }

        boolean inRound = !ph.equals(Room.LOBBY) && !ph.equals(Room.OVER);
        if (inRound && !ph.equals(Room.REVEAL)) {
            roleB.visible = true;
            roleB.at(12 * dp, v.safeTop + 6 * dp, 96 * dp, v.safeTop + 40 * dp);
            ui.button(c, roleB, t);
            if (roleB.pressed) {
                tmp.set(w * 0.08f, h * 0.3f, w * 0.92f, h * 0.3f + 300 * dp);
                ui.fill.setColor(0xCC000000);
                c.drawRect(0, 0, w, h, ui.fill);
                roleCard(c, tmp, t);
            }
        }

        for (Spark s : sparks) {
            float l = s.life / s.max;
            ui.fill.setColor(Neon.alpha(Neon.hue(s.hue, 1f), Math.min(1f, l + 0.2f)));
            c.drawCircle(s.x, s.y, (1f + 2.2f * l) * dp, ui.fill);
        }

        if (now < board.toastUntil) {
            float ty = h - v.safeBottom - 96 * dp;
            ui.text.setTextSize(14 * dp);
            float tw = ui.text.measureText(board.toast) + 32 * dp;
            tmp.set((w - tw) / 2, ty - 24 * dp, (w + tw) / 2, ty + 10 * dp);
            ui.fill.setColor(0xEE1A0A20);
            c.drawRoundRect(tmp, 17 * dp, 17 * dp, ui.fill);
            ui.label(c, board.toast, w / 2f, ty, 14, 0xFFFFE14D, Paint.Align.CENTER, 4);
        }

        if (hostGone) {
            ui.fill.setColor(0xDD000000);
            c.drawRect(0, 0, w, h, ui.fill);
            ui.rainbow(c, "HOST LEFT", w / 2f, h * 0.42f, 38, t, 4);
            ui.label(c, "the game is over", w / 2f, h * 0.42f + 34 * dp, 14, 0xFFAAAAAA, Paint.Align.CENTER, 0);
            for (Ui.Btn b : btns) b.visible = false;
            leaveB.visible = true;
            leaveB.label = "MENU";
            float bw = w * 0.5f, y = h * 0.42f + 70 * dp;
            leaveB.at((w - bw) / 2, y, (w + bw) / 2, y + 54 * dp);
            ui.button(c, leaveB, t);
        }
    }

    /** Title, optional subtitle and the phase timer. Returns the y where content can start. */
    private float header(Canvas c, String title, String sub, boolean timer, float t, int w, long now) {
        float top = v.safeTop + 6 * dp;
        ui.rainbow(c, title, w / 2f, top + 28 * dp, 22, t, 2);
        float y = top + 44 * dp;
        if (sub != null) {
            ui.label(c, sub, w / 2f, top + 52 * dp, 12, 0xFFBBBBCC, Paint.Align.CENTER, 0);
            y = top + 62 * dp;
        }
        if (timer && board.phaseSecs > 0) {
            long rem = board.remainingMs(now);
            float frac = rem / (board.phaseSecs * 1000f);
            int col = Neon.hue(0.33f * frac, 1f);
            float x0 = 18 * dp, x1 = w - 18 * dp;
            ui.fill.setColor(0x33FFFFFF);
            c.drawRoundRect(x0, y, x1, y + 7 * dp, 4 * dp, 4 * dp, ui.fill);
            ui.fill.setColor(col);
            ui.fill.setShadowLayer(8 * dp, 0, 0, col);
            c.drawRoundRect(x0, y, x0 + (x1 - x0) * frac, y + 7 * dp, 4 * dp, 4 * dp, ui.fill);
            ui.fill.clearShadowLayer();
            ui.label(c, (int) Math.ceil(rem / 1000.0) + "s", w - 18 * dp, top + 28 * dp, 16, col, Paint.Align.RIGHT, 6);
            y += 16 * dp;
        }
        return y;
    }

    private float bottomY(int h) {
        return h - v.safeBottom - 66 * dp;
    }

    private void bottomButtons(Canvas c, float t, int w, int h, Ui.Btn... bs) {
        float y = bottomY(h), pad = 16 * dp, gap = 10 * dp;
        float bw = (w - 2 * pad - gap * (bs.length - 1)) / bs.length;
        for (int i = 0; i < bs.length; i++) {
            Ui.Btn b = bs[i];
            b.visible = true;
            b.at(pad + i * (bw + gap), y, pad + i * (bw + gap) + bw, y + 54 * dp);
            ui.button(c, b, t);
        }
    }

    private String fit(String s, float sizeDp, float maxW) {
        ui.text.setTextSize(sizeDp * dp);
        if (ui.text.measureText(s) <= maxW) return s;
        while (s.length() > 1 && ui.text.measureText(s + "…") > maxW) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    private void feed(Canvas c, RectF r, float t, boolean cluesOnly) {
        ui.panel(c, r, t, 0.88f);
        c.save();
        c.clipRect(r.left + 3 * dp, r.top + 3 * dp, r.right - 3 * dp, r.bottom - 3 * dp);
        float lh = 34 * dp, y = r.bottom - 16 * dp;
        int shown = 0;
        for (int i = board.feed.size() - 1; i >= 0 && y > r.top + 12 * dp; i--) {
            Board.Line l = board.feed.get(i);
            if (cluesOnly && l.kind != 0) continue;
            shown++;
            if (l.kind == 2) {
                ui.label(c, fit(l.text, 12, r.width() - 30 * dp), r.centerX(), y, 12, 0xFFFFE14D, Paint.Align.CENTER, 0);
                y -= lh * 0.8f;
                continue;
            }
            Board.VP p = board.any(l.id);
            String name = p != null ? p.name : "?";
            int col = p != null ? Neon.avatarColor(p.hue, t) : 0xFFFFFFFF;
            if (p != null) Avatar.draw(c, ui.misc, p.avatar, p.hue, r.left + 24 * dp, y - 5 * dp, 24 * dp, t, 0, 1f);
            float x = r.left + 44 * dp;
            ui.text.setTextSize(12 * dp);
            String nm = fit(name, 12, 110 * dp);
            ui.label(c, nm, x, y, 12, col, Paint.Align.LEFT, 0);
            ui.text.setTextSize(12 * dp);
            x += ui.text.measureText(nm) + 10 * dp;
            float right = r.right - (l.kind == 0 ? 40 * dp : 14 * dp);
            if (l.kind == 0) {
                ui.label(c, fit(l.text, 16, right - x), x, y + 1 * dp, 16, 0xFFFFFFFF, Paint.Align.LEFT, 4);
                ui.label(c, "R" + l.round, r.right - 14 * dp, y, 10, 0xFF777788, Paint.Align.RIGHT, 0);
            } else {
                ui.label(c, fit("💬 " + l.text, 13, right - x), x, y, 13, 0xFFBBBBCC, Paint.Align.LEFT, 0);
            }
            y -= lh;
        }
        if (shown == 0) {
            ui.label(c, cluesOnly ? "no clues yet" : "nothing said yet", r.centerX(), r.centerY(), 13, 0xFF777788,
                    Paint.Align.CENTER, 0);
        }
        c.restore();
    }

    private void roleCard(Canvas c, RectF r, float t) {
        ui.panel(c, r, t, 0.97f);
        float cx = r.centerX();
        if (board.amImp) {
            ui.label(c, "YOU ARE THE", cx, r.top + 52 * dp, 14, 0xFFFFAAAA, Paint.Align.CENTER, 0);
            float jx = (rnd.nextFloat() - 0.5f) * 4 * dp, jy = (rnd.nextFloat() - 0.5f) * 3 * dp;
            float sz = Math.min(46, (r.width() - 30 * dp) / dp / (8 * 0.62f));
            ui.label(c, "IMPOSTER", cx + jx - 3 * dp, r.top + 108 * dp + jy, sz, 0x9900F0FF, Paint.Align.CENTER, 0);
            ui.label(c, "IMPOSTER", cx + jx, r.top + 108 * dp + jy, sz, 0xFFFF1F4F, Paint.Align.CENTER, 18);
            String hint = board.myCat.isEmpty() ? "no hint — listen closely" : "category: " + board.myCat;
            ui.label(c, hint, cx, r.top + 160 * dp, 18, 0xFFFFE14D, Paint.Align.CENTER, 6);
            ui.label(c, "blend in · fake your clues", cx, r.bottom - 50 * dp, 13, 0xFFBBBBCC, Paint.Align.CENTER, 0);
            ui.label(c, "figure out the secret word", cx, r.bottom - 30 * dp, 13, 0xFFBBBBCC, Paint.Align.CENTER, 0);
        } else {
            ui.label(c, "CATEGORY", cx, r.top + 40 * dp, 12, 0xFF9999AA, Paint.Align.CENTER, 0);
            ui.label(c, board.myCat, cx, r.top + 68 * dp, 20, Neon.hue(t * 0.2f + 0.5f, 1f), Paint.Align.CENTER, 8);
            ui.label(c, "SECRET WORD", cx, r.top + 112 * dp, 12, 0xFF9999AA, Paint.Align.CENTER, 0);
            String wd = board.myWord.toUpperCase();
            float sz = Math.min(44, (r.width() - 30 * dp) / dp / (Math.max(4, wd.length()) * 0.62f));
            ui.rainbow(c, wd, cx, r.top + 160 * dp, sz, t, 2);
            ui.label(c, "give clues — never say it", cx, r.bottom - 50 * dp, 13, 0xFFBBBBCC, Paint.Align.CENTER, 0);
            ui.label(c, "find the one who doesn't know", cx, r.bottom - 30 * dp, 13, 0xFFBBBBCC, Paint.Align.CENTER, 0);
        }
    }

    // ── phases ────────────────────────────────────────────────────────────

    private void drawLobby(Canvas c, float t, int w, int h) {
        long now = System.currentTimeMillis();
        Board.VP hostP = board.players.get(0);
        String sub = host ? "you're the host" : "waiting for " + (hostP != null ? hostP.name : "host") + " to start";
        float y = header(c, "LOBBY", sub, false, t, w, now);
        if (host) {
            List<String> ips = Net.localIps();
            String ipText = ips.isEmpty() ? "no network?" : "IP  " + TextUtils.join("  ·  ", ips);
            ui.label(c, ipText, w / 2f, y + 14 * dp, 15, Neon.hue(t * 0.3f, 1f), Paint.Align.CENTER, 8);
            y += 26 * dp;
        }
        float pad = 16 * dp, cellH = 46 * dp;
        tmp.set(pad, y, w - pad, y + 5 * cellH + 12 * dp);
        ui.panel(c, tmp, t, 0.9f);
        List<Board.VP> ps = new ArrayList<>(board.players.values());
        Collections.sort(ps, (a, b) -> a.id - b.id);
        float cw = (tmp.width() - 12 * dp) / 2;
        for (int i = 0; i < 10; i++) {
            float x = tmp.left + 6 * dp + (i % 2) * cw, yy = tmp.top + 6 * dp + (i / 2) * cellH;
            float cy = yy + cellH / 2;
            if (i < ps.size()) {
                Board.VP p = ps.get(i);
                Avatar.draw(c, ui.misc, p.avatar, p.hue, x + 22 * dp, cy, 30 * dp, t, 0, 1f);
                String tag = (p.id == 0 ? "★ " : "") + (p.id == board.myId ? "(you) " : "");
                ui.label(c, fit(p.name, 13, cw - 50 * dp), x + 42 * dp, cy, 13, Neon.avatarColor(p.hue, t),
                        Paint.Align.LEFT, 4);
                ui.label(c, tag + "score " + p.score, x + 42 * dp, cy + 15 * dp, 9, 0xFF9999AA, Paint.Align.LEFT, 0);
            } else {
                float a = 0.18f + 0.12f * (float) Math.sin(t * 3 + i);
                ui.stroke.setStrokeWidth(1.2f * dp);
                ui.stroke.setColor(Neon.alpha(0xFFFFFFFF, a));
                c.drawCircle(x + 22 * dp, cy, 11 * dp, ui.stroke);
                ui.label(c, "open", x + 42 * dp, cy + 4 * dp, 11, Neon.alpha(0xFFFFFFFF, a + 0.1f), Paint.Align.LEFT, 0);
            }
        }
        y = tmp.bottom + 12 * dp;

        float rowH = 40 * dp;
        tmp.set(pad, y, w - pad, y + 5 * rowH + 12 * dp);
        ui.panel(c, tmp, t + 2f, 0.9f);
        int n = board.players.size();
        String[] vals = {
                board.cat < 0 ? "RANDOM" : Words.CATEGORIES[board.cat],
                board.imps == 2 ? (n >= 6 ? "2" : "2 (needs 6+)") : "1",
                String.valueOf(board.rounds),
                Room.DISCUSS_OPTS[Math.max(0, Math.min(Room.DISCUSS_OPTS.length - 1, board.discussIdx))] + "s",
                board.hint ? "SEES CATEGORY" : "NO HINT",
        };
        for (int i = 0; i < 5; i++) {
            float yy = tmp.top + 6 * dp + i * rowH, cy = yy + rowH / 2;
            ui.label(c, SET_NAMES[i], tmp.left + 14 * dp, cy + 4 * dp, 11, 0xFF9999AA, Paint.Align.LEFT, 0);
            float vx = tmp.left + tmp.width() * 0.66f;
            ui.label(c, vals[i], vx, cy + 5 * dp, 14, Neon.hue(0.5f + i * 0.1f + t * 0.1f, 1f), Paint.Align.CENTER, 5);
            if (host) {
                float bs = 30 * dp;
                setL[i].visible = setR[i].visible = true;
                setL[i].at(vx - 92 * dp, cy - bs / 2, vx - 92 * dp + bs, cy + bs / 2);
                setR[i].at(vx + 92 * dp - bs, cy - bs / 2, vx + 92 * dp, cy + bs / 2);
                ui.button(c, setL[i], t);
                ui.button(c, setR[i], t);
            }
        }

        leaveB.label = host ? "CLOSE ROOM" : "LEAVE";
        if (host) {
            startB.enabled = n >= Room.MIN_PLAYERS;
            startB.label = n >= Room.MIN_PLAYERS ? "▶  START (" + n + ")" : "NEED 3+ (" + n + ")";
            bottomButtons(c, t, w, h, leaveB, startB);
        } else {
            ui.label(c, "waiting for the host" + "...".substring(0, 1 + (int) (t * 3) % 3), w / 2f,
                    bottomY(h) - 14 * dp, 13, 0xFFAAAAAA, Paint.Align.CENTER, 0);
            bottomButtons(c, t, w, h, leaveB);
        }
    }

    private void drawReveal(Canvas c, float t, int w, int h, long now) {
        float y = header(c, "YOUR SECRET", "hold the card — keep it hidden!", true, t, w, now);
        float ch = Math.min(320 * dp, h * 0.42f);
        card.set(w * 0.08f, y + 20 * dp, w * 0.92f, y + 20 * dp + ch);
        float scale = Math.max(0.02f, Math.abs((float) Math.cos(flip * Math.PI)));
        c.save();
        c.scale(scale, 1f, card.centerX(), card.centerY());
        if (flip < 0.5f) {
            ui.panel(c, card, t, 0.95f);
            float g = (int) (t * 7) % 9 == 0 ? (rnd.nextFloat() - 0.5f) * 10 * dp : 0;
            ui.label(c, "?", card.centerX() + g, card.centerY() + 30 * dp, 120, Neon.hue(t * 0.4f, 1f),
                    Paint.Align.CENTER, 24);
            float a = 0.6f + 0.4f * (float) Math.sin(t * 4);
            ui.label(c, "HOLD TO REVEAL", card.centerX(), card.bottom - 26 * dp, 15, Neon.alpha(0xFFFFFFFF, a),
                    Paint.Align.CENTER, 6);
        } else {
            roleCard(c, card, t);
        }
        c.restore();

        Board.VP me = board.me();
        boolean ready = me != null && me.ready;
        int nReady = 0;
        for (Board.VP p : board.players.values()) if (p.ready) nReady++;
        float ry = card.bottom + 40 * dp;
        ui.label(c, nReady + "/" + board.players.size() + " ready", w / 2f, ry, 14, 0xFFBBBBCC, Paint.Align.CENTER, 0);
        int n = board.players.size();
        float step = Math.min(36 * dp, (w - 40 * dp) / Math.max(1, n));
        float x = w / 2f - step * (n - 1) / 2f;
        for (Board.VP p : board.players.values()) {
            Avatar.draw(c, ui.misc, p.avatar, p.hue, x, ry + 32 * dp, 28 * dp, t, 0, p.ready ? 1f : 0.35f);
            if (p.ready) ui.label(c, "✓", x + 10 * dp, ry + 50 * dp, 14, 0xFF40FF90, Paint.Align.CENTER, 4);
            x += step;
        }
        readyB.enabled = !ready;
        readyB.label = ready ? "READY ✓" : "I'M READY";
        bottomButtons(c, t, w, h, readyB);
    }

    private void drawClue(Canvas c, float t, int w, int h, long now) {
        float y = header(c, "CLUES · ROUND " + board.round, null, true, t, w, now);
        float pad = 16 * dp;
        boolean mine = board.turnId == board.myId;
        tmp.set(pad, y + 6 * dp, w - pad, y + 102 * dp);
        ui.panel(c, tmp, t * (mine ? 3 : 1), 0.92f);
        Board.VP p = board.any(board.turnId);
        if (p != null) {
            float bounce = mine ? (float) Math.abs(Math.sin(t * 5)) * 4 * dp : 0;
            Avatar.draw(c, ui.misc, p.avatar, p.hue, tmp.left + 50 * dp, tmp.centerY() - bounce, 60 * dp, t, 0, 1f);
        }
        if (mine) {
            ui.rainbow(c, "YOUR TURN!", tmp.left + 92 * dp + 100 * dp, tmp.centerY() - 2 * dp, 24, t * 2, 3);
            ui.label(c, board.amImp ? "fake it. blend in." : "one short clue · don't say the word",
                    tmp.left + 192 * dp, tmp.centerY() + 24 * dp, 11, 0xFFBBBBCC, Paint.Align.CENTER, 0);
        } else {
            ui.label(c, fit(board.name(board.turnId), 20, tmp.width() - 110 * dp), tmp.left + 96 * dp,
                    tmp.centerY() - 2 * dp, 20, p != null ? Neon.avatarColor(p.hue, t) : 0xFFFFFFFF, Paint.Align.LEFT, 8);
            ui.label(c, "is thinking of a clue" + "...".substring(0, 1 + (int) (t * 3) % 3), tmp.left + 96 * dp,
                    tmp.centerY() + 22 * dp, 12, 0xFFBBBBCC, Paint.Align.LEFT, 0);
        }
        float fb = mine ? bottomY(h) - 12 * dp : h - v.safeBottom - 16 * dp;
        feedR.set(pad, tmp.bottom + 12 * dp, w - pad, fb);
        feed(c, feedR, t, true);
        if (mine) bottomButtons(c, t, w, h, clueB);
    }

    private void drawDiscuss(Canvas c, float t, int w, int h, long now) {
        float y = header(c, "DISCUSS!", "who is faking it?", true, t, w, now);
        float pad = 16 * dp;
        feedR.set(pad, y + 8 * dp, w - pad, bottomY(h) - 12 * dp);
        feed(c, feedR, t, false);
        if (host) bottomButtons(c, t, w, h, chatB, skipDiscB);
        else bottomButtons(c, t, w, h, chatB);
    }

    private void drawVote(Canvas c, float t, int w, int h, long now) {
        String sub = board.amOut() ? "you're out — spectating"
                : board.myVote == -2 ? "tap who you think is the imposter"
                : "vote cast — tap another to change it";
        float y = header(c, "VOTE", sub, true, t, w, now);
        float pad = 16 * dp, gap = 8 * dp;
        List<Board.VP> ps = new ArrayList<>(board.players.values());
        int rows = (ps.size() + 1) / 2;
        float avail = bottomY(h) - 30 * dp - y;
        float ch = Math.max(56 * dp, Math.min(96 * dp, (avail - gap * (rows - 1)) / Math.max(1, rows)));
        float cw = (w - 2 * pad - gap) / 2;
        int voted = 0, alive = 0;
        for (int i = 0; i < ps.size() && i < voteR.length; i++) {
            Board.VP p = ps.get(i);
            if (!p.out) {
                alive++;
                if (p.voted) voted++;
            }
            float x = pad + (i % 2) * (cw + gap), yy = y + 6 * dp + (i / 2) * (ch + gap);
            RectF r = voteR[i];
            r.set(x, yy, x + cw, yy + ch);
            voteId[i] = p.id;
            voteN = i + 1;
            boolean sel = board.myVote == p.id;
            ui.fill.setColor(p.out ? 0x22FFFFFF : sel ? 0x55FF1F4F : 0x33FFFFFF);
            c.drawRoundRect(r, 12 * dp, 12 * dp, ui.fill);
            if (sel) {
                ui.stroke.setStrokeWidth(3 * dp);
                ui.stroke.setColor(0xFFFF1F4F);
                ui.stroke.setShadowLayer(14 * dp, 0, 0, 0xFFFF1F4F);
                c.drawRoundRect(r, 12 * dp, 12 * dp, ui.stroke);
                ui.stroke.clearShadowLayer();
            }
            float al = p.out ? 0.3f : 1f;
            Avatar.draw(c, ui.misc, p.avatar, p.hue, r.left + 26 * dp, r.top + Math.min(ch / 2, 30 * dp), 34 * dp, t, 0, al);
            String nm = p.name + (p.id == board.myId ? " (you)" : "");
            ui.label(c, fit(nm, 14, cw - 60 * dp), r.left + 50 * dp, r.top + 24 * dp, 14,
                    Neon.alpha(Neon.avatarColor(p.hue, t), al), Paint.Align.LEFT, 0);
            String clues = p.out ? "EJECTED" : board.cluesOf(p.id);
            ui.label(c, fit(clues.isEmpty() ? "—" : clues, 11, cw - 60 * dp), r.left + 50 * dp, r.top + 42 * dp, 11,
                    p.out ? 0xFFFF6080 : 0xFFBBBBCC, Paint.Align.LEFT, 0);
            if (p.voted && !p.out) ui.label(c, "✓", r.right - 10 * dp, r.top + 20 * dp, 15, 0xFF40FF90, Paint.Align.RIGHT, 5);
            if (sel) ui.label(c, "YOUR VOTE", r.right - 10 * dp, r.bottom - 8 * dp, 10, 0xFFFF6080, Paint.Align.RIGHT, 4);
        }
        ui.label(c, voted + "/" + alive + " voted", w / 2f, bottomY(h) - 12 * dp, 13, 0xFFBBBBCC, Paint.Align.CENTER, 0);
        skipVoteB.label = board.myVote == -1 ? "SKIPPED ✓" : "SKIP VOTE";
        if (board.amOut()) bottomButtons(c, t, w, h, chatB);
        else bottomButtons(c, t, w, h, skipVoteB, chatB);
    }

    private void drawTally(Canvas c, float t, int w, int h) {
        float age = t - phaseShownAt;
        float y = header(c, "THE VOTES ARE IN", null, false, t, w, System.currentTimeMillis());
        float pad = 16 * dp, rowH = 54 * dp;
        List<Map.Entry<Integer, List<Integer>>> rows = new ArrayList<>(board.tally.entrySet());
        Collections.sort(rows, (a, b) -> b.getValue().size() - a.getValue().size());
        int k = 0;
        for (Map.Entry<Integer, List<Integer>> e : rows) {
            float yy = y + 10 * dp + k * rowH, cy = yy + rowH / 2;
            tmp.set(pad, yy, w - pad, yy + rowH - 6 * dp);
            ui.fill.setColor(e.getKey() == board.ejected ? 0x44FF1F4F : 0x26FFFFFF);
            c.drawRoundRect(tmp, 10 * dp, 10 * dp, ui.fill);
            Board.VP target = board.any(e.getKey());
            if (target != null && e.getKey() >= 0) {
                Avatar.draw(c, ui.misc, target.avatar, target.hue, pad + 26 * dp, cy - 3 * dp, 32 * dp, t, 0, 1f);
                ui.label(c, fit(target.name, 14, 100 * dp), pad + 50 * dp, cy + 2 * dp, 14, Neon.avatarColor(target.hue, t),
                        Paint.Align.LEFT, 0);
            } else {
                ui.label(c, "⊘ SKIPPED", pad + 14 * dp, cy + 2 * dp, 14, 0xFFAAAAAA, Paint.Align.LEFT, 0);
            }
            List<Integer> vs = e.getValue();
            for (int i = 0; i < vs.size(); i++) {
                if (age < 0.3f + i * 0.25f + k * 0.15f) break;
                Board.VP vp = board.any(vs.get(i));
                if (vp != null) {
                    Avatar.draw(c, ui.misc, vp.avatar, vp.hue, pad + 170 * dp + i * 22 * dp, cy - 3 * dp, 20 * dp, t, 0, 1f);
                }
            }
            ui.label(c, String.valueOf(vs.size()), w - pad - 14 * dp, cy + 4 * dp, 18, 0xFFFFFFFF, Paint.Align.RIGHT, 6);
            k++;
        }

        if (age > 2.5f) {
            float a = Math.min(0.9f, (age - 2.5f) * 1.5f);
            ui.fill.setColor(Neon.alpha(0xFF000000, a));
            c.drawRect(0, 0, w, h, ui.fill);
            float cy = h * 0.42f;
            if (board.ejected >= 0) {
                Board.VP p = board.any(board.ejected);
                float x = -60 * dp + (age - 2.5f) * w * 0.22f;
                c.save();
                c.rotate((age - 2.5f) * 140f, x, cy - 80 * dp);
                if (p != null) Avatar.draw(c, ui.misc, p.avatar, p.hue, x, cy - 80 * dp, 70 * dp, t, 0, 1f);
                c.restore();
                String line1 = board.name(board.ejected) + " was ejected.";
                int chars = (int) Math.min(line1.length(), (age - 2.6f) * 22);
                if (chars > 0) ui.label(c, line1.substring(0, chars), w / 2f, cy + 10 * dp, 20, 0xFFFFFFFF, Paint.Align.CENTER, 8);
                if (age > 4.8f) {
                    if (board.ejectedWasImp) {
                        float jx = (rnd.nextFloat() - 0.5f) * 5 * dp;
                        ui.label(c, "THEY WERE THE IMPOSTER", w / 2f + jx, cy + 56 * dp, 22, 0xFFFF1F4F, Paint.Align.CENTER, 16);
                        ui.label(c, "…but they get one last guess", w / 2f, cy + 84 * dp, 13, 0xFFBBBBCC, Paint.Align.CENTER, 0);
                    } else {
                        ui.label(c, "they were NOT the imposter", w / 2f, cy + 56 * dp, 20, 0xFF2BF5FF, Paint.Align.CENTER, 12);
                    }
                }
            } else {
                String line1 = "No one was ejected.";
                int chars = (int) Math.min(line1.length(), (age - 2.6f) * 22);
                if (chars > 0) ui.label(c, line1.substring(0, chars), w / 2f, cy, 22, 0xFFFFFFFF, Paint.Align.CENTER, 8);
                if (age > 4f) ui.label(c, "(tie or skipped)", w / 2f, cy + 32 * dp, 13, 0xFFAAAAAA, Paint.Align.CENTER, 0);
            }
        }
    }

    private void drawGuess(Canvas c, float t, int w, int h, long now) {
        float y = header(c, "LAST CHANCE", null, true, t, w, now);
        float pad = 16 * dp;
        boolean me = board.guesser == board.myId;
        Board.VP g = board.any(board.guesser);
        tmp.set(pad, y + 8 * dp, w - pad, y + 190 * dp);
        ui.panel(c, tmp, t * 2, 0.92f);
        if (g != null) Avatar.draw(c, ui.misc, g.avatar, g.hue, w / 2f, tmp.top + 50 * dp, 60 * dp, t, 0, 1f);
        if (me) {
            ui.label(c, "YOU'VE BEEN CAUGHT!", w / 2f, tmp.top + 108 * dp, 20, 0xFFFF1F4F, Paint.Align.CENTER, 12);
            ui.label(c, "guess the secret word to steal the win", w / 2f, tmp.top + 132 * dp, 12, 0xFFBBBBCC,
                    Paint.Align.CENTER, 0);
            if (!board.myCat.isEmpty()) {
                ui.label(c, "category: " + board.myCat, w / 2f, tmp.top + 158 * dp, 14, 0xFFFFE14D, Paint.Align.CENTER, 4);
            }
        } else {
            ui.label(c, board.name(board.guesser) + " was the imposter", w / 2f, tmp.top + 108 * dp, 17, 0xFFFF6080,
                    Paint.Align.CENTER, 8);
            ui.label(c, "now guessing the word" + "...".substring(0, 1 + (int) (t * 3) % 3), w / 2f,
                    tmp.top + 134 * dp, 13, 0xFFBBBBCC, Paint.Align.CENTER, 0);
        }
        if (board.guessDone) {
            String gt = board.guessText.isEmpty() ? "(no guess)" : "\"" + board.guessText + "\"";
            ui.label(c, "guessed " + gt + (board.guessOk ? "  ✓" : "  ✗"), w / 2f, tmp.bottom - 8 * dp, 15,
                    board.guessOk ? 0xFF40FF90 : 0xFFFF4060, Paint.Align.CENTER, 6);
        }
        float fb = me && !board.guessDone ? bottomY(h) - 12 * dp : h - v.safeBottom - 16 * dp;
        feedR.set(pad, tmp.bottom + 12 * dp, w - pad, fb);
        feed(c, feedR, t, true);
        if (me && !board.guessDone) bottomButtons(c, t, w, h, guessB);
    }

    private void drawOver(Canvas c, float t, int w, int h) {
        float age = t - phaseShownAt;
        if (age < 6f && t >= nextFirework) {
            float bx = w * (0.1f + rnd.nextFloat() * 0.8f), by = h * (0.08f + rnd.nextFloat() * 0.35f);
            float hue = board.impWins ? rnd.nextFloat() * 0.1f - 0.05f : 0.45f + rnd.nextFloat() * 0.2f;
            for (int i = 0; i < 36; i++) {
                Spark s = new Spark();
                double a = rnd.nextDouble() * Math.PI * 2;
                float sp = (60 + rnd.nextFloat() * 240) * dp;
                s.x = bx;
                s.y = by;
                s.vx = (float) Math.cos(a) * sp;
                s.vy = (float) Math.sin(a) * sp;
                s.life = s.max = 1.1f + rnd.nextFloat() * 0.6f;
                s.hue = hue + (rnd.nextFloat() - 0.5f) * 0.08f;
                sparks.add(s);
            }
            if (age > 0.5f) v.audio.sfx(Audio.POP);
            nextFirework = t + 0.25f + rnd.nextFloat() * 0.4f;
        }
        float top = v.safeTop + 20 * dp;
        String title = board.impWins ? "IMPOSTER WINS" : "CREW WINS";
        int col = board.impWins ? 0xFFFF1F4F : 0xFF2BF5FF;
        float jx = board.impWins ? (rnd.nextFloat() - 0.5f) * 4 * dp : 0;
        ui.label(c, title, w / 2f + jx - 3 * dp, top + 40 * dp, 38, 0x88FFFFFF, Paint.Align.CENTER, 0);
        ui.label(c, title, w / 2f + jx, top + 40 * dp, 38, col, Paint.Align.CENTER, 20);
        boolean iWon = board.amImp == board.impWins;
        ui.rainbow(c, iWon ? "YOU WON!" : "you lost", w / 2f, top + 72 * dp, 18, t, iWon ? 3 : 0);
        ui.label(c, board.overReason, w / 2f, top + 94 * dp, 12, 0xFFBBBBCC, Paint.Align.CENTER, 0);

        ui.label(c, "THE WORD WAS", w / 2f, top + 128 * dp, 11, 0xFF9999AA, Paint.Align.CENTER, 0);
        String wd = board.overWord.toUpperCase();
        ui.rainbow(c, wd, w / 2f, top + 160 * dp, Math.min(32, (w - 40 * dp) / dp / (Math.max(4, wd.length()) * 0.62f)), t, 2);
        ui.label(c, board.overCat, w / 2f, top + 180 * dp, 11, 0xFF9999AA, Paint.Align.CENTER, 0);

        ui.label(c, board.impIds.size() > 1 ? "THE IMPOSTERS" : "THE IMPOSTER", w / 2f, top + 212 * dp, 11, 0xFFFF6080,
                Paint.Align.CENTER, 0);
        int n = board.impIds.size();
        for (int i = 0; i < n; i++) {
            Board.VP p = board.any(board.impIds.get(i));
            float x = w / 2f + (i - (n - 1) / 2f) * 130 * dp;
            if (p != null) {
                Avatar.draw(c, ui.misc, p.avatar, p.hue, x, top + 248 * dp, 44 * dp, t, 0, 1f);
                ui.label(c, fit(p.name, 14, 120 * dp), x, top + 286 * dp, 14, Neon.avatarColor(p.hue, t), Paint.Align.CENTER, 6);
            }
        }
        float y = top + 300 * dp;
        if (board.guessDone && !board.guessText.isEmpty()) {
            ui.label(c, "last guess: \"" + board.guessText + "\"" + (board.guessOk ? " ✓" : " ✗"), w / 2f, y + 6 * dp, 13,
                    board.guessOk ? 0xFF40FF90 : 0xFFFF4060, Paint.Align.CENTER, 0);
            y += 14 * dp;
        }

        float pad = 22 * dp;
        List<Board.VP> ps = new ArrayList<>(board.players.values());
        Collections.sort(ps, (a, b) -> b.score - a.score);
        float rowH = 26 * dp;
        tmp.set(pad, y + 12 * dp, w - pad, Math.min(bottomY(h) - 14 * dp, y + 24 * dp + ps.size() * rowH));
        ui.panel(c, tmp, t, 0.88f);
        float ry = tmp.top + 20 * dp;
        int rank = 1;
        for (Board.VP p : ps) {
            if (ry > tmp.bottom - 4 * dp) break;
            ui.label(c, "#" + rank++, tmp.left + 14 * dp, ry + 4 * dp, 12, 0xFF9999AA, Paint.Align.LEFT, 0);
            Avatar.draw(c, ui.misc, p.avatar, p.hue, tmp.left + 58 * dp, ry, 20 * dp, t, 0, 1f);
            ui.label(c, fit(p.name, 13, tmp.width() - 140 * dp), tmp.left + 76 * dp, ry + 5 * dp, 13,
                    Neon.avatarColor(p.hue, t), Paint.Align.LEFT, 0);
            ui.label(c, p.score + " pts", tmp.right - 14 * dp, ry + 5 * dp, 13, 0xFFFFE14D, Paint.Align.RIGHT, 0);
            ry += rowH;
        }

        leaveB.label = host ? "END" : "LEAVE";
        if (host) {
            againB.enabled = board.players.size() >= Room.MIN_PLAYERS;
            bottomButtons(c, t, w, h, leaveB, lobbyB, againB);
        } else {
            ui.label(c, "waiting for the host" + "...".substring(0, 1 + (int) (t * 3) % 3), w / 2f,
                    bottomY(h) - 8 * dp, 12, 0xFFAAAAAA, Paint.Align.CENTER, 0);
            bottomButtons(c, t, w, h, leaveB);
        }
    }
}
