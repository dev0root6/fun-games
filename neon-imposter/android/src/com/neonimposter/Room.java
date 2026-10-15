package com.neonimposter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Host-side rules engine. Owns the truth: roles, the secret word, turns, votes, timers.
 * Pure Java (no Android) so whole games can be simulated on a desktop JVM.
 *
 * Output goes through {@link Out}: to = -1 means everyone (host included), otherwise one player id.
 */
final class Room {
    interface Out {
        void send(int to, String line);
    }

    static final int MIN_PLAYERS = 3;
    static final int[] DISCUSS_OPTS = {30, 60, 90, 120, 180};
    static final int CLUE_SECS = 35, VOTE_SECS = 45, REVEAL_SECS = 90, TALLY_SECS = 8, GUESS_SECS = 35;
    static final int MAX_VOTE_ROUNDS = 3;
    static final int CLUE_MAX = 32;

    static final String LOBBY = "LOBBY", REVEAL = "REVEAL", CLUE = "CLUE", DISCUSS = "DISCUSS", VOTE = "VOTE",
            TALLY = "TALLY", GUESS = "GUESS", OVER = "OVER";

    static final class P {
        final int id;
        String name, avatar;
        int hue, score;
        boolean imp, out, ready, voted;
        int vote = -1;

        P(int id, String name, String avatar, int hue) {
            this.id = id;
            this.name = name;
            this.avatar = avatar;
            this.hue = hue;
        }
    }

    // settings (host-editable in the lobby)
    int cat = -1;          // -1 random
    int imps = 1;
    int rounds = 2;
    int discussIdx = 1;
    boolean hint = true;   // imposter sees the category

    final LinkedHashMap<Integer, P> ps = new LinkedHashMap<>();
    private final Out out;
    private final Random rnd;
    private final HashSet<String> used = new HashSet<>();

    String phase = LOBBY;
    private long phaseEnd;
    private String word = "", catName = "";
    private final List<Integer> order = new ArrayList<>();
    private int turnIdx, round, roundsThisCycle, voteRound, guesser = -1, ejected = -1;
    private final HashSet<Integer> votedForImp = new HashSet<>();
    private final List<Integer> gameImps = new ArrayList<>();

    Room(Out out, long seed) {
        this.out = out;
        this.rnd = new Random(seed);
    }

    boolean inGame() {
        return !phase.equals(LOBBY);
    }

    // ── roster / lobby ────────────────────────────────────────────────────

    boolean join(int id, String name, String avatar, int hue) {
        if (inGame() && !ps.containsKey(id)) return false;
        P p = ps.get(id);
        if (p == null) ps.put(id, p = new P(id, name, avatar, hue));
        p.name = name;
        p.avatar = avatar;
        p.hue = hue;
        lobby();
        return true;
    }

    String settingsCsv() {
        return cat + "," + imps + "," + rounds + "," + discussIdx + "," + (hint ? 1 : 0);
    }

    void lobby() {
        StringBuilder sb = new StringBuilder("LOBBY|").append(settingsCsv()).append('|');
        boolean first = true;
        for (P p : ps.values()) {
            if (!first) sb.append(';');
            first = false;
            sb.append(p.id).append(',').append(Wire.enc(p.name)).append(',').append(Wire.enc(p.avatar))
                    .append(',').append(p.hue).append(',').append(p.score);
        }
        out.send(-1, sb.toString());
    }

    int maxImps() {
        return ps.size() >= 6 ? 2 : 1;
    }

    /** Lobby settings change from the host. which: 0 cat, 1 imps, 2 rounds, 3 discussion, 4 hint. */
    void setting(int which, int delta) {
        if (inGame()) return;
        switch (which) {
            case 0:
                cat = ((cat + 1 + delta) % (Words.CATEGORIES.length + 1) + Words.CATEGORIES.length + 1)
                        % (Words.CATEGORIES.length + 1) - 1;
                break;
            case 1:
                imps = imps == 1 ? 2 : 1;
                break;
            case 2:
                rounds = (rounds - 1 + delta + 3) % 3 + 1;
                break;
            case 3:
                discussIdx = (discussIdx + delta + DISCUSS_OPTS.length) % DISCUSS_OPTS.length;
                break;
            case 4:
                hint = !hint;
                break;
            default:
                break;
        }
        lobby();
    }

