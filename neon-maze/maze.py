#!/usr/bin/env python3
"""
NEON MAZE — a terminal maze crawler with a frankly irresponsible amount of UI.

Controls:  arrows / WASD / hjkl  move
           F  toggle fog        P  pause
           R  reroll maze       Q  quit to menu

Needs a UTF-8 terminal. 256 colors strongly recommended.
"""
import curses
import locale
import math
import random
import time
from collections import deque

locale.setlocale(locale.LC_ALL, "")

FPS = 30
HUD_W = 32
MIN_W, MIN_H = 44, 16

# name, maze cells wide, maze cells tall, torch radius
DIFFICULTIES = [
    ("EASY", 12, 7, 9),
    ("NORMAL", 18, 10, 7),
    ("HARD", 24, 13, 5),
    ("INSANE", 32, 17, 3),
]

WALL_SHADES = ["██", "▓▓", "▒▒", "░░"]
PORTAL_FRAMES = ["◐◑", "◓◒", "◑◐", "◒◓"]
EQ_BARS = "▁▂▃▄▅▆▇█"
ARROWS = ["→", "↘", "↓", "↙", "←", "↖", "↑", "↗"]
BRAILLE = [(0, 0, 0x01), (0, 1, 0x02), (0, 2, 0x04), (1, 0, 0x08),
           (1, 1, 0x10), (1, 2, 0x20), (0, 3, 0x40), (1, 3, 0x80)]

FONT = {
    "N": ["█   █", "██  █", "█ █ █", "█  ██", "█   █"],
    "E": ["█████", "█    ", "████ ", "█    ", "█████"],
    "O": [" ███ ", "█   █", "█   █", "█   █", " ███ "],
    "M": ["█   █", "██ ██", "█ █ █", "█   █", "█   █"],
    "A": [" ███ ", "█   █", "█████", "█   █", "█   █"],
    "Z": ["█████", "   █ ", "  █  ", " █   ", "█████"],
    "C": [" ████", "█    ", "█    ", "█    ", " ████"],
    "L": ["█    ", "█    ", "█    ", "█    ", "█████"],
    "R": ["████ ", "█   █", "████ ", "█  █ ", "█   █"],
    "V": ["█   █", "█   █", "█   █", " █ █ ", "  █  "],
    "!": ["█", "█", "█", " ", "█"],
    " ": ["   "] * 5,
}

MARQUEE = ("  ★ NEON MAZE ★  find the spinning portal ◐◑  ·  ◆ grab gems for points  ·  "
           "✦ torch surges widen your light  ·  ◖◗ that's you  ·  walls bite back  ")

KEYS = {
    curses.KEY_UP: (0, -1), ord("w"): (0, -1), ord("k"): (0, -1), ord("W"): (0, -1),
    curses.KEY_DOWN: (0, 1), ord("s"): (0, 1), ord("j"): (0, 1), ord("S"): (0, 1),
    curses.KEY_LEFT: (-1, 0), ord("a"): (-1, 0), ord("h"): (-1, 0), ord("A"): (-1, 0),
    curses.KEY_RIGHT: (1, 0), ord("d"): (1, 0), ord("l"): (1, 0), ord("D"): (1, 0),
}
ENTER_KEYS = (10, 13, curses.KEY_ENTER, ord(" "))


# ───────────────────────────── drawing helpers ─────────────────────────────

def hsv_to_256(h, v):
    """Hue 0..1 at value 0..1 to an xterm-256 color cube index."""
    h = (h % 1.0) * 6
    i, f = int(h) % 6, h - int(h)
    r, g, b = [(1, f, 0), (1 - f, 1, 0), (0, 1, f),
               (0, 1 - f, 1), (f, 0, 1), (1, 0, 1 - f)][i]
    r, g, b = (max(0, min(5, round(c * v * 5))) for c in (r, g, b))
    return 16 + 36 * r + 6 * g + b


