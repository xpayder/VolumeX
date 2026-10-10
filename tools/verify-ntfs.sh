#!/bin/bash
# Asks ntfs-3g (in a privileged Linux container; needs Docker) whether the images written by WriteFixtureTest are valid NTFS
# volumes: mounts them read-only, compares every recorded file by SHA-256, looks each name up through the directory index,
# and runs ntfsresize's consistency check (MFT, runlists, cluster bitmap).
# Usage: tools/verify-ntfs.sh [fixturesdir]
OUT="${1:-$(cd "$(dirname "$0")/.." && pwd)/app/build/fixtures}"
cat > /tmp/verify-ntfs-inner.sh <<'IN'
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq >/dev/null && apt-get install -y -qq ntfs-3g util-linux >/dev/null 2>&1
rc=0
for name in ntfs stress-ntfs; do
  img=/fx/written-$name.img; [ -f "$img" ] || { echo "[$name] no image"; continue; }
  L=$(losetup -f --show -o $((2048*512)) --sizelimit $((255*1024*1024)) "$img")
  echo "== $name"
  ntfsresize --info -f $L 2>&1 | grep -iE "error|inconsist|corrupt|wrong|Checking filesystem|consistent|OK" | head -8
  ntfsresize --info -f $L >/dev/null 2>&1 || { echo "[$name] ntfsresize CHECK FAILED"; rc=1; }
  mkdir -p /mnt/v; ntfs-3g -o ro $L /mnt/v 2>&1 | head -2
  while IFS=$'\t' read -r sha size path; do
    [ -z "$sha" ] && continue
    if [ "$sha" = "DELETED" ]; then [ -e "/mnt/v/$size" ] && { echo "[$name] still exists: $size"; rc=1; }; continue; fi
    got=$(sha256sum "/mnt/v/$path" 2>/dev/null | cut -d' ' -f1)
    [ "$got" = "$sha" ] || { echo "[$name] BAD $path"; rc=1; }
  done < /fx/written-$name.expect
  echo "[$name] checked $(wc -l < /fx/written-$name.expect) entries; listing count: $(ls -R /mnt/v | wc -l)"
  umount /mnt/v; losetup -d $L
done
exit $rc
IN
docker run --rm --privileged -v "$OUT":/fx -v /tmp/verify-ntfs-inner.sh:/v.sh ubuntu:24.04 bash /v.sh
