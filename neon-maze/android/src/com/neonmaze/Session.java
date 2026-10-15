package com.neonmaze;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/** A LAN game: the network link plus the roster everyone agrees on. */
final class Session {
    static final class Player {
        final int id;
        String name, avatar;
        int hue;
        // in-race state
        int x, y, dir, score;
        float dx, dy;

        Player(int id, String name, String avatar, int hue) {
            this.id = id;
            this.name = name;
            this.avatar = avatar;
            this.hue = hue;
        }
    }

    final Net net;
    final boolean host;
    int myId;
    int diff;
    int round = 1;
    final LinkedHashMap<Integer, Player> players = new LinkedHashMap<>();

    Session(Net net, boolean host) {
        this.net = net;
        this.host = host;
    }

    Player me() {
        return players.get(myId);
    }

    /** Spawn corner for a player: rank of their id among everyone in the race. */
    int slot(int id) {
        List<Integer> ids = new ArrayList<>(players.keySet());
        Collections.sort(ids);
        int i = ids.indexOf(id);
        return Math.max(0, i) % 4;
    }

    String lobbyLine() {
        StringBuilder sb = new StringBuilder("LOBBY|").append(diff).append('|');
        boolean first = true;
        for (Player p : players.values()) {
            if (!first) sb.append(';');
            first = false;
            sb.append(p.id).append(',').append(Net.enc(p.name)).append(',')
                    .append(Net.enc(p.avatar)).append(',').append(p.hue);
        }
        return sb.toString();
    }

    void applyLobby(String[] f) {
        if (f.length < 3) return;
        diff = Net.parse(f[1]);
        players.clear();
        if (f[2].isEmpty()) return;
        for (String e : f[2].split(";")) {
            String[] p = e.split(",", -1);
            if (p.length < 4) continue;
            int id = Net.parse(p[0]);
            players.put(id, new Player(id, Net.dec(p[1]), Net.dec(p[2]), Net.parse(p[3])));
        }
    }

    String beacon(String hostName) {
        return "NEONMAZE|1|" + Net.enc(hostName) + "|" + players.size() + "|"
                + PlayScreen.DIFF_NAMES[diff] + "|" + (net.accepting ? "1" : "0");
    }
}
