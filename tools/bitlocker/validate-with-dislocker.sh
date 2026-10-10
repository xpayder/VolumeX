export DEBIAN_FRONTEND=noninteractive
apt-get update -qq >/dev/null && apt-get install -y -qq util-linux ntfs-3g exfatprogs exfat-fuse dislocker fuse3 >/dev/null 2>&1
dislocker --version 2>&1 | head -1
for img in bitlocker-ntfs bitlocker-exfat bitlocker-cbc; do
  for cred in "-uVolumeX-Test-1" "-p012221-024442-036663-048884-061105-073326-085547-097768"; do
    echo "=== $img $cred"
    L=$(losetup -f --show -o $((2048*512)) /fx/$img.img)
    mkdir -p /mnt/dl /mnt/b; rm -rf /mnt/dl/*
    dislocker -V $L $cred -- /mnt/dl 2>&1 | tail -3
    ls /mnt/dl
    if [ -e /mnt/dl/dislocker-file ]; then
      L2=$(losetup -f --show /mnt/dl/dislocker-file)
      (mount -o ro $L2 /mnt/b 2>&1 || ntfs-3g -o ro $L2 /mnt/b 2>&1 || mount.exfat-fuse -o ro $L2 /mnt/b 2>&1) | tail -2
      (cd /mnt/b && find . -type f -print0 | sort -z | while IFS= read -r -d '' f; do printf '%s\t%s\t%s\n' "$(sha256sum "$f" | cut -d' ' -f1)" "$(stat -c %s "$f")" "${f#./}"; done) > /tmp/got.txt
      diff /tmp/got.txt /fx/$img.manifest > /dev/null && echo "MANIFEST MATCH $img" || echo "MISMATCH $img ($(wc -l < /tmp/got.txt) files)"
      umount /mnt/b 2>/dev/null; losetup -d $L2
    fi
    umount /mnt/dl 2>/dev/null; losetup -d $L
  done
done
