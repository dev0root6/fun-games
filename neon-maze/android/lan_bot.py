#!/usr/bin/env python3
"""
Neon Maze LAN bot — a fake racer for testing multiplayer with a single phone.

It joins a phone's lobby, rebuilds the exact same maze from the race seed
(java.util.Random is ported bit-for-bit), and runs the shortest path to the portal.

  phone on USB:   adb forward tcp:47777 tcp:47777 && ./lan_bot.py
  phone on Wi-Fi: ./lan_bot.py --host 192.168.1.23

Options: --speed moves/sec (default 3.5), --name, --avatar, --hue
"""
import argparse
import base64
import random
import socket
import threading
import time
from collections import deque

MASK = (1 << 48) - 1
DIFF_CFG = [(12, 7, 9), (18, 10, 7), (24, 13, 5), (32, 17, 3)]


class JavaRandom:
    """java.util.Random, exactly."""

    def __init__(self, seed):
        self.seed = (seed ^ 0x5DEECE66D) & MASK

    def next(self, bits):
        self.seed = (self.seed * 0x5DEECE66D + 0xB) & MASK
        r = self.seed >> (48 - bits)
        return r - (1 << 32) if r >= 1 << 31 else r  # (int) cast

    def next_int(self, bound):
        if bound & -bound == bound:
            return (bound * self.next(31)) >> 31
        while True:
            bits = self.next(31)
            val = bits % bound
            if bits - val + (bound - 1) < (1 << 31):
                return val


