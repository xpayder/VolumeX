#!/bin/bash
# Rock Ridge + Joliet ISO and a larger multi-extent test with xorriso (inside Docker, ubuntu:24.04). Writes iso-rr.iso + iso-rr.manifest.
set -euo pipefail
OUT="${1:-$(cd "$(dirname "$0")/.." && pwd)/app/build/fixtures}"; mkdir -p "$OUT"
SRC=$(mktemp -d); trap 'rm -rf "$SRC"' EXIT
mkdir -p "$SRC/Photos/2026/Trip" "$SRC/Docs" "$SRC/empty dir"
printf 'Hello FeldKit ISO\n' > "$SRC/hello.txt"; : > "$SRC/empty.txt"
head -c 5242880 /dev/urandom > "$SRC/big.bin"; head -c 4097 /dev/urandom > "$SRC/odd4097.bin"
printf 'deep\n' > "$SRC/Photos/2026/Trip/deep file with spaces.txt"
printf 'unicode\n' > "$SRC/Docs/Привет мир.txt"
LONG="a very long file name that goes well past the thirty one character limit of plain iso nine six six oh and also joliet which stops at sixty four.txt"
printf 'long\n' > "$SRC/Docs/$LONG"
for i in $(seq 1 120); do printf 'file %s\n' "$i" > "$SRC/Photos/IMG_$i.txt"; done
( cd "$SRC" && find . -type f -print0 | sort -z | while IFS= read -r -d '' f; do printf '%s\t%s\t%s\n' "$(shasum -a 256 "$f" | cut -d' ' -f1)" "$(stat -f %z "$f")" "${f#./}"; done ) > "$OUT/iso-rr.manifest"
docker run --rm -v "$SRC":/src -v "$OUT":/out ubuntu:24.04 bash -c 'apt-get update -qq >/dev/null && apt-get install -y -qq xorriso >/dev/null && xorriso -as mkisofs -R -J -V FKRR -o /out/iso-rr.iso /src >/dev/null 2>&1 && ls -la /out/iso-rr.iso'
