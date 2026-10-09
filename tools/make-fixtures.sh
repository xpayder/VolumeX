#!/bin/bash
# Builds raw whole-disk GPT images (APFS, HFS+, exFAT, FAT32) with known content using the
# macOS tools, plus a manifest of "sha256  size  path" per file. macOS only.
# Usage: tools/make-fixtures.sh [outdir]   (default: app/build/fixtures)
set -euo pipefail
OUT="${1:-$(cd "$(dirname "$0")/.." && pwd)/app/build/fixtures}"
mkdir -p "$OUT"
SIZE_MB=${SIZE_MB:-256}

populate() {  # $1 = mountpoint
  local m="$1"
  printf 'Hello VolumeX\n' > "$m/hello.txt"
  : > "$m/empty.txt"
  head -c 5242880 /dev/urandom > "$m/big.bin"
  head -c 4097 /dev/urandom > "$m/odd4097.bin"
  mkdir -p "$m/dir/sub"; printf 'deep file\n' > "$m/dir/sub/deep.txt"
  printf 'unicode name\n' > "$m/Привет мир.txt"
  mkdir -p "$m/many"; for i in $(seq 1 300); do printf 'file %s\n' "$i" > "$m/many/file_$i.txt"; done
}

manifest() {  # $1 = mountpoint, $2 = out
  ( cd "$1" && find . -type f ! -path './.*' ! -name '.*' -print0 | sort -z | while IFS= read -r -d '' f; do
      printf '%s\t%s\t%s\n' "$(shasum -a 256 "$f" | cut -d' ' -f1)" "$(stat -f %z "$f")" "${f#./}"
    done ) > "$2"
}

make() {  # $1 = name, $2 = diskutil format, $3 = volume name
  local img="$OUT/$1.img"
  rm -f "$img"; mkfile -n ${SIZE_MB}m "$img"
  local dev; dev=$(hdiutil attach -imagekey diskimage-class=CRawDiskImage -nomount "$img" | awk 'NR==1{print $1}')
  diskutil eraseDisk "$2" "$3" GPT "$dev" >/dev/null
  local mnt="/Volumes/$3"
  [ -d "$mnt" ] || { echo "not mounted: $mnt"; exit 1; }
  populate "$mnt"; manifest "$mnt" "$OUT/$1.manifest"
  sync; diskutil unmountDisk "$dev" >/dev/null; hdiutil detach "$dev" >/dev/null
  echo "built $img ($(wc -l < "$OUT/$1.manifest" | tr -d ' ') files)"
}

make apfs  APFS    VXAPFS
make hfs   JHFS+   VXHFS
make exfat ExFAT   VXEXFAT
make fat32 "MS-DOS FAT32" VXFAT32
