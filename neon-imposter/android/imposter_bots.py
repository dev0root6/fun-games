#!/usr/bin/env python3
"""
Neon Imposter LAN bots — fill a lobby with fake players to test on a single phone.

  phone on USB:   adb forward tcp:47787 tcp:47787 && ./imposter_bots.py --bots 3
  phone on Wi-Fi: ./imposter_bots.py --host 192.168.1.23 --bots 3

Bots ready up, give clues (crew bots give real hints, imposter bots bluff),
chat during discussion, vote, and guess the word when caught.
"""
import argparse
import base64
import os
import random
import re
import socket
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
NAMES = ["NOVA", "BYTE", "ECHO", "PIXEL", "VIPER", "GHOST", "LUNA", "ZED", "MOCHI"]
AVATARS = ["🤖", "👽", "🦊", "🐸", "👾", "🐙", "🦄", "🐼", "🥷"]
VAGUE = ["interesting", "hmm classic", "everyone knows it", "seen it before", "pretty common", "colorful",
         "kinda big", "fun", "popular", "you'd like it"]
CHAT = ["sus", "that clue was weird", "i trust the first guy", "not me!", "who said that??",
        "hmm", "vote the quiet one", "trust me bro", "that's exactly what an imposter says"]
HINTS = {
    "FOOD": ["tasty", "dinner", "snack", "delicious", "kitchen", "eat it"],
    "ANIMALS": ["wild", "zoo", "fur", "cute", "nature doc", "pet?"],
    "PLACES": ["visit", "travel", "building", "people go", "trip", "location"],
    "OBJECTS": ["useful", "at home", "hold it", "thing", "everyday", "tool"],
    "JOBS": ["career", "uniform", "salary", "work", "skills", "boss"],
    "SPORTS": ["team", "score", "olympics", "ball?", "athlete", "match"],
    "TECH": ["digital", "screen", "internet", "gadget", "battery", "modern"],
    "NATURE": ["outdoors", "earth", "beautiful", "weather", "wild", "sky"],
}


def load_words():
    """Read the word bank out of Words.java so the bots can bluff guesses."""
    src = open(os.path.join(HERE, "src/com/neonimposter/Words.java"), encoding="utf-8").read()
    cats = re.findall(r'"([A-Z]+)"', src.split("WORDS")[0])
    blocks = re.findall(r"\{([^{}]*)\}", src.split("WORDS =")[1])
    return {c: re.findall(r'"([^"]+)"', b) for c, b in zip(cats, blocks)}


WORDS = load_words()


def enc(s):
    return base64.urlsafe_b64encode(s.encode()).decode().rstrip("=")


def dec(s):
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4)).decode(errors="replace")


class Bot(threading.Thread):
    def __init__(self, host, port, name, avatar, hue, smart):
        super().__init__(daemon=True)
        self.host, self.port = host, port
        self.name, self.avatar, self.hue, self.smart = name, avatar, hue, smart
        self.id = None
        self.players = {}
        self.imp = False
        self.cat = self.word = ""
        self.phase = "LOBBY"
        self.clues = {}
        self.lock = threading.Lock()

    def send(self, line):
        with self.lock:
            self.sock.sendall((line + "\n").encode())

    def later(self, lo, hi, fn):
        threading.Timer(random.uniform(lo, hi), fn).start()

    def log(self, *a):
        print(f"[{self.name}]", *a, flush=True)

    def run(self):
        self.sock = socket.create_connection((self.host, self.port), timeout=5)
        self.sock.settimeout(None)
        self.send(f"HELLO|{enc(self.name)}|{enc(self.avatar)}|{self.hue}")
        for line in self.sock.makefile("r", encoding="utf-8"):
            try:
                self.on(line.rstrip("\n").split("|"))
            except Exception as e:  # keep the bot alive through odd messages
                self.log("error handling", line.strip(), e)
        self.log("disconnected")

    def clue(self):
        if self.imp:
            pool = HINTS.get(self.cat, VAGUE) + VAGUE if self.cat else VAGUE
            return random.choice(pool)
        if self.smart:
            return random.choice(HINTS.get(self.cat, VAGUE))
        return random.choice(HINTS.get(self.cat, VAGUE) + VAGUE)

    def on(self, f):
        cmd = f[0]
        if cmd == "WELCOME":
            self.id = int(f[1])
            self.log("joined as", self.id)
        elif cmd == "FULL":
            self.log("game full / already running")
        elif cmd == "LOBBY":
            self.players = {int(e.split(",")[0]): dec(e.split(",")[1]) for e in f[2].split(";") if e}
        elif cmd == "ROLE":
            self.imp = f[1] == "1"
            self.cat, self.word = dec(f[2]), dec(f[3])
            self.clues = {}
            self.log("IMPOSTER" if self.imp else f"crew — word: {self.word}", f"({self.cat or 'no hint'})")
        elif cmd == "CLUE":
            self.clues.setdefault(int(f[1]), []).append(dec(f[3]))
        elif cmd == "PHASE":
            self.phase, a = f[1], int(f[3])
            if self.phase == "REVEAL":
                self.later(2, 6, lambda: self.send("READY"))
            elif self.phase == "CLUE" and a == self.id:
                c = self.clue()
                self.later(2, 5, lambda: self.send(f"CLUE|{enc(c)}"))
            elif self.phase == "DISCUSS" and random.random() < 0.7:
                msg = random.choice(CHAT)
                self.later(3, 12, lambda: self.send(f"CHAT|{enc(msg)}"))
            elif self.phase == "VOTE":
                others = [i for i in self.players if i != self.id]
                target = random.choice(others) if others and random.random() > 0.1 else -1
                self.later(3, 10, lambda: self.send(f"VOTE|{target}"))
            elif self.phase == "GUESS" and a == self.id:
                pool = WORDS.get(self.cat) or [w for ws in WORDS.values() for w in ws]
                g = random.choice(pool)
                self.later(3, 7, lambda: self.send(f"GUESS|{enc(g)}"))
        elif cmd == "OVER":
            self.log(("IMPOSTER" if f[1] == "1" else "CREW"), "wins — word was", dec(f[2]), "|", dec(f[5]))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=47787)
    ap.add_argument("--bots", type=int, default=3)
    ap.add_argument("--dumb", action="store_true", help="crew bots give vaguer clues")
    a = ap.parse_args()
    bots = []
    for i in range(max(1, min(9, a.bots))):
        b = Bot(a.host, a.port, NAMES[i], AVATARS[i], (i * 40) % 360, not a.dumb)
        b.start()
        bots.append(b)
        time.sleep(0.3)
    print(f"{len(bots)} bots running — start the game on the phone. Ctrl+C to quit.")
    try:
        while any(b.is_alive() for b in bots):
            time.sleep(0.5)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
