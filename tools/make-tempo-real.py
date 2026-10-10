#!/usr/bin/env python3
"""Pick audio files whose names state their tempo (e.g. "Loop 140bpm [Am].wav") from a sample library, decode copies to
mono WAV under app/build/fixtures/tempo-real/ and write manifest.json (file -> labelled BPM). The library is only read.
usage: make-tempo-real.py <library dir> [count] [seed] [subdir] [exclude-manifest.json]"""
import os, re, sys, json, random, subprocess
lib = sys.argv[1]; count = int(sys.argv[2]) if len(sys.argv) > 2 else 80; seed = int(sys.argv[3]) if len(sys.argv) > 3 else 1
sub = sys.argv[4] if len(sys.argv) > 4 else "tempo-real"
out = os.path.join(os.path.dirname(__file__), "..", "app", "build", "fixtures", sub)
exclude = set()
if len(sys.argv) > 5: exclude = {x["src"] for x in json.load(open(sys.argv[5]))}
os.makedirs(out, exist_ok=True)
pat = re.compile(r"(?<![\d.])(\d{2,3}(?:\.\d+)?)\s*-?\s*bpm", re.I)
cand = []
for root, _, files in os.walk(lib):
    for f in files:
        if f.startswith("._") or not f.lower().endswith((".wav", ".mp3", ".flac", ".aif", ".aiff", ".m4a", ".ogg")): continue
        ms = pat.findall(f)
        vals = {float(m) for m in ms}
        if len(vals) != 1: continue
        b = vals.pop()
        if 55 <= b <= 220 and os.path.relpath(os.path.join(root, f), lib) not in exclude: cand.append((os.path.join(root, f), b))
random.Random(seed).shuffle(cand)
print(len(cand), "candidates")
manifest = []
for path, b in cand:
    if len(manifest) >= count: break
    name = "r%03d.wav" % len(manifest)
    r = subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", path, "-ac", "1", "-ar", "44100", "-t", "180", os.path.join(out, name)], capture_output=True)
    if r.returncode != 0: continue
    dur = os.path.getsize(os.path.join(out, name)) / (44100 * 2)
    if dur < 3: os.remove(os.path.join(out, name)); continue
    manifest.append({"file": name, "bpm": b, "src": os.path.relpath(path, lib), "dur": round(dur, 2)})
json.dump(manifest, open(os.path.join(out, "manifest.json"), "w"), indent=1, ensure_ascii=False)
print("wrote", len(manifest))