    void leave(int id) {
        P p = ps.remove(id);
        if (p == null) return;
        out.send(-1, "LEFT|" + id);
        lobby();
        if (!inGame()) return;
        if (ps.size() < MIN_PLAYERS) {
            toLobby("not enough players left");
            return;
        }
        if (phase.equals(OVER)) return;
        if (p.imp && aliveImps() == 0) {
            over(false, p.name + " (imposter) left the game");
            return;
        }
        switch (phase) {
            case CLUE:
                if (turnIdx < order.size() && order.get(turnIdx) == id) {
                    order.remove(turnIdx);
                    turnIdx--;
                    nextTurn();
                } else {
                    int idx = order.indexOf(id);
                    if (idx >= 0) {
                        order.remove(idx);
                        if (idx < turnIdx) turnIdx--;
                    }
                }
                break;
            case VOTE:
                if (allVoted()) tally();
                break;
            case REVEAL:
                if (allReady()) startCycle(rounds);
                break;
            case GUESS:
                if (guesser == id) afterImpCaught();
                break;
            default:
                break;
        }
    }

    void toLobby(String why) {
        phase = LOBBY;
        for (P p : ps.values()) p.imp = p.out = p.ready = p.voted = false;
        out.send(-1, "PHASE|" + LOBBY + "|0|0|0");
        if (why != null) out.send(-1, "SYS|" + Wire.enc(why));
        lobby();
    }

    // ── game flow ─────────────────────────────────────────────────────────

    boolean start(long now) {
        nowRef = now;
        if (ps.size() < MIN_PLAYERS) return false;
        int c = cat >= 0 ? cat : rnd.nextInt(Words.CATEGORIES.length);
        String[] list = Words.WORDS[c];
        String w = list[rnd.nextInt(list.length)];
        for (int tries = 0; tries < 40 && used.contains(w); tries++) w = list[rnd.nextInt(list.length)];
        used.add(w);
        word = w;
        catName = Words.CATEGORIES[c];

        List<Integer> ids = new ArrayList<>(ps.keySet());
        Collections.shuffle(ids, rnd);
        int n = Math.min(imps, maxImps());
        for (P p : ps.values()) {
            p.imp = p.out = p.ready = p.voted = false;
            p.vote = -1;
        }
        gameImps.clear();
        for (int i = 0; i < n; i++) {
            ps.get(ids.get(i)).imp = true;
            gameImps.add(ids.get(i));
        }
        voteRound = 0;
        guesser = ejected = -1;
        votedForImp.clear();
        for (P p : ps.values()) {
            String cn = p.imp ? (hint ? catName : "") : catName;
            out.send(p.id, "ROLE|" + (p.imp ? 1 : 0) + "|" + Wire.enc(cn) + "|" + Wire.enc(p.imp ? "" : word));
        }
        setPhase(REVEAL, REVEAL_SECS, 0, 0, now);
        return true;
    }

    private long nowRef;

    private void setPhase(String ph, int secs, int a, int b, long now) {
        phase = ph;
        phaseEnd = secs > 0 ? now + secs * 1000L : Long.MAX_VALUE;
        out.send(-1, "PHASE|" + ph + "|" + secs + "|" + a + "|" + b);
    }

    private boolean allReady() {
        for (P p : ps.values()) if (!p.ready) return false;
        return true;
    }

    private boolean allVoted() {
        for (P p : ps.values()) if (!p.out && !p.voted) return false;
        return true;
    }

    private int aliveImps() {
        int n = 0;
        for (P p : ps.values()) if (p.imp && !p.out) n++;
        return n;
    }

