#!/usr/bin/env python3
"""Synthetic tracks with an exactly known tempo, for TempoTest. Writes mono 16-bit WAVs into app/build/fixtures/tempo/.

Each track has kick / snare / hats (optionally swung or humanised), a bass line and a sustained pad, so the detector has
to find the pulse among non-percussive material too."""
import numpy as np, wave, os, sys, json
SR = 44100
rng = np.random.default_rng(7)
out = os.path.join(os.path.dirname(__file__), "..", "app", "build", "fixtures", "tempo")
os.makedirs(out, exist_ok=True)

def kick(n=0.35):
    t = np.arange(int(SR * n)) / SR
    f = 45 + 110 * np.exp(-t * 28)
    ph = 2 * np.pi * np.cumsum(f) / SR
    return np.sin(ph) * np.exp(-t * 11) + 0.4 * np.exp(-t * 400) * rng.standard_normal(len(t))
def snare(n=0.25):
    t = np.arange(int(SR * n)) / SR
    noise = rng.standard_normal(len(t))
    noise = noise - np.convolve(noise, np.ones(8) / 8, mode="same")
    return 0.6 * np.sin(2 * np.pi * 190 * t) * np.exp(-t * 28) + 0.7 * noise * np.exp(-t * 20)
def hat(n=0.06, open_=False):
    t = np.arange(int(SR * (0.3 if open_ else n))) / SR
    noise = rng.standard_normal(len(t))
    noise = noise - np.convolve(noise, np.ones(4) / 4, mode="same")
    return 0.35 * noise * np.exp(-t * (14 if open_ else 70))

K, S, H, HO = kick(), snare(), hat(), hat(open_=True)

def place(buf, samp, t, g=1.0):
    i = int(round(t * SR))
    if i < 0 or i >= len(buf): return
    n = min(len(samp), len(buf) - i)
    buf[i:i + n] += g * samp[:n]

def track(bpm, seconds=70, pattern="four", swing=0.0, jitter_ms=0.0, name=None, noise=0.0, pad=True):
    n = int(SR * seconds)
    buf = np.zeros(n)
    beat = 60.0 / bpm
    nb = int(seconds / beat) + 2
    for b in range(nb):
        t = b * beat
        j = lambda: rng.normal(0, jitter_ms / 1000.0) if jitter_ms else 0.0
        bar_pos = b % 4
        if pattern == "four":        # kick every beat, snare 2 & 4, 8th hats
            place(buf, K, t + j()); 
            if bar_pos in (1, 3): place(buf, S, t + j())
        elif pattern == "boombap":   # kick 1, 2.5(and), snare 2 & 4
            if bar_pos == 0: place(buf, K, t + j())
            if bar_pos == 2: place(buf, K, t + 0.5 * beat + j())
            if bar_pos in (1, 3): place(buf, S, t + j())
        elif pattern == "halftime":  # kick 1, snare 3 (in beats of the half-time feel)
            if bar_pos == 0: place(buf, K, t + j())
            if bar_pos == 2: place(buf, S, t + j())
        # hats on 8ths (swung off-beats)
        place(buf, H, t + j(), 0.8)
        place(buf, HO if bar_pos == 3 else H, t + (0.5 + swing * 0.25) * beat + j(), 0.6)
    # bass note per bar (sine+harmonic) and a pad chord
    bar = beat * 4
    notes = [43, 43, 46, 41]
    for k in range(int(seconds / bar) + 1):
        m = notes[k % 4]; f = 440 * 2 ** ((m - 69) / 12)
        i0 = int(k * bar * SR); L = min(int(bar * SR * 0.9), n - i0)
        if L <= 0: continue
        t = np.arange(L) / SR
        env = np.minimum(1, t * 80) * np.exp(-t * 1.2)
        buf[i0:i0 + L] += 0.35 * (np.sin(2 * np.pi * f * t) + 0.3 * np.sin(4 * np.pi * f * t)) * env
        if pad:
            for ch in (0, 4, 7, 11):
                fp = 440 * 2 ** ((m + 24 + ch - 69) / 12)
                buf[i0:i0 + L] += 0.04 * np.sin(2 * np.pi * fp * t) * np.minimum(1, t * 3) * np.minimum(1, (L / SR - t) * 8)
    if noise: buf += noise * rng.standard_normal(n)
    buf /= max(1e-9, np.abs(buf).max()) / 0.85
    name = name or f"t_{bpm:.2f}_{pattern}"
    with wave.open(os.path.join(out, name + ".wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((buf * 32767).astype("<i2").tobytes())
    return name

cases = []
def add(**kw):
    nm = track(**kw); cases.append({"file": nm + ".wav", "bpm": kw["bpm"], "pattern": kw.get("pattern", "four"), "swing": kw.get("swing", 0), "jitter": kw.get("jitter_ms", 0)})

for b in (70.0, 87.5, 93.37, 100.0, 110.0, 120.0, 127.35, 128.0, 135.5, 140.0, 150.01):
    add(bpm=b, pattern="four")
for b in (84.0, 90.0, 96.5): add(bpm=b, pattern="boombap")
add(bpm=127.35, pattern="four", swing=0.5, name="t_127.35_swing")
add(bpm=100.0, pattern="boombap", jitter_ms=4.0, name="t_100_human")
add(bpm=122.0, pattern="four", jitter_ms=3.0, noise=0.05, name="t_122_noisy")
add(bpm=145.0, pattern="halftime", name="t_145_halftime")
add(bpm=174.0, pattern="boombap", name="t_174_dnb")
json.dump(cases, open(os.path.join(out, "cases.json"), "w"), indent=1)
# a beatless pad (must give no tempo)
n = SR * 60; t = np.arange(n) / SR
pad = sum(np.sin(2 * np.pi * 220 * 2 ** (c / 12) * t) for c in (0, 4, 7)) * 0.2
pad = pad * (0.7 + 0.3 * np.sin(2 * np.pi * 0.05 * t))
with wave.open(os.path.join(out, "beatless_pad.wav"), "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes((pad / np.abs(pad).max() * 0.8 * 32767).astype("<i2").tobytes())
print("wrote", len(cases) + 1, "files to", os.path.abspath(out))
