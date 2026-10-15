package com.neonimposter;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Tiny realtime synth: a step sequencer playing synthwave loops plus sound effects.
 * Everything is generated in code — no audio assets.
 */
final class Audio implements Runnable {
    static final int MENU = 1, SUS = 2, VOTE = 3;
    static final int CLICK = 0, REVEAL = 1, TICK = 2, CLUE = 3, TURN = 4, VOTED = 5, EJECT = 6, STING = 7,
            WIN_CREW = 8, WIN_IMP = 9, JOIN = 10, ERR = 11, POP = 12, CHAT = 13, SAFE = 14;

    private static final int SR = 44100;
    private static final int SQUARE = 0, SAW = 1, TRI = 2, NOISE = 3, SINE = 4;

    // menu: A minor synthwave  Am – F – C – G
    private static final int[][] CHORDS = {{57, 60, 64}, {53, 57, 60}, {55, 60, 64}, {55, 59, 62}};
    private static final int[] BASS = {45, 41, 48, 43};
    // in-game: D minor suspense  Dm – Bb – Gm – A
    private static final int[][] SUS_CHORDS = {{62, 65, 69}, {58, 62, 65}, {55, 58, 62}, {57, 61, 64}};
    private static final int[] SUS_BASS = {38, 34, 43, 45};
    private static final int[] ARP = {0, 1, 2, 3, 2, 1, 3, 4};

    private static final class Voice {
        boolean on, sfx;
        int wave;
        float freq, freqEnd, amp, att, decMul, len, t, env, pw, lp, lpA;
        double phase;
        boolean vib;
    }

    private static final class Pending {
        int delay;
        boolean sfx;
        int wave;
        float freq, freqEnd, amp, att, dec, len, cutoff, pw;
    }

    volatile boolean musicOn = true, sfxOn = true;
    private volatile int wantSong = MENU;
    private int song;

    private final Voice[] voices = new Voice[48];
    private final ConcurrentLinkedQueue<Integer> sfxQueue = new ConcurrentLinkedQueue<>();
    private final ArrayList<Pending> pending = new ArrayList<>();
    private int step;
    private float stepLen, stepAcc;
    private int noise = 0x1234567;

    private AudioTrack track;
    private Thread thread;
    private volatile boolean running;

    Audio() {
        for (int i = 0; i < voices.length; i++) voices[i] = new Voice();
    }

    void song(int s) {
        wantSong = s;
    }

    void sfx(int id) {
        if (sfxOn) sfxQueue.add(id);
    }

    synchronized void start() {
        if (running) return;
        try {
            int min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SR)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(Math.max(min, 4096))
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            track.play();
        } catch (Exception e) {
            track = null;
            return;
        }
        running = true;
        thread = new Thread(this, "neon-audio");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    synchronized void stop() {
        running = false;
        if (thread != null) {
            try {
                thread.join(500);
            } catch (InterruptedException ignored) {
            }
            thread = null;
        }
        if (track != null) {
            try {
                track.pause();
                track.flush();
                track.release();
            } catch (Exception ignored) {
            }
            track = null;
        }
    }

    @Override
    public void run() {
        short[] buf = new short[256];
        while (running) {
            Integer id;
            while ((id = sfxQueue.poll()) != null) playSfx(id);
            if (!musicOn) for (Voice v : voices) if (!v.sfx) v.on = false;
            for (int i = 0; i < buf.length; i++) {
                sequencer();
                float s = mix() * 0.55f;
                if (s > 1f) s = 1f;
                else if (s < -1f) s = -1f;
                buf[i] = (short) (s * 32767);
            }
            AudioTrack tr = track;
            if (tr == null || tr.write(buf, 0, buf.length) < 0) break;
        }
    }

    // ── sequencer ─────────────────────────────────────────────────────────

