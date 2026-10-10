#!/usr/bin/env python3
"""Pick MIDI files from a library whose path says what they are (drum / hat / kick ... vs melody / chord / bass ...) and write
app/build/fixtures/midi-list.tsv (label<TAB>path). The files are only read."""
import os, re, sys, random
lib = sys.argv[1]; per = int(sys.argv[2]) if len(sys.argv) > 2 else 150
drum = re.compile(r"drum|hi-?hat|hat |hats|kick|snare|perc|beat", re.I)
tone = re.compile(r"melod|chord|bass|lead|arp|progression|piano|keys|pluck|pad|synth", re.I)
D, T = [], []
for root, _, files in os.walk(lib):
    for f in files:
        if f.startswith("._") or not f.lower().endswith((".mid", ".midi")): continue
        p = os.path.join(root, f); rel = os.path.relpath(p, lib)
        d, t = bool(drum.search(rel)), bool(tone.search(rel))
        if d and not t: D.append(p)
        elif t and not d: T.append(p)
r = random.Random(4); r.shuffle(D); r.shuffle(T)
out = os.path.join(os.path.dirname(__file__), "..", "app", "build", "fixtures", "midi-list.tsv")
with open(out, "w") as fh:
    for p in D[:per]: fh.write("drum\t%s\n" % p)
    for p in T[:per]: fh.write("tonal\t%s\n" % p)
print(len(D), "drum-like,", len(T), "tonal-like candidates; wrote", min(per, len(D)) + min(per, len(T)))
