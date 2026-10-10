#!/bin/bash
# Hashes files of edge-case lengths on the phone (debug hook `vx_hash`) with every algorithm and compares each value with
# xxhsum / md5 / shasum / python on this Mac. Needs the debug app installed and one phone on adb.
set -euo pipefail
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}; PKG=app.feldkit
D=$(mktemp -d); trap 'rm -rf "$D"' EXIT
python3 - "$D" <<'PY'
import os, random, sys
d = sys.argv[1]; r = random.Random(5)
for n in (0,1,2,3,4,5,8,9,15,16,17,31,32,33,63,64,65,127,128,129,191,192,240,241,255,256,257,511,512,1023,1024,1025,2047,2048,4096,65535,65536,1048576,1048577,17000001):
    open(f"{d}/f{n}.bin", "wb").write(bytes(r.getrandbits(8) for _ in range(min(n, 1 << 16))) + bytes(max(0, n - (1 << 16))))
PY
$ADB shell "run-as $PKG mkdir -p files/h"
for f in "$D"/*.bin; do b=$(basename "$f"); $ADB push "$f" /data/local/tmp/$b >/dev/null; $ADB shell "run-as $PKG cp /data/local/tmp/$b files/h/$b; rm /data/local/tmp/$b"; done
$ADB logcat -c
for f in "$D"/*.bin; do b=$(basename "$f"); $ADB shell "am start -n $PKG/app.feldkit.MainActivity --activity-single-top --es vx_hash /data/data/$PKG/files/h/$b" >/dev/null 2>&1; sleep 0.7; done
sleep 3; $ADB logcat -d -s FeldKitHash:I > "$D/log.txt"
python3 - "$D" <<'PY'
import re, subprocess, hashlib, zlib, sys
d = sys.argv[1]
blocks = []; cur = {}
for l in open(d + "/log.txt"):
    m = re.search(r"FeldKitHash: (\w+)=(\S+)", l)
    if m: cur[m.group(1)] = m.group(2); continue
    m = re.search(r"done (\d+) bytes", l)
    if m: cur["size"] = int(m.group(1)); blocks.append(cur); cur = {}
B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
def c4(b):
    n = int.from_bytes(hashlib.sha512(b).digest(), "big"); s = ""
    while n: n, r = divmod(n, 58); s = B58[r] + s
    return "c4" + s.rjust(88, "1")
def xs(flag, p): return subprocess.run(["xxhsum", flag, p], capture_output=True, text=True).stdout.split()[0].replace("XXH3_", "")
ok = bad = 0
for b in blocks:
    p = f"{d}/f{b['size']}.bin"; data = open(p, "rb").read()
    exp = {"XXH64": xs("-H1", p), "XXH3_128": xs("-H2", p), "XXH3_64": xs("-H3", p), "MD5": hashlib.md5(data).hexdigest(), "SHA1": hashlib.sha1(data).hexdigest(),
           "SHA256": hashlib.sha256(data).hexdigest(), "SHA512": hashlib.sha512(data).hexdigest(), "C4": c4(data), "CRC32": "%08x" % (zlib.crc32(data) & 0xffffffff)}
    for k, v in exp.items():
        if b.get(k) == v: ok += 1
        else: bad += 1; print("MISMATCH", b["size"], k, b.get(k), v)
print("files", len(blocks), "checks ok", ok, "bad", bad)
sys.exit(1 if bad or not blocks else 0)
PY