class Palette:
    """Lazily allocates curses color pairs so we can paint with hues."""

    BASIC = [curses.COLOR_RED, curses.COLOR_YELLOW, curses.COLOR_GREEN,
             curses.COLOR_CYAN, curses.COLOR_BLUE, curses.COLOR_MAGENTA]

    def __init__(self):
        self.enabled = curses.has_colors()
        self.rich = self.enabled and curses.COLORS >= 256
        self.cache = {}
        self.next_pair = 1

    def pair(self, fg):
        if not self.enabled:
            return 0
        if fg in self.cache:
            return self.cache[fg]
        if self.next_pair >= curses.COLOR_PAIRS:
            return 0
        curses.init_pair(self.next_pair, fg, -1)
        attr = curses.color_pair(self.next_pair)
        self.cache[fg] = attr
        self.next_pair += 1
        return attr

    def hue(self, h, v=1.0):
        v = max(0.2, min(1.0, v))
        if self.rich:
            return self.pair(hsv_to_256(h, v))
        attr = self.pair(self.BASIC[int((h % 1.0) * 6) % 6])
        return attr | (curses.A_DIM if v < 0.5 else 0)

    def gray(self, v):
        v = max(0.0, min(1.0, v))
        if self.rich:
            return self.pair(232 + round(v * 23))
        return self.pair(curses.COLOR_WHITE) | (curses.A_DIM if v < 0.5 else 0)


def put(scr, y, x, s, attr=0):
    h, w = scr.getmaxyx()
    if y < 0 or y >= h or x >= w:
        return
    if x < 0:
        s, x = s[-x:], 0
    if x + len(s) > w:
        s = s[:w - x]
    if y == h - 1 and x + len(s) >= w:
        s = s[:w - x - 1]  # writing the bottom-right cell raises in curses
    if s:
        try:
            scr.addstr(y, x, s, attr)
        except curses.error:
            pass


def rainbow(scr, P, y, x, text, t, spread=0.04, speed=0.4, bold=True):
    for i, ch in enumerate(text):
        if ch != " ":
            put(scr, y, x + i, ch, P.hue(i * spread + t * speed) | (curses.A_BOLD if bold else 0))


