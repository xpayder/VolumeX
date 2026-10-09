#!/bin/bash
# Builds raw whole-disk GPT images (APFS, HFS+, exFAT, FAT32) with known content using the
# macOS tools, plus a manifest of "sha256  size  path" per file. macOS only.
# Usage: tools/make-fixtures.sh [outdir]   (default: app/build/fixtures)
set -euo pipefail
OUT="${1:-$(cd "$(dirname "$0")/.." && pwd)/app/build/fixtures}"
mkdir -p "$OUT"
SIZE_MB=${SIZE_MB:-256}
BIG_MB=${BIG_MB:-8192}

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

make() {  # $1 = name, $2 = diskutil format, $3 = volume name, $4 = size in MB (optional)
  local img="$OUT/$1.img"
  rm -f "$img"; mkfile -n ${4:-$SIZE_MB}m "$img"
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
make exfatbig ExFAT VXEXFBIG "$BIG_MB"

# FileVault-encrypted APFS (throwaway password "secret123"), for the unlock tests.
make_filevault() {
  local img="$OUT/filevault.img"; rm -f "$img"; mkfile -n 160m "$img"
  local dev; dev=$(hdiutil attach -imagekey diskimage-class=CRawDiskImage -nomount "$img" | awk 'NR==1{print $1}')
  diskutil eraseDisk APFS VXFV GPT "$dev" >/dev/null
  printf 'Hello encrypted VolumeX\n' > /Volumes/VXFV/hello.txt
  head -c 3000000 /dev/urandom > /Volumes/VXFV/big.bin
  mkdir -p /Volumes/VXFV/docs; printf 'secret note\n' > /Volumes/VXFV/docs/note.txt
  manifest /Volumes/VXFV "$OUT/filevault.manifest"
  sync
  diskutil apfs encryptVolume /Volumes/VXFV -user disk -passphrase secret123 >/dev/null
  for i in $(seq 1 120); do
    diskutil apfs list | grep -q "Conversion Status:.*Complete\|FileVault:  *Yes (Unlocked)" && ! diskutil apfs list | grep -q "Encrypting" && break; sleep 2
  done
  sync; diskutil unmountDisk force "$dev" >/dev/null; hdiutil detach "$dev" >/dev/null
  echo "built $img"
}
make_filevault

# Three-partition GPT disk (HFS+, APFS, exFAT), one marker file in each.
make_multi() {
  local img="$OUT/multi.img"; rm -f "$img"; mkfile -n 400m "$img"
  local dev; dev=$(hdiutil attach -imagekey diskimage-class=CRawDiskImage -nomount "$img" | awk 'NR==1{print $1}')
  diskutil partitionDisk "$dev" GPT JHFS+ MULTIHFS 100m APFS MULTIAPFS 100m ExFAT MULTIEXFAT R >/dev/null
  for v in MULTIHFS MULTIAPFS MULTIEXFAT; do printf 'marker %s\n' "$v" > "/Volumes/$v/marker.txt"; done
  sync; diskutil unmountDisk "$dev" >/dev/null; hdiutil detach "$dev" >/dev/null
  echo "built $img"
}
make_multi

# exFAT with 128 KB clusters and a busy root (like a 1 TB drive formatted by Windows).
make_exfat128() {
  local img="$OUT/exfat128.img"; rm -f "$img"; mkfile -n 2048m "$img"
  local dev; dev=$(hdiutil attach -imagekey diskimage-class=CRawDiskImage -nomount "$img" | awk 'NR==1{print $1}')
  diskutil partitionDisk "$dev" GPT ExFAT VX128 R >/dev/null
  local part; part=$(diskutil list "$dev" | awk '/VX128/{print $NF}')
  diskutil unmount "/dev/$part" >/dev/null 2>&1
  newfs_exfat -c 256 -v VX128 "/dev/r$part" >/dev/null
  diskutil mount "/dev/$part" >/dev/null
  local m="/Volumes/VX128"
  for i in $(seq 1 400); do printf 'row %s\n' "$i" > "$m/item $i - Пример.txt"; done
  mkdir -p "$m/Android" "$m/Samples (old)"; printf 'hello\n' > "$m/Android/a.txt"
  sync; manifest "$m" "$OUT/exfat128.manifest"
  diskutil unmountDisk "$dev" >/dev/null; hdiutil detach "$dev" >/dev/null
  echo "built $img"
}
make_exfat128
