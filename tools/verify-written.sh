#!/bin/bash
# Asks macOS whether the images written by WriteFixtureTest are valid filesystems with the expected content.
# Usage: tools/verify-written.sh [fixturesdir]
OUT="${1:-$(cd "$(dirname "$0")/.." && pwd)/app/build/fixtures}"
rc=0
for name in hfs exfat exfatbig fat32 stress-hfs stress-exfat stress-fat32; do
  kind="${name#stress-}"
  img="$OUT/written-$name.img"; [ -f "$img" ] || { echo "[$name] no image"; continue; }
  dev=$(hdiutil attach -imagekey diskimage-class=CRawDiskImage -nomount "$img" | awk 'NR==1{print $1}')
  part=$(diskutil list "$dev" | awk '/Apple_HFS|Microsoft Basic Data|DOS_FAT_32|Windows_FAT_32|Apple_APFS|EXFAT|Microsoft/ && !/EFI/ {print $NF; exit}')
  [ -z "$part" ] && part="${dev#/dev/}s1"
  echo "== $name ($dev, /dev/$part)"
  case $kind in
    hfs)   fsck_hfs -fn "/dev/$part" 2>&1 | tail -8 ;;
    exfat|exfatbig) fsck_exfat -n "/dev/$part" 2>&1 | tail -4 ;;
    fat32) fsck_msdos -n "/dev/$part" 2>&1 | tail -4 ;;
  esac
  [ ${PIPESTATUS[0]} -ne 0 ] && { echo "[$name] FSCK FAILED"; rc=1; }
  diskutil mount readOnly "/dev/$part" >/dev/null 2>&1
  mnt=$(diskutil info "/dev/$part" | awk -F': *' '/Mount Point/{print $2}')
  if [ -n "$mnt" ] && [ -d "$mnt" ]; then
    while IFS=$'\t' read -r sha size path; do
      [ -z "$sha" ] && continue
      if [ "$sha" = "DELETED" ]; then [ -e "$mnt/$size" ] && { echo "[$name] still exists: $size"; rc=1; }; continue; fi
      got=$(shasum -a 256 "$mnt/$path" 2>/dev/null | cut -d' ' -f1)
      [ "$got" = "$sha" ] && echo "[$name] OK  $path" || { echo "[$name] BAD $path"; rc=1; }
    done < "$OUT/written-$name.expect"
  else echo "[$name] COULD NOT MOUNT on macOS"; rc=1; fi
  diskutil unmountDisk "$dev" >/dev/null 2>&1; hdiutil detach "$dev" >/dev/null 2>&1
done
exit $rc