    private void sequencer() {
        if (wantSong != song) {
            song = wantSong;
            step = 0;
            stepAcc = 0;
            for (Voice v : voices) if (!v.sfx) v.on = false;
            stepLen = SR * 60f / (song == VOTE ? 126 : song == SUS ? 92 : 88) / 4f;
            onStep(step++);
        }
        for (int i = pending.size() - 1; i >= 0; i--) {
            Pending p = pending.get(i);
            if (--p.delay <= 0) {
                pending.remove(i);
                note(p.sfx, p.wave, p.freq, p.freqEnd, p.amp, p.att, p.dec, p.len, p.cutoff, p.pw, false);
            }
        }
        if (song == 0) return;
        stepAcc += 1;
        if (stepAcc >= stepLen) {
            stepAcc -= stepLen;
            onStep(step++);
        }
    }

    private void onStep(int i) {
        if (!musicOn || song == 0) return;
        int s = i % 16, bar = (i / 16) % 8, chord = bar % 4;
        float stepSec = stepLen / SR, barSec = stepSec * 16;
        if (song == MENU) {
            int[] tones = CHORDS[chord];
            if (s == 0) pad(tones, barSec, 0.07f, 1600, 0.7f);
            if (s == 0 || s == 8) {
                float f = hz(BASS[chord]);
                note(false, SAW, f, f, 0.26f, 0.02f, 0.9f, stepSec * 7.5f, 500, 0.5f, false);
            }
            if (s % 2 == 0) {
                int a = ARP[(s / 2) % 8];
                int m = tones[a % 3] + 12 * (a / 3) + 12;
                note(false, TRI, hz(m), hz(m), 0.09f, 0.004f, 0.25f, stepSec * 1.8f, 2600, 0.5f, false);
            }
            if (s % 4 == 2) note(false, NOISE, 0, 0, 0.03f, 0.001f, 0.02f, 0.04f, 14000, 0.5f, false);
            if (s == 14 && bar % 2 == 1) {
                int m = tones[2] + 24;
                note(false, TRI, hz(m), hz(m), 0.06f, 0.005f, 0.5f, 0.8f, 6000, 0.5f, false);
            }
            return;
        }
        int[] tones = SUS_CHORDS[chord];
        float bass = hz(SUS_BASS[chord]);
        if (song == SUS) {
            // heartbeat: lub-dub
            if (s == 0 || s == 3) note(false, SINE, s == 0 ? 95 : 80, 40, s == 0 ? 0.8f : 0.55f, 0.002f, 0.12f, 0.22f, 3000, 0.5f, false);
            if (s == 0) {
                pad(tones, barSec, 0.05f, 900, 0.9f);
                note(false, SAW, bass, bass, 0.22f, 0.3f, 2.5f, barSec, 380, 0.5f, false);
            }
            if (s % 4 == 2) note(false, NOISE, 0, 0, 0.035f, 0.001f, 0.02f, 0.04f, 14000, 0.5f, false);
            if (s % 3 == 0 && s < 15) {
                int a = ARP[(s / 3) % 8];
                int m = tones[a % 3] + 12 * (a / 3) + 12;
                note(false, TRI, hz(m), hz(m), 0.08f, 0.003f, 0.18f, stepSec * 2.5f, 2400, 0.5f, false);
            }
            if (s == 10 && bar % 2 == 1) {
                int m = tones[1] + 24;
                note(false, SINE, hz(m), hz(m) * 0.99f, 0.07f, 0.01f, 0.9f, 1.2f, 5000, 0.5f, true);
            }
        } else { // VOTE — driving tension
            if (s % 4 == 0) note(false, SINE, 140, 42, 0.85f, 0.001f, 0.12f, 0.24f, 8000, 0.5f, false);
            if (s == 4 || s == 12) note(false, NOISE, 0, 0, 0.28f, 0.001f, 0.07f, 0.18f, 7000, 0.5f, false);
            note(false, NOISE, 0, 0, s % 2 == 1 ? 0.05f : 0.025f, 0.001f, 0.015f, 0.03f, 14000, 0.5f, false);
            if (s % 2 == 1) note(false, SAW, bass * 2, bass * 2, 0.24f, 0.002f, 0.1f, stepSec * 0.9f, 800, 0.5f, false);
            int m = tones[s % 3] + 12;
            note(false, SQUARE, hz(m), hz(m), 0.055f, 0.002f, 0.06f, stepSec * 0.7f, 3000, 0.3f, false);
            if (s == 0) pad(tones, barSec, 0.035f, 1100, 0.2f);
        }
    }

