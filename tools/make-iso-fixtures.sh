#!/bin/bash
# Builds optical-image fixtures with the macOS tools: ISO 9660 (+Joliet), plain ISO 9660, UDF, and compressed DMGs holding HFS+.
# Each gets a "<name>.manifest" (sha256, size, path) like tools/make-fixtures.sh. macOS only.
set -euo pipefail
OUT="${1:-$(cd "$(dirname "$0")/.." && pwd)/app/build/fixtures}"; mkdir -p "$OUT"
SRC=$(mktemp -d); trap 'rm -rf "$SRC"' EXIT
mkdir -p "$SRC/Photos/2026/Trip" "$SRC/Docs" "$SRC/empty dir"
printf 'Hello FeldKit ISO\n' > "$SRC/hello.txt"
: > "$SRC/empty.txt"
head -c 5242880 /dev/urandom > "$SRC/big.bin"
head -c 4097 /dev/urandom > "$SRC/odd4097.bin"
head -c 2048 /dev/urandom > "$SRC/exact2048.bin"
printf 'deep\n' > "$SRC/Photos/2026/Trip/deep file with spaces.txt"
printf 'unicode\n' > "$SRC/Docs/Привет мир.txt"
printf 'long\n' > "$SRC/Docs/a long name beyond thirty one characters for iso.txt"
for i in $(seq 1 120); do printf 'file %s\n' "$i" > "$SRC/Photos/IMG_$i.txt"; done
manifest() { ( cd "$SRC" && find . -type f ! -name '.*' -print0 | sort -z | while IFS= read -r -d '' f; do printf '%s\t%s\t%s\n' "$(shasum -a 256 "$f" | cut -d' ' -f1)" "$(stat -f %z "$f")" "${f#./}"; done ) > "$1"; }
for n in iso-joliet iso-plain udf dmg-udzo dmg-udro dmg-ulfo; do manifest "$OUT/$n.manifest"; done
rm -f "$OUT"/iso-joliet.iso "$OUT"/iso-plain.iso "$OUT"/udf.iso "$OUT"/dmg-*.dmg
hdiutil makehybrid -iso -joliet -default-volume-name FKISO -o "$OUT/iso-joliet.iso" "$SRC" >/dev/null
hdiutil makehybrid -iso -default-volume-name FKPLAIN -o "$OUT/iso-plain.iso" "$SRC" >/dev/null
hdiutil makehybrid -udf -default-volume-name FKUDF -o "$OUT/udf.iso" "$SRC" >/dev/null
hdiutil create -srcfolder "$SRC" -volname FKDMG -fs HFS+ -format UDZO -ov "$OUT/dmg-udzo.dmg" >/dev/null
hdiutil create -srcfolder "$SRC" -volname FKDMG -fs HFS+ -format UDRO -ov "$OUT/dmg-udro.dmg" >/dev/null
hdiutil create -srcfolder "$SRC" -volname FKDMG -fs HFS+ -format ULFO -ov "$OUT/dmg-ulfo.dmg" >/dev/null 2>&1 || rm -f "$OUT/dmg-ulfo.manifest"
ls -la "$OUT"/*.iso "$OUT"/*.dmg