class Maze:
    """Port of Maze.java (layout, origins and exit only)."""

    def __init__(self, cw, ch, seed, multi=True):
        rng = JavaRandom(seed)
        self.W, self.H = W, H = 2 * cw + 1, 2 * ch + 1
        g = self.g = [1] * (W * H)
        g[W + 1] = 0
        seen = [False] * (cw * ch)
        stack = [0]
        seen[0] = True
        ddx, ddy = (1, -1, 0, 0), (0, 0, 1, -1)
        while stack:
            cur = stack[-1]
            cx, cy = cur % cw, cur // cw
            nbr = [i for i in range(4)
                   if 0 <= cx + ddx[i] < cw and 0 <= cy + ddy[i] < ch and not seen[(cy + ddy[i]) * cw + cx + ddx[i]]]
            if not nbr:
                stack.pop()
                continue
            i = nbr[rng.next_int(len(nbr))]
            nx, ny = cx + ddx[i], cy + ddy[i]
            g[(2 * cy + 1 + ddy[i]) * W + 2 * cx + 1 + ddx[i]] = 0
            g[(2 * ny + 1) * W + 2 * nx + 1] = 0
            seen[ny * cw + nx] = True
            stack.append(ny * cw + nx)
        for _ in range(cw * ch // 10):
            x = 1 + rng.next_int(W - 2)
            y = 1 + rng.next_int(H - 2)
            if g[y * W + x] == 1 and (x % 2) != (y % 2):
                g[y * W + x] = 0

        if multi:
            self.origins = [(1, 1), (W - 2, H - 2), (1, H - 2), (W - 2, 1)]
            ds = [self.bfs(*o) for o in self.origins]
            best, best_cost = -1, None
            for y in range(1, H, 2):
                for x in range(1, W, 2):
                    k = y * W + x
                    vals = [d[k] for d in ds]
                    mn, mx = min(vals), max(vals)
                    if mn <= 0:
                        continue
                    cost = (mx - mn) * 4 - mn
                    if best_cost is None or cost < best_cost:
                        best_cost, best = cost, k
        else:
            self.origins = [(1, 1)]
            d = self.bfs(1, 1)
            best = max(range(len(d)), key=lambda k: (d[k], -k))
        self.exit = (best % W, best // W)

    def wall(self, x, y):
        return not (0 <= x < self.W and 0 <= y < self.H) or self.g[y * self.W + x] == 1

    def bfs(self, sx, sy):
        d = [-1] * (self.W * self.H)
        d[sy * self.W + sx] = 0
        q = deque([(sx, sy)])
        while q:
            x, y = q.popleft()
            for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
                if not self.wall(nx, ny) and d[ny * self.W + nx] < 0:
                    d[ny * self.W + nx] = d[y * self.W + x] + 1
                    q.append((nx, ny))
        return d

    def path(self, start):
        d = self.bfs(*self.exit)
        x, y = start
        out = []
        while (x, y) != self.exit:
            for i, (nx, ny) in enumerate(((x + 1, y), (x, y + 1), (x - 1, y), (x, y - 1))):
                if not self.wall(nx, ny) and d[ny * self.W + nx] == d[y * self.W + x] - 1:
                    out.append((nx, ny, i))
                    x, y = nx, ny
                    break
        return out


def enc(s):
    return base64.urlsafe_b64encode(s.encode()).decode().rstrip("=")


def dec(s):
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4)).decode(errors="replace")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=47777)
    ap.add_argument("--name", default="BOT")
    ap.add_argument("--avatar", default="🤖")
    ap.add_argument("--hue", type=int, default=140)
    ap.add_argument("--speed", type=float, default=3.5, help="moves per second")
    a = ap.parse_args()

    sock = socket.create_connection((a.host, a.port), timeout=5)
    sock.settimeout(None)
    f = sock.makefile("r", encoding="utf-8")
    lock = threading.Lock()

    def send(line):
        with lock:
            sock.sendall((line + "\n").encode())

    send(f"HELLO|{enc(a.name)}|{enc(a.avatar)}|{a.hue}")
    state = {"id": None, "players": [], "race": None}
    print(f"connected to {a.host}:{a.port} — waiting in lobby")

    def run_race(seed, diff, rnd, token):
        cfg = DIFF_CFG[diff]
        cw, ch = cfg[1] + (rnd - 1) * 2, cfg[0] + (rnd - 1) * 3
        mz = Maze(cw, ch, seed)
        ids = sorted(state["players"])
        slot = ids.index(state["id"]) % 4 if state["id"] in ids else 0
        start = mz.origins[slot]
        steps = mz.path(start)
        print(f"race {rnd}: maze {mz.W}x{mz.H}, spawn {start}, exit {mz.exit}, {len(steps)} steps")
        time.sleep(3.0)  # countdown
        t0 = time.time()
        for x, y, d in steps:
            if state["race"] != token:
                return
            send(f"MOVE|{state['id']}|{x}|{y}|0|{d}")
            time.sleep(1 / a.speed * random.uniform(0.7, 1.3))
        send(f"FIN|{state['id']}|{int((time.time() - t0) * 1000)}")
        print("bot reached the portal")

    for line in f:
        p = line.rstrip("\n").split("|")
        cmd = p[0]
        if cmd == "WELCOME":
            state["id"] = int(p[1])
            print(f"joined as player {state['id']}")
        elif cmd == "LOBBY":
            state["players"] = [int(e.split(",")[0]) for e in p[2].split(";") if e]
            names = [dec(e.split(",")[1]) for e in p[2].split(";") if e]
            print("lobby:", ", ".join(names), "| difficulty", p[1])
        elif cmd == "LEFT":
            gone = int(p[1])
            state["players"] = [i for i in state["players"] if i != gone]
        elif cmd == "START":
            token = object()
            state["race"] = token
            threading.Thread(target=run_race, args=(int(p[1]), int(p[2]), int(p[3]), token), daemon=True).start()
        elif cmd == "WIN":
            who = int(p[1])
            print("winner:", "BOT" if who == state["id"] else f"player {who}", f"in {int(p[2]) / 1000:.1f}s")
            state["race"] = None
        elif cmd == "FULL":
            print("lobby full / race already running")
            break
    print("disconnected")


if __name__ == "__main__":
    main()