    private void pad(int[] tones, float len, float amp, float cutoff, float att) {
        for (int tn : tones) {
            float f = hz(tn);
            note(false, SAW, f * 0.997f, f * 0.997f, amp, att, 3f, len, cutoff, 0.5f, false);
            note(false, SAW, f * 1.003f, f * 1.003f, amp, att, 3f, len, cutoff, 0.5f, false);
        }
    }

    // ── sound effects ─────────────────────────────────────────────────────

    private void playSfx(int id) {
        switch (id) {
            case CLICK:
                note(true, TRI, 1400, 1400, 0.14f, 0.001f, 0.02f, 0.035f, 8000, 0.5f, false);
                break;
            case REVEAL:
                note(true, NOISE, 0, 0, 0.12f, 0.08f, 0.2f, 0.35f, 2500, 0.5f, false);
                note(true, SINE, 200, 900, 0.18f, 0.01f, 0.3f, 0.35f, 4000, 0.5f, false);
                break;
            case TICK:
                note(true, SQUARE, 1800, 1800, 0.07f, 0.001f, 0.02f, 0.03f, 6000, 0.5f, false);
                break;
            case CLUE:
                later(0f, SQUARE, hz(79), hz(79), 0.1f, 0.08f, 0.1f, 5000, 0.25f);
                later(0.05f, SQUARE, hz(84), hz(84), 0.1f, 0.12f, 0.15f, 5000, 0.25f);
                break;
            case TURN: {
                int[] arp = {72, 76, 79, 84};
                for (int i = 0; i < arp.length; i++) later(i * 0.06f, SQUARE, hz(arp[i]), hz(arp[i]), 0.12f, 0.15f, 0.2f, 6000, 0.5f);
                break;
            }
            case VOTED:
                note(true, SINE, 300, 120, 0.3f, 0.001f, 0.06f, 0.1f, 3000, 0.5f, false);
                break;
            case EJECT:
                note(true, SINE, 120, 30, 0.9f, 0.002f, 0.4f, 0.8f, 2000, 0.5f, false);
                note(true, NOISE, 0, 0, 0.25f, 0.002f, 0.3f, 0.6f, 1500, 0.5f, false);
                break;
            case STING: // "dun dun DUNN"
                later(0f, SAW, hz(38), hz(38), 0.35f, 0.25f, 0.3f, 900, 0.5f);
                later(0.32f, SAW, hz(39), hz(39), 0.35f, 0.25f, 0.3f, 900, 0.5f);
                later(0.64f, SAW, hz(34), hz(34), 0.45f, 1.0f, 1.4f, 1100, 0.5f);
                later(0.64f, SAW, hz(46), hz(46), 0.25f, 1.0f, 1.4f, 1500, 0.5f);
                break;
            case SAFE:
                later(0f, TRI, hz(67), hz(67), 0.16f, 0.2f, 0.25f, 4000, 0.5f);
                later(0.18f, TRI, hz(72), hz(72), 0.16f, 0.4f, 0.5f, 4000, 0.5f);
                break;
            case WIN_CREW: {
                int[] arp = {69, 72, 76, 81, 84, 88};
                for (int i = 0; i < arp.length; i++) {
                    boolean last = i == arp.length - 1;
                    later(i * 0.08f, SQUARE, hz(arp[i]), hz(arp[i]), 0.13f, last ? 0.6f : 0.2f, last ? 0.9f : 0.2f, 6000, 0.5f);
                }
                later(0.45f, NOISE, 0, 0, 0.12f, 0.5f, 1.0f, 9000, 0.5f);
                break;
            }
            case WIN_IMP: {
                int[] arp = {74, 73, 70, 69, 62};
                for (int i = 0; i < arp.length; i++) {
                    boolean last = i == arp.length - 1;
                    later(i * 0.16f, SAW, hz(arp[i]), hz(arp[i]) * (last ? 0.97f : 1f), 0.16f, last ? 1.0f : 0.25f,
                            last ? 1.3f : 0.3f, 2200, 0.5f);
                }
                later(0.64f, SINE, 60, 30, 0.6f, 0.8f, 1.2f, 1500, 0.5f);
                break;
            }
            case JOIN:
                later(0f, SQUARE, hz(76), hz(76), 0.12f, 0.1f, 0.12f, 5000, 0.5f);
                later(0.09f, SQUARE, hz(83), hz(83), 0.12f, 0.2f, 0.25f, 5000, 0.5f);
                break;
            case ERR:
                note(true, SQUARE, 140, 120, 0.18f, 0.002f, 0.2f, 0.25f, 1500, 0.5f, false);
                break;
            case POP:
                note(true, NOISE, 0, 0, 0.09f, 0.001f, 0.12f, 0.25f, 3000, 0.5f, false);
                note(true, SINE, 90, 45, 0.18f, 0.001f, 0.1f, 0.2f, 2000, 0.5f, false);
                break;
            case CHAT:
                note(true, TRI, 1100, 1300, 0.08f, 0.002f, 0.05f, 0.07f, 6000, 0.5f, false);
                break;
            default:
                break;
        }
    }

