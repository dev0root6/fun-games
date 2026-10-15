package com.neonimposter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * What one player knows, built only from messages the host sends them.
 * Pure Java; the screen draws from this and tests replay it.
 */
final class Board {
    static final class VP {
        final int id;
        String name, avatar;
        int hue, score;
        boolean ready, voted, out;

        VP(int id) {
            this.id = id;
        }
    }

    static final class Line {
        final int kind; // 0 clue, 1 chat, 2 system
        final int id, round;
        final String text;

        Line(int kind, int id, int round, String text) {
            this.kind = kind;
            this.id = id;
            this.round = round;
            this.text = text;
        }
    }

    /** Hook for sounds/haptics; called after a message is applied. */
    interface Events {
        void on(String event, int arg);
    }

    int myId = -1;
    final LinkedHashMap<Integer, VP> players = new LinkedHashMap<>();
    /** Everyone ever seen this session, so departed players still have names. */
    final LinkedHashMap<Integer, VP> known = new LinkedHashMap<>();
    int cat = -1, imps = 1, rounds = 2, discussIdx = 1;
    boolean hint = true;

    String phase = Room.LOBBY;
    long phaseEnd;
    int phaseSecs, turnId = -1, round, voteRound, guesser = -1;

    boolean amImp;
    String myCat = "", myWord = "";
    final List<Line> feed = new ArrayList<>();
    int myVote = -2;

    // tally
    int ejected = -1;
    boolean ejectedWasImp;
    final LinkedHashMap<Integer, List<Integer>> tally = new LinkedHashMap<>();
    // guess + game over
    String guessText = "";
    boolean guessOk, guessDone;
    boolean impWins;
    String overWord = "", overCat = "", overReason = "";
    final List<Integer> impIds = new ArrayList<>();
    String toast = "";
    long toastUntil;

    Events events;

    VP me() {
        return players.get(myId);
    }

    VP any(int id) {
        VP p = players.get(id);
        return p != null ? p : known.get(id);
    }

    String name(int id) {
        VP p = any(id);
        return p != null ? p.name : "???";
    }

    long remainingMs(long now) {
        return Math.max(0, phaseEnd - now);
    }