    /** A clue cycle: some rounds of clues, then discussion, then a vote. */
    private void startCycle(int nRounds) {
        roundsThisCycle = nRounds;
        round = 1;
        order.clear();
        for (P p : ps.values()) if (!p.out) order.add(p.id);
        Collections.shuffle(order, rnd);
        // the imposter never has to speak first
        for (int i = 0; i < order.size(); i++) {
            if (!ps.get(order.get(i)).imp) {
                order.add(0, order.remove(i));
                break;
            }
        }
        turnIdx = 0;
        setPhase(CLUE, CLUE_SECS, order.get(0), round, nowRef);
    }

    private void nextTurn() {
        turnIdx++;
        if (turnIdx >= order.size()) {
            round++;
            turnIdx = 0;
            if (round > roundsThisCycle || order.isEmpty()) {
                setPhase(DISCUSS, DISCUSS_OPTS[discussIdx], 0, 0, nowRef);
                return;
            }
        }
        setPhase(CLUE, CLUE_SECS, order.get(turnIdx), round, nowRef);
    }

    private void startVote() {
        voteRound++;
        for (P p : ps.values()) {
            p.voted = false;
            p.vote = -1;
        }
        setPhase(VOTE, VOTE_SECS, voteRound, 0, nowRef);
    }

    private void tally() {
        Map<Integer, List<Integer>> votes = new HashMap<>();
        for (P p : ps.values()) {
            if (p.out) continue;
            int target = p.voted && ps.containsKey(p.vote) && !ps.get(p.vote).out ? p.vote : -1;
            List<Integer> l = votes.get(target);
            if (l == null) votes.put(target, l = new ArrayList<>());
            l.add(p.id);
        }
        int best = -1, bestN = 0;
        boolean tie = false;
        for (Map.Entry<Integer, List<Integer>> e : votes.entrySet()) {
            int n = e.getValue().size();
            if (n > bestN) {
                bestN = n;
                best = e.getKey();
                tie = false;
            } else if (n == bestN) {
                tie = true;
            }
        }
        ejected = (tie || best < 0) ? -1 : best;
        boolean wasImp = ejected >= 0 && ps.get(ejected).imp;
        if (wasImp) votedForImp.addAll(votes.get(ejected));
        StringBuilder sb = new StringBuilder("TALLY|").append(ejected).append('|').append(wasImp ? 1 : 0).append('|');
        boolean first = true;
        for (Map.Entry<Integer, List<Integer>> e : votes.entrySet()) {
            if (!first) sb.append(';');
            first = false;
            sb.append(e.getKey()).append(':');
            for (int i = 0; i < e.getValue().size(); i++) {
                if (i > 0) sb.append('.');
                sb.append(e.getValue().get(i));
            }
        }
        out.send(-1, sb.toString());
        setPhase(TALLY, TALLY_SECS, ejected, wasImp ? 1 : 0, nowRef);
    }

    private void afterTally() {
        if (ejected >= 0) {
            P p = ps.get(ejected);
            if (p == null) {
                startCycle(1);
                return;
            }
            p.out = true;
            if (p.imp) {
                guesser = p.id;
                setPhase(GUESS, GUESS_SECS, guesser, 0, nowRef);
            } else {
                over(true, "an innocent was ejected");
            }
        } else if (voteRound >= MAX_VOTE_ROUNDS) {
            over(true, "the crew couldn't agree");
        } else {
            out.send(-1, "SYS|" + Wire.enc("no one was ejected — one more round of clues"));
            startCycle(1);
        }
    }

    private void afterImpCaught() {
        guesser = -1;
        if (aliveImps() > 0) {
            out.send(-1, "SYS|" + Wire.enc("one imposter down… but another is still among you"));
            startCycle(1);
        } else {
            over(false, "the imposter was caught");
        }
    }

    static boolean guessOk(String guess, String word) {
        String g = Wire.norm(guess), w = Wire.norm(word);
        if (g.isEmpty()) return false;
        if (g.equals(w) || g.equals(w + "s") || (g + "s").equals(w)) return true;
        return w.length() >= 5 && lev(g, w) <= 1;
    }