def big_text(scr, P, text, top, t):
    rows = [""] * 5
    for ch in text:
        glyph = FONT.get(ch, FONT[" "])
        for i in range(5):
            rows[i] += glyph[i] + " "
    width = len(rows[0])
    h, w = scr.getmaxyx()
    if width > w - 2:
        rainbow(scr, P, top + 2, (w - len(text)) // 2, text, t)
        return
    x0 = (w - width) // 2
    for col in range(width):
        dy = round(math.sin(t * 4 + col * 0.22))
        for r in range(5):
            if rows[r][col] == "█":
                put(scr, top + r + dy + 1, x0 + col + 1, "░", P.gray(0.25))
    for col in range(width):
        dy = round(math.sin(t * 4 + col * 0.22))
        attr = P.hue(col * 0.025 + t * 0.4) | curses.A_BOLD
        for r in range(5):
            if rows[r][col] == "█":
                put(scr, top + r + dy, x0 + col, "█", attr)


def box(scr, P, y, x, bh, bw, t, fill=True):
    """Rounded box whose border is a rainbow flowing around the perimeter."""
    perim = []
    for i in range(bw):
        perim.append((y, x + i))
    for i in range(1, bh):
        perim.append((y + i, x + bw - 1))
    for i in range(bw - 2, -1, -1):
        perim.append((y + bh - 1, x + i))
    for i in range(bh - 2, 0, -1):
        perim.append((y + i, x))
    n = len(perim)
    for k, (py, px) in enumerate(perim):
        if (py, px) == (y, x):
            ch = "╭"
        elif (py, px) == (y, x + bw - 1):
            ch = "╮"
        elif (py, px) == (y + bh - 1, x):
            ch = "╰"
        elif (py, px) == (y + bh - 1, x + bw - 1):
            ch = "╯"
        elif py in (y, y + bh - 1):
            ch = "─"
        else:
            ch = "│"
        put(scr, py, px, ch, P.hue(k / n - t * 0.3) | curses.A_BOLD)
    if fill:
        for r in range(1, bh - 1):
            put(scr, y + r, x + 1, " " * (bw - 2))


def bar(scr, P, y, x, width, frac, t, full="█", empty="░"):
    n = round(max(0.0, min(1.0, frac)) * width)
    for i in range(width):
        if i < n:
            put(scr, y, x + i, full, P.hue(i * 0.04 + t * 0.5) | curses.A_BOLD)
        else:
            put(scr, y, x + i, empty, P.gray(0.3))


def fmt_time(sec):
    return f"{int(sec // 60):02d}:{sec % 60:04.1f}"


def drain_keys(scr):
    keys = []
    while True:
        k = scr.getch()
        if k == -1:
            return keys
        keys.append(k)


# ───────────────────────────────── maze ─────────────────────────────────

def gen_maze(cw, ch, rng):
    """Recursive-backtracker maze, then braided a little so it has loops."""
    W, H = 2 * cw + 1, 2 * ch + 1
    g = [[1] * W for _ in range(H)]
    g[1][1] = 0
    stack, seen = [(0, 0)], {(0, 0)}
    while stack:
        cx, cy = stack[-1]
        nbrs = [(cx + dx, cy + dy, dx, dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))
                if 0 <= cx + dx < cw and 0 <= cy + dy < ch and (cx + dx, cy + dy) not in seen]
        if not nbrs:
            stack.pop()
            continue
        nx, ny, dx, dy = rng.choice(nbrs)
        g[2 * cy + 1 + dy][2 * cx + 1 + dx] = 0
        g[2 * ny + 1][2 * nx + 1] = 0
        seen.add((nx, ny))
        stack.append((nx, ny))
    for _ in range(cw * ch // 10):
        x, y = rng.randrange(1, W - 1), rng.randrange(1, H - 1)
        if g[y][x] and (x % 2) != (y % 2):
            g[y][x] = 0
    return g


def bfs(grid, start):
    H, W = len(grid), len(grid[0])
    dist = {start: 0}
    q = deque([start])
    while q:
        x, y = q.popleft()
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            n = (x + dx, y + dy)
            if 0 <= n[0] < W and 0 <= n[1] < H and not grid[n[1]][n[0]] and n not in dist:
                dist[n] = dist[(x, y)] + 1
                q.append(n)
    return dist


class Level:
    def __init__(self, cw, ch, seed):
        rng = random.Random(seed)
        self.grid = gen_maze(cw, ch, rng)
        self.H, self.W = len(self.grid), len(self.grid[0])
        self.start = (1, 1)
        dist = bfs(self.grid, self.start)
        self.exit = max(dist, key=dist.get)
        self.par = dist[self.exit]
        cells = [(x, y) for y in range(1, self.H, 2) for x in range(1, self.W, 2)
                 if (x, y) not in (self.start, self.exit)]
        rng.shuffle(cells)
        n_gems = max(3, cw * ch // 10)
        self.gems = set(cells[:n_gems])
        self.powers = set(cells[n_gems:n_gems + max(1, cw * ch // 60)])
        self.total_gems = len(self.gems)

    def wall(self, x, y):
        return not (0 <= x < self.W and 0 <= y < self.H) or self.grid[y][x] == 1


# ───────────────────────────────── game ─────────────────────────────────

class Game:
    def __init__(self, scr, P, diff_idx):
        self.scr, self.P = scr, P
        self.diff = DIFFICULTIES[diff_idx]
        self.level_no = 1
        self.score = 0
        self.display_score = 0
        self.fog = True
        self.new_level()

    # state ---------------------------------------------------------------

    def new_level(self):
        _, cw, ch, radius = self.diff
        cw += (self.level_no - 1) * 3
        ch += (self.level_no - 1) * 2
        self.lv = Level(cw, ch, random.randrange(1 << 30))
        self.px, self.py = self.lv.start
        self.base_radius = radius
        self.boost_until = 0.0
        self.seen = set()
        self.trail = {}
        self.moves = 0
        self.gems = 0
        self.t0 = time.time()
        self.paused_total = 0.0
        self.paused_at = None
        self.shake = self.flash = 0
        self.energy = 0.0
        self.particles = []
        self.msg, self.msg_until = "", 0.0
        self.visible = {}
        self.update_vis(time.time())

    def radius(self, now):
        return self.base_radius + (3 if now < self.boost_until else 0)

    def elapsed(self, now):
        paused = self.paused_total + ((now - self.paused_at) if self.paused_at else 0)
        return now - self.t0 - paused

    def los(self, x1, y1):
        x0, y0 = self.px, self.py
        dx, dy = abs(x1 - x0), -abs(y1 - y0)
        sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
        err, x, y = dx + dy, x0, y0
        while (x, y) != (x1, y1):
            if (x, y) != (x0, y0) and self.lv.wall(x, y):
                return False
            e2 = 2 * err
            if e2 >= dy:
                err += dy
                x += sx
            if e2 <= dx:
                err += dx
                y += sy
        return True

    def update_vis(self, now):
        R = self.radius(now)
        self.vis_radius = R
        self.visible = {}
        for y in range(self.py - R, self.py + R + 1):
            for x in range(self.px - R, self.px + R + 1):
                if not (0 <= x < self.lv.W and 0 <= y < self.lv.H):
                    continue
                d = math.hypot(x - self.px, y - self.py)
                if d <= R and self.los(x, y):
                    self.visible[(x, y)] = d
        self.seen.update(self.visible)

    def say(self, text, now, dur=2.0):
        self.msg, self.msg_until = text, now + dur

    def burst(self, x, y, hue, n=14, text=None):
        for _ in range(n):
            a = random.uniform(0, math.tau)
            sp = random.uniform(2, 7)
            self.particles.append({"x": x + 0.5, "y": y + 0.5, "vx": math.cos(a) * sp,
                                   "vy": math.sin(a) * sp * 0.6, "life": 0.8, "max": 0.8,
                                   "hue": hue + random.uniform(-0.08, 0.08),
                                   "ch": random.choice("✦*+·"), "g": 6})
        if text:
            self.particles.append({"x": x, "y": y - 0.5, "vx": 0, "vy": -2.0, "life": 1.2,
                                   "max": 1.2, "hue": hue, "ch": text, "g": 0})

    def move(self, dx, dy, now):
        nx, ny = self.px + dx, self.py + dy
        if self.lv.wall(nx, ny):
            self.shake, self.flash = 5, 6
            self.energy = min(1.0, self.energy + 0.4)
            self.say(random.choice(["BONK", "OUCH", "THAT'S A WALL", "NOPE", "*thud*"]), now, 0.8)
            return False
        self.px, self.py = nx, ny
        self.moves += 1
        self.trail[(nx, ny)] = now
        self.energy = min(1.0, self.energy + 0.15)
        pos = (nx, ny)
        if pos in self.lv.gems:
            self.lv.gems.discard(pos)
            self.gems += 1
            self.score += 100
            self.energy = 1.0
            self.burst(nx, ny, random.random(), text="+100")
        if pos in self.lv.powers:
            self.lv.powers.discard(pos)
            self.boost_until = now + 12
            self.energy = 1.0
            self.burst(nx, ny, 0.14, n=24, text="SURGE!")
            self.say("✦ TORCH SURGE ✦  light radius +3", now, 2.5)
        self.update_vis(now)
        return pos == self.lv.exit

    # rendering -----------------------------------------------------------

    def star(self, X, Y, mx, my, t):
        hsh = ((mx * 73856093) ^ (my * 19349663)) & 0xFFFF
        if hsh % 31 == 0:
            tw = (math.sin(t * 2 + hsh) + 1) / 2
            ch = "✦" if tw > 0.9 else ("·" if tw > 0.25 else " ")
            put(self.scr, Y, X, ch, self.P.gray(0.15 + tw * 0.5))

    def draw_tile(self, X, Y, mx, my, t, R):
        scr, P, lv = self.scr, self.P, self.lv
        if not (0 <= mx < lv.W and 0 <= my < lv.H):
            self.star(X, Y, mx, my, t)
            return
        pos = (mx, my)
        if pos == (self.px, self.py):
            pulse = (math.sin(t * 8) + 1) / 2
            put(scr, Y, X, "◖◗", P.hue(t * 0.6, 0.7 + 0.3 * pulse) | curses.A_BOLD)
            return
        if self.fog:
            d = self.visible.get(pos)
            lit = d is not None
            f = d / max(1, R) if lit else 1.0
        else:
            lit = True
            f = min(1.0, math.hypot(mx - self.px, my - self.py) / (R * 3))
        is_wall = lv.grid[my][mx] == 1
        if lit:
            if is_wall:
                speed = 0.8 if t < self.boost_until else 0.2
                shade = WALL_SHADES[min(3, int(f * 4))]
                attr = P.hue((mx + my) * 0.035 - t * speed, 1 - f * 0.7)
                put(scr, Y, X, shade, attr | (curses.A_BOLD if f < 0.5 else 0))
            elif pos == lv.exit:
                put(scr, Y, X, PORTAL_FRAMES[int(t * 8) % 4], P.hue(t * 0.8 + 0.5) | curses.A_BOLD)
            elif pos in lv.gems:
                put(scr, Y, X, "◆ ", P.hue(t * 1.5 + mx * 0.1, 1 - f * 0.5) | curses.A_BOLD)
            elif pos in lv.powers:
                blink = int(t * 6) % 2
                put(scr, Y, X, "✦ ", (P.hue(0.14) if blink else P.gray(1.0)) | curses.A_BOLD)
            elif pos in self.trail:
                age = t - self.trail[pos]
                if age < 20:
                    put(scr, Y, X, "• " if age < 2 else "· ",
                        P.hue(self.trail[pos] * 0.15, (1 - age / 20) * (1 - f * 0.5)))
                else:
                    put(scr, Y, X, "· ", P.gray(0.3))
            elif f < 0.3:
                put(scr, Y, X, " ·", P.gray(0.22))
        elif pos in self.seen:
            if is_wall:
                put(scr, Y, X, "░░", P.gray(0.22))
            elif pos == lv.exit:
                put(scr, Y, X, PORTAL_FRAMES[int(t * 3) % 4], P.hue(t * 0.3, 0.45))
            elif pos in lv.gems:
                put(scr, Y, X, "◇ ", P.gray(0.35))
            elif pos in self.trail:
                put(scr, Y, X, "· ", P.gray(0.2))
        else:
            self.star(X, Y, mx, my, t)

    def render(self, now, dt):
        scr, P, lv, t = self.scr, self.P, self.lv, now
        h, w = scr.getmaxyx()
        scr.erase()
        if w < MIN_W or h < MIN_H:
            put(scr, h // 2, max(0, (w - 22) // 2), "terminal too small :(", P.hue(t * 0.5))
            put(scr, h // 2 + 1, max(0, (w - 14) // 2), f"need {MIN_W}×{MIN_H}", P.gray(0.5))
            return

        hud = w >= 72
        map_w = w - (HUD_W + 1 if hud else 0)
        map_top, vh, vw = 1, h - 2, map_w // 2

        def cam(p, size, view):
            if size <= view:
                return -((view - size) // 2)
            return max(0, min(size - view, p - view // 2))

        cx, cy = cam(self.px, lv.W, vw), cam(self.py, lv.H, vh)
        ox = oy = 0
        if self.shake > 0:
            ox, oy = random.randint(-1, 1), random.randint(-1, 1)
            self.shake -= 1

        R = self.radius(now)
        if R != self.vis_radius:
            self.update_vis(now)
        for sy in range(vh):
            for sx in range(vw):
                self.draw_tile(sx * 2 + ox, map_top + sy + oy, cx + sx, cy + sy, t, R)

        # particles live in map coordinates
        alive = []
        for p in self.particles:
            p["life"] -= dt
            if p["life"] <= 0:
                continue
            p["vy"] += p["g"] * dt
            p["x"] += p["vx"] * dt
            p["y"] += p["vy"] * dt
            alive.append(p)
            X = int((p["x"] - cx) * 2) + ox
            Y = map_top + int(p["y"] - cy) + oy
            if 0 <= X < map_w and map_top <= Y < map_top + vh:
                put(scr, Y, X, p["ch"], P.hue(p["hue"], p["life"] / p["max"] + 0.2) | curses.A_BOLD)
        self.particles = alive

        self.draw_topbar(w, t)
        self.draw_marquee(h, w, t, now)
        if hud:
            self.draw_hud(w - HUD_W, 1, h - 2, t, now)

        self.energy *= 0.94
        if self.flash:
            self.flash -= 1
        if self.display_score < self.score:
            self.display_score += max(1, (self.score - self.display_score) // 8)

    def draw_topbar(self, w, t):
        P = self.P
        amp = 0.25 + 0.75 * self.energy
        for i in range(w):
            v = (math.sin(i * 0.31 + t * 5) + math.sin(i * 0.13 - t * 2.7) + 2) / 4 * amp
            attr = P.hue(0.0) if self.flash else P.hue(i / max(1, w) + t * 0.15)
            put(self.scr, 0, i, EQ_BARS[min(7, int(v * 8))], attr)
        title = f" ◆ LEVEL {self.level_no} · {self.diff[0]} ◆ "
        x = (w - len(title)) // 2
        put(self.scr, 0, x, title, P.gray(1.0) | curses.A_BOLD | curses.A_REVERSE)

    def draw_marquee(self, h, w, t, now):
        P = self.P
        if now < self.msg_until:
            text = f"  {self.msg}  "
            x = (w - len(text)) // 2
            hue = 0.0 if self.flash else t * 2
            put(self.scr, h - 1, x, text, P.hue(hue) | curses.A_BOLD | curses.A_REVERSE)
            return
        off = int(t * 12) % len(MARQUEE)
        text = (MARQUEE * (w // len(MARQUEE) + 2))[off:off + w]
        for i, ch in enumerate(text):
            if ch != " ":
                put(self.scr, h - 1, i, ch, P.hue(i * 0.02 - t * 0.3, 0.75))

    def draw_hud(self, x0, top, hh, t, now):
        scr, P, lv = self.scr, self.P, self.lv
        box(scr, P, top, x0, hh, HUD_W, t)
        x, y, iw = x0 + 2, top + 1, HUD_W - 4
        label = P.gray(0.55)
        val = P.gray(1.0) | curses.A_BOLD

        def row(name, value, attr=val):
            nonlocal y
            put(scr, y, x, f"{name:<7}", label)
            put(scr, y, x + 7, value, attr)
            y += 1

        rainbow(scr, P, y, x + (iw - 17) // 2, "N E O N   M A Z E", t, spread=0.05)
        y += 1
        put(scr, y, x, "┄" * iw, P.gray(0.3))
        y += 1
        row("LEVEL", str(self.level_no))
        row("MODE", self.diff[0], P.hue(t * 0.2) | curses.A_BOLD)
        row("TIME", fmt_time(self.elapsed(now)))
        row("MOVES", f"{self.moves}  (par {lv.par})")
        row("SCORE", f"{self.display_score:07d}", P.hue(0.13) | curses.A_BOLD)
        row("GEMS", f"◆ {self.gems}/{lv.total_gems}", P.hue(t) | curses.A_BOLD)
        bar(scr, P, y, x, iw, self.gems / max(1, lv.total_gems), t)
        y += 1
        R = self.radius(now)
        boosted = now < self.boost_until
        put(scr, y, x, f"{'TORCH':<7}", label)
        bar(scr, P, y, x + 7, 12, R / 12, t, full="▰", empty="▱")
        if boosted:
            put(scr, y, x + 20, f"{self.boost_until - now:4.1f}s", P.hue(0.14) | curses.A_BOLD | curses.A_BLINK)
        y += 1
        ex, ey = lv.exit
        ang = math.atan2(ey - self.py, ex - self.px)
        arrow = ARROWS[round(ang / (math.pi / 4)) % 8]
        dist = round(math.hypot(ex - self.px, ey - self.py))
        pulse = (math.sin(t * 6) + 1) / 2
        put(scr, y, x, f"{'EXIT':<7}", label)
        put(scr, y, x + 7, arrow, P.hue(t * 0.8 + 0.5, 0.6 + 0.4 * pulse) | curses.A_BOLD)
        put(scr, y, x + 9, f"{dist} tiles away", val)
        y += 1
        put(scr, y, x, f"{'FOG':<7}", label)
        put(scr, y, x + 7, "ON" if self.fog else "OFF (cheater)", P.gray(0.8) if self.fog else P.hue(0.0))
        y += 1
        put(scr, y, x, "┄" * iw, P.gray(0.3))
        y += 1

        controls = ["←↑↓→ WASD hjkl  move", "F fog · P pause", "R reroll · Q menu"]
        mm_rows = top + hh - 1 - len(controls) - 1 - y - 1
        if mm_rows >= 3:
            put(scr, y, x, "MINIMAP", label)
            y += 1
            self.draw_minimap(x, y, iw, mm_rows, t)
            y += mm_rows
        cy = top + hh - 1 - len(controls)
        put(scr, cy - 1, x, "┄" * iw, P.gray(0.3))
        for i, c in enumerate(controls):
            put(scr, cy + i, x, c, P.gray(0.45))

    def draw_minimap(self, x0, y0, mw, mh, t):
        scr, P, lv = self.scr, self.P, self.lv
        s = max(1, math.ceil(lv.W / (mw * 2)), math.ceil(lv.H / (mh * 4)))
        cols = min(mw, math.ceil(lv.W / s / 2))
        rows = min(mh, math.ceil(lv.H / s / 4))
        xo = x0 + (mw - cols) // 2
        for r in range(rows):
            for c in range(cols):
                bits = 0
                for ddx, ddy, bit in BRAILLE:
                    mx, my = (c * 2 + ddx) * s, (r * 4 + ddy) * s
                    if mx < lv.W and my < lv.H and lv.grid[my][mx] and \
                            (not self.fog or (mx, my) in self.seen):
                        bits |= bit
                if bits:
                    put(scr, y0 + r, xo + c, chr(0x2800 + bits), P.hue(c * 0.03 + r * 0.06 - t * 0.2, 0.7))
        ex, ey = lv.exit
        if not self.fog or lv.exit in self.seen:
            put(scr, y0 + (ey // s) // 4, xo + (ex // s) // 2, "★", P.hue(t) | curses.A_BOLD)
        if int(t * 4) % 2 == 0:
            put(scr, y0 + (self.py // s) // 4, xo + (self.px // s) // 2, "●", P.gray(1.0) | curses.A_BOLD)

    def draw_pause(self, t):
        h, w = self.scr.getmaxyx()
        bw, bh = 30, 7
        y, x = (h - bh) // 2, (w - bw) // 2
        box(self.scr, self.P, y, x, bh, bw, t)
        rainbow(self.scr, self.P, y + 2, x + (bw - 11) // 2, "P A U S E D", t, spread=0.08, speed=1)
        put(self.scr, y + 4, x + (bw - 22) // 2, "P resume  ·  Q to menu", self.P.gray(0.6))

    # loop ----------------------------------------------------------------

    def run(self):
        scr = self.scr
        last = time.time()
        while True:
            now = time.time()
            dt, last = now - last, now
            for k in drain_keys(scr):
                if k in (ord("q"), ord("Q")):
                    return "menu"
                if k in (ord("p"), ord("P")):
                    if self.paused_at:
                        self.paused_total += now - self.paused_at
                        self.paused_at = None
                    else:
                        self.paused_at = now
                    continue
                if self.paused_at:
                    continue
                if k in (ord("f"), ord("F")):
                    self.fog = not self.fog
                    self.say("fog lifted. we saw nothing." if not self.fog else "fog restored", now)
                elif k in (ord("r"), ord("R")):
                    self.new_level()
                    self.say("maze rerolled", now)
                elif k in KEYS and self.move(*KEYS[k], now):
                    if self.celebrate() == "menu":
                        return "menu"
                    self.level_no += 1
                    self.new_level()
                    self.say(f"LEVEL {self.level_no} — the maze grows", time.time(), 3)
                    last = time.time()
                    break
            self.render(now, dt)
            if self.paused_at:
                self.draw_pause(now)
            scr.refresh()
            time.sleep(max(0.0, 1 / FPS - (time.time() - now)))

    def celebrate(self):
        scr, P, lv = self.scr, self.P, self.lv
        end = time.time()
        elapsed = self.elapsed(end)
        time_bonus = max(0, int(1500 - elapsed * 10)) * self.level_no
        path_bonus = max(0, int((2 - self.moves / max(1, lv.par)) * 400))
        gem_bonus = 750 if self.gems == lv.total_gems else 0
        self.score += time_bonus + path_bonus + gem_bonus
        lines = [
            ("TIME", fmt_time(elapsed)),
            ("MOVES", f"{self.moves} / par {lv.par}"),
            ("GEMS", f"{self.gems}/{lv.total_gems}"),
            ("TIME BONUS", f"+{time_bonus}"),
            ("PATH BONUS", f"+{path_bonus}"),
            ("ALL GEMS", f"+{gem_bonus}" if gem_bonus else "—"),
            ("SCORE", None),
        ]
        sparks = []
        next_burst = end
        last = end
        while True:
            now = time.time()
            dt, last = now - last, now
            t, age = now, now - end
            h, w = scr.getmaxyx()
            revealed = int(age / 0.3)
            for k in drain_keys(scr):
                if revealed > len(lines) + 1:
                    if k in ENTER_KEYS:
                        return "next"
                    if k in (ord("q"), ord("Q")):
                        return "menu"
            if now >= next_burst:
                bx, by = random.uniform(4, w - 4), random.uniform(2, h * 0.6)
                hue = random.random()
                for _ in range(36):
                    a = random.uniform(0, math.tau)
                    sp = random.uniform(6, 22)
                    sparks.append([bx, by, math.cos(a) * sp * 2, math.sin(a) * sp, 1.6, hue])
                next_burst = now + random.uniform(0.15, 0.45)

            self.render(now, dt)
            alive = []
            for s in sparks:
                s[4] -= dt
                if s[4] <= 0:
                    continue
                s[3] += 14 * dt
                s[0] += s[2] * dt
                s[1] += s[3] * dt
                s[2] *= 0.97
                alive.append(s)
                life = s[4] / 1.6
                ch = "✦" if life > 0.7 else "*" if life > 0.45 else "+" if life > 0.2 else "·"
                put(scr, int(s[1]), int(s[0]), ch, P.hue(s[5] + random.uniform(-0.05, 0.05), life + 0.2) | curses.A_BOLD)
            sparks = alive

            big_text(scr, P, "CLEAR!", max(1, h // 2 - 11), t)
            bw, bh = 40, len(lines) + 6
            by0, bx0 = max(7, h // 2 - 4), (w - bw) // 2
            box(scr, P, by0, bx0, bh, bw, t)
            put(scr, by0, bx0 + (bw - 16) // 2, f" LEVEL {self.level_no} DONE ", P.gray(1.0) | curses.A_BOLD | curses.A_REVERSE)
            for i, (name, value) in enumerate(lines[:revealed]):
                yy = by0 + 2 + i
                put(scr, yy, bx0 + 3, name, P.gray(0.6))
                if value is None:
                    value = f"{self.display_score:07d}"
                    attr = P.hue(t * 1.5) | curses.A_BOLD
                else:
                    attr = P.gray(1.0) | curses.A_BOLD
                put(scr, yy, bx0 + bw - 3 - len(value), value, attr)
            if revealed > len(lines) + 1:
                hint = "ENTER ▸ next level   Q ▸ menu"
                blink = int(t * 2) % 2
                put(scr, by0 + bh - 2, bx0 + (bw - len(hint)) // 2, hint, P.hue(t, 1.0 if blink else 0.6))
            scr.refresh()
            time.sleep(max(0.0, 1 / FPS - (time.time() - now)))


# ─────────────────────────────── title screen ───────────────────────────────

def title_screen(scr, P, last_score=None):
    stars = [[random.uniform(-1, 1), random.uniform(-1, 1), random.uniform(0.05, 1)] for _ in range(180)]
    sel = 1
    last = time.time()
    while True:
        now = time.time()
        dt, last = now - last, now
        t = now
        for k in drain_keys(scr):
            if k in (curses.KEY_UP, ord("w"), ord("k"), ord("W")):
                sel = (sel - 1) % len(DIFFICULTIES)
            elif k in (curses.KEY_DOWN, ord("s"), ord("j"), ord("S")):
                sel = (sel + 1) % len(DIFFICULTIES)
            elif k in ENTER_KEYS:
                return sel
            elif k in (ord("q"), ord("Q")):
                return None

        h, w = scr.getmaxyx()
        scr.erase()
        cx, cy = w / 2, h / 2
        for s in stars:
            s[2] -= dt * 0.35
            if s[2] <= 0.02:
                s[0], s[1], s[2] = random.uniform(-1, 1), random.uniform(-1, 1), 1.0
            X, Y = int(cx + s[0] / s[2] * cx * 0.5), int(cy + s[1] / s[2] * cy * 0.5)
            if 0 <= X < w and 0 <= Y < h:
                z = s[2]
                ch = "✦" if z < 0.25 else ("•" if z < 0.5 else "·")
                put(scr, Y, X, ch, P.gray(1 - z * 0.8))

        top = max(1, h // 2 - 10)
        big_text(scr, P, "NEON MAZE", top, t)
        sub = "━━━━  a terminal odyssey through living light  ━━━━"
        put(scr, top + 7, (w - len(sub)) // 2, sub, P.hue(t * 0.1 + 0.55, 0.6))

        my = top + 10
        for i, (name, cw, ch, r) in enumerate(DIFFICULTIES):
            if i == sel:
                label = f"▶   {name}   ◀"
                attr = P.hue(t * 0.6 + i * 0.25) | curses.A_BOLD
            else:
                label = name
                attr = P.gray(0.45)
            put(scr, my + i * 2, (w - len(label)) // 2, label, attr)
        name, cw, ch, r = DIFFICULTIES[sel]
        desc = f"{cw}×{ch} maze  ·  torch radius {r}  ·  grows every level"
        put(scr, my + len(DIFFICULTIES) * 2 + 1, (w - len(desc)) // 2, desc, P.gray(0.7))
        if last_score is not None:
            ls = f"last run: {last_score:07d}"
            put(scr, my + len(DIFFICULTIES) * 2 + 2, (w - len(ls)) // 2, ls, P.hue(0.13, 0.8))
        hint = "↑↓ choose  ·  ENTER play  ·  Q quit"
        put(scr, h - 2, (w - len(hint)) // 2, hint, P.gray(0.5 + 0.3 * math.sin(t * 3)))
        scr.refresh()
        time.sleep(max(0.0, 1 / FPS - (time.time() - now)))


def main(scr):
    curses.curs_set(0)
    if curses.has_colors():
        curses.start_color()
        curses.use_default_colors()
    scr.keypad(True)
    scr.nodelay(True)
    P = Palette()
    best = None
    while True:
        d = title_screen(scr, P, best)
        if d is None:
            return best
        g = Game(scr, P, d)
        g.run()
        best = max(best or 0, g.score)


def run():
    best = curses.wrapper(main)
    if best:
        print(f"\n  ◆ NEON MAZE ◆  best score this session: {best:07d}\n")


if __name__ == "__main__":
    run()