    void apply(String line, long now) {
        String[] f = Wire.split(line);
        switch (f[0]) {
            case "LOBBY": {
                String[] st = f.length > 1 ? f[1].split(",") : new String[0];
                if (st.length >= 5) {
                    cat = Integer.parseInt(st[0]);
                    imps = Integer.parseInt(st[1]);
                    rounds = Integer.parseInt(st[2]);
                    discussIdx = Integer.parseInt(st[3]);
                    hint = st[4].equals("1");
                }
                LinkedHashMap<Integer, VP> old = new LinkedHashMap<>(players);
                players.clear();
                if (f.length > 2 && !f[2].isEmpty()) {
                    for (String e : f[2].split(";")) {
                        String[] p = e.split(",", -1);
                        if (p.length < 5) continue;
                        int id = Integer.parseInt(p[0]);
                        VP v = old.containsKey(id) ? old.get(id) : new VP(id);
                        v.name = Wire.dec(p[1]);
                        v.avatar = Wire.dec(p[2]);
                        v.hue = Integer.parseInt(p[3]);
                        v.score = Integer.parseInt(p[4]);
                        players.put(id, v);
                        known.put(id, v);
                    }
                }
                break;
            }
            case "ROLE":
                amImp = Wire.i(f, 1) == 1;
                myCat = Wire.s(f, 2);
                myWord = Wire.s(f, 3);
                feed.clear();
                tally.clear();
                impIds.clear();
                ejected = -1;
                guessDone = false;
                guessText = "";
                for (VP p : players.values()) p.ready = p.voted = p.out = false;
                break;
            case "PHASE": {
                String prev = phase;
                phase = f[1];
                phaseSecs = Wire.i(f, 2);
                phaseEnd = phaseSecs > 0 ? now + phaseSecs * 1000L : Long.MAX_VALUE;
                int a = Wire.i(f, 3), b = Wire.i(f, 4);
                switch (phase) {
                    case Room.CLUE:
                        turnId = a;
                        round = b;
                        break;
                    case Room.VOTE:
                        voteRound = a;
                        myVote = -2;
                        for (VP p : players.values()) p.voted = false;
                        break;
                    case Room.GUESS:
                        guesser = a;
                        guessDone = false;
                        break;
                    case Room.TALLY:
                        break;
                    case Room.LOBBY:
                        feed.clear();
                        amImp = false;
                        myWord = myCat = "";
                        break;
                    default:
                        break;
                }
                if (events != null) events.on(prev.equals(phase) ? "TURN" : "PHASE", a);
                break;
            }
            case "READY": {
                VP p = players.get(Wire.i(f, 1));
                if (p != null) p.ready = true;
                break;
            }
            case "CLUE":
                feed.add(new Line(0, Wire.i(f, 1), Wire.i(f, 2), Wire.s(f, 3)));
                if (events != null) events.on("CLUE", Wire.i(f, 1));
                break;
            case "CHAT":
                feed.add(new Line(1, Wire.i(f, 1), 0, Wire.s(f, 2)));
                if (events != null) events.on("CHAT", Wire.i(f, 1));
                break;
            case "SYS":
                feed.add(new Line(2, -1, 0, Wire.s(f, 1)));
                toast(Wire.s(f, 1), now, 3500);
                break;
            case "ERR":
                toast(Wire.s(f, 1), now, 3000);
                if (events != null) events.on("ERR", 0);
                break;
            case "VOTED": {
                VP p = players.get(Wire.i(f, 1));
                if (p != null) p.voted = true;
                if (events != null) events.on("VOTED", Wire.i(f, 1));
                break;
            }
            case "TALLY": {
                ejected = Wire.i(f, 1);
                ejectedWasImp = Wire.i(f, 2) == 1;
                tally.clear();
                if (f.length > 3 && !f[3].isEmpty()) {
                    for (String e : f[3].split(";")) {
                        String[] kv = e.split(":", -1);
                        List<Integer> voters = new ArrayList<>();
                        if (kv.length > 1 && !kv[1].isEmpty()) {
                            for (String v : kv[1].split("\\.")) voters.add(Integer.parseInt(v));
                        }
                        tally.put(Integer.parseInt(kv[0]), voters);
                    }
                }
                VP p = players.get(ejected);
                if (p != null) p.out = true;
                break;
            }
            case "GUESSRES":
                guessText = Wire.s(f, 2);
                guessOk = Wire.i(f, 3) == 1;
                guessDone = true;
                break;
            case "OVER":
                impWins = Wire.i(f, 1) == 1;
                overWord = Wire.s(f, 2);
                overCat = Wire.s(f, 3);
                impIds.clear();
                if (f.length > 4 && !f[4].isEmpty()) {
                    for (String s : f[4].split("\\.")) impIds.add(Integer.parseInt(s));
                }
                overReason = Wire.s(f, 5);
                if (events != null) events.on("OVER", impWins ? 1 : 0);
                break;
            case "LEFT": {
                VP p = players.get(Wire.i(f, 1));
                if (p != null) toast(p.name + " left", now, 2500);
                break;
            }
            default:
                break;
        }
    }

    void toast(String s, long now, long ms) {
        toast = s;
        toastUntil = now + ms;
    }

    boolean amOut() {
        VP p = me();
        return p != null && p.out;
    }

    int aliveCount() {
        int n = 0;
        for (VP p : players.values()) if (!p.out) n++;
        return n;
    }

    /** Clues given by one player this game, joined. */
    String cluesOf(int id) {
        StringBuilder sb = new StringBuilder();
        for (Line l : feed) {
            if (l.kind == 0 && l.id == id) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(l.text);
            }
        }
        return sb.toString();
    }
}