    private void later(float sec, int wave, float f, float fe, float amp, float dec, float len, float cutoff, float pw) {
        Pending p = new Pending();
        p.delay = Math.max(1, (int) (sec * SR));
        p.sfx = true;
        p.wave = wave;
        p.freq = f;
        p.freqEnd = fe;
        p.amp = amp;
        p.att = 0.002f;
        p.dec = dec;
        p.len = len;
        p.cutoff = cutoff;
        p.pw = pw;
        pending.add(p);
    }

    // ── synthesis ─────────────────────────────────────────────────────────

    private void note(boolean sfx, int wave, float freq, float freqEnd, float amp, float att, float dec, float len,
                      float cutoff, float pw, boolean vib) {
        if (sfx && !sfxOn) return;
        Voice v = null;
        float oldest = -1;
        for (Voice c : voices) {
            if (!c.on) {
                v = c;
                break;
            }
            if (c.t > oldest) {
                oldest = c.t;
                v = c;
            }
        }
        v.on = true;
        v.sfx = sfx;
        v.wave = wave;
        v.freq = freq;
        v.freqEnd = freqEnd;
        v.amp = amp;
        v.att = Math.max(0.001f, att);
        v.decMul = (float) Math.exp(-1.0 / (SR * Math.max(0.001f, dec)));
        v.len = len;
        v.t = 0;
        v.env = 0;
        v.pw = pw;
        v.lp = 0;
        v.lpA = (float) (1 - Math.exp(-2 * Math.PI * cutoff / SR));
        v.phase = 0;
        v.vib = vib;
    }

    private float mix() {
        float out = 0, dt = 1f / SR;
        for (Voice v : voices) {
            if (!v.on) continue;
            v.t += dt;
            if (v.t >= v.len) {
                v.on = false;
                continue;
            }
            if (v.t < v.att) v.env = v.t / v.att;
            else v.env *= v.decMul;
            float env = v.env;
            float rem = v.len - v.t;
            if (rem < 0.008f) env *= rem / 0.008f;
            float s;
            if (v.wave == NOISE) {
                noise ^= noise << 13;
                noise ^= noise >>> 17;
                noise ^= noise << 5;
                s = (noise & 0xFFFF) / 32768f - 1f;
            } else {
                float f = v.freq + (v.freqEnd - v.freq) * (v.t / v.len);
                if (v.vib && v.t > 0.15f) f *= 1f + 0.007f * (float) Math.sin(v.t * 34.5f);
                v.phase += f / SR;
                if (v.phase >= 1) v.phase -= 1;
                float ph = (float) v.phase;
                switch (v.wave) {
                    case SQUARE:
                        s = ph < v.pw ? 0.8f : -0.8f;
                        break;
                    case SAW:
                        s = 2 * ph - 1;
                        break;
                    case TRI:
                        s = 4 * Math.abs(ph - 0.5f) - 1;
                        break;
                    default:
                        s = (float) Math.sin(ph * 6.2831853f);
                        break;
                }
            }
            v.lp += v.lpA * (s - v.lp);
            out += v.lp * env * v.amp * (v.sfx ? 1f : 0.8f);
        }
        return out;
    }

    private static float hz(int midi) {
        return 440f * (float) Math.pow(2, (midi - 69) / 12.0);
    }
}