    static int lev(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int c = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + c);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    private void over(boolean impWins, String reason) {
        StringBuilder imps = new StringBuilder();
        for (int id : gameImps) {
            if (imps.length() > 0) imps.append('.');
            imps.append(id);
        }
        for (P p : ps.values()) {
            if (p.imp) {
                if (impWins) p.score += 3;
            } else if (!impWins) {
                p.score += 1 + (votedForImp.contains(p.id) ? 1 : 0);
            }
        }
        out.send(-1, "OVER|" + (impWins ? 1 : 0) + "|" + Wire.enc(word) + "|" + Wire.enc(catName) + "|" + imps
                + "|" + Wire.enc(reason));
        phase = OVER;
        phaseEnd = Long.MAX_VALUE;
        out.send(-1, "PHASE|" + OVER + "|0|0|0");
        lobby();
    }

    // ── input ─────────────────────────────────────────────────────────────

    /** A message from player {@code id} (the host's own actions come through here too). */
    void handle(int id, String[] f, long now) {
        nowRef = now;
        P p = ps.get(id);
        if (p == null || f.length == 0) return;
        switch (f[0]) {
            case "READY":
                if (phase.equals(REVEAL) && !p.ready) {
                    p.ready = true;
                    out.send(-1, "READY|" + id);
                    if (allReady()) startCycle(rounds);
                }
                break;
            case "CLUE": {
                if (!phase.equals(CLUE) || turnIdx >= order.size() || order.get(turnIdx) != id) break;
                String c = Wire.clean(Wire.s(f, 1), CLUE_MAX);
                if (c.isEmpty()) break;
                if (!p.imp && Wire.norm(c).contains(Wire.norm(word))) {
                    out.send(id, "ERR|" + Wire.enc("you can't say the secret word!"));
                    break;
                }
                out.send(-1, "CLUE|" + id + "|" + round + "|" + Wire.enc(c));
                nextTurn();
                break;
            }
            case "CHAT": {
                String c = Wire.clean(Wire.s(f, 1), 60);
                if (!c.isEmpty() && inGame()) out.send(-1, "CHAT|" + id + "|" + Wire.enc(c));
                break;
            }
            case "VOTE": {
                if (!phase.equals(VOTE) || p.out) break;
                int target = Wire.i(f, 1);
                P t = ps.get(target);
                if (target != -1 && (t == null || t.out || target == id)) break;
                p.vote = target;
                if (!p.voted) {
                    p.voted = true;
                    out.send(-1, "VOTED|" + id);
                }
                if (allVoted()) tally();
                break;
            }
            case "GUESS": {
                if (!phase.equals(GUESS) || id != guesser) break;
                String g = Wire.clean(Wire.s(f, 1), 40);
                boolean ok = guessOk(g, word);
                out.send(-1, "GUESSRES|" + id + "|" + Wire.enc(g) + "|" + (ok ? 1 : 0));
                if (ok) over(true, p.name + " guessed the word");
                else afterImpCaught();
                break;
            }
            case "SKIPDISCUSS":
                if (id == 0 && phase.equals(DISCUSS)) startVote();
                break;
            default:
                break;
        }
    }

    /** Drive timers. Call every frame on the host. */
    void tick(long now) {
        nowRef = now;
        if (now < phaseEnd) return;
        switch (phase) {
            case REVEAL:
                for (P p : ps.values()) p.ready = true;
                startCycle(rounds);
                break;
            case CLUE: {
                int id = order.get(turnIdx);
                out.send(-1, "CLUE|" + id + "|" + round + "|" + Wire.enc("… (no clue)"));
                nextTurn();
                break;
            }
            case DISCUSS:
                startVote();
                break;
            case VOTE:
                tally();
                break;
            case TALLY:
                afterTally();
                break;
            case GUESS:
                out.send(-1, "GUESSRES|" + guesser + "|" + Wire.enc("") + "|0");
                afterImpCaught();
                break;
            default:
                phaseEnd = Long.MAX_VALUE;
                break;
        }
    }

    String word() {
        return word;
    }
}
