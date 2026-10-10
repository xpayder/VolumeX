#!/bin/bash
# Builds LUKS1 / LUKS2 (pbkdf2 and argon2id) containers holding ext4, and an LVM physical volume with two ext4 logical volumes,
# inside a privileged Ubuntu container, plus a manifest of sha256/size/path for each. Passphrase for every container: FeldKit-Test-1
# usage: tools/linux/make-luks-lvm.sh [outdir]
set -euo pipefail
OUT="${1:-$(cd "$(dirname "$0")/../.." && pwd)/app/build/fixtures}"; mkdir -p "$OUT"
docker run --rm --privileged -v /dev:/dev -v "$OUT":/out ubuntu:24.04 bash -c '
set -e
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq >/dev/null && apt-get install -y -qq cryptsetup lvm2 e2fsprogs util-linux udev >/dev/null 2>&1
cd /out
PASS=FeldKit-Test-1
populate() {
  M=$1
  printf "Hello FeldKit LUKS\n" > $M/hello.txt; : > $M/empty.txt
  head -c 3000000 /dev/urandom > $M/big.bin; head -c 4097 /dev/urandom > $M/odd4097.bin
  mkdir -p $M/dir/sub; printf "deep\n" > $M/dir/sub/deep.txt; printf "unicode\n" > $M/"Привет.txt"
  for i in $(seq 1 60); do printf "f%s\n" $i > $M/dir/file_$i.txt; done
  (cd $M && find . -type f -print0 | sort -z | while IFS= read -r -d "" f; do printf "%s\t%s\t%s\n" "$(sha256sum "$f" | cut -d" " -f1)" "$(stat -c %s "$f")" "${f#./}"; done)
}
mkluks() {  # name, luksFormat args
  N=$1; shift
  rm -f $N.img; truncate -s 48M $N.img
  L=$(losetup -f --show $N.img)
  echo -n "$PASS" | cryptsetup luksFormat --batch-mode "$@" $L -
  echo -n "$PASS" | cryptsetup open $L fk_$N -
  mkfs.ext4 -q -L FKLUKS /dev/mapper/fk_$N
  mkdir -p /mnt/$N; mount /dev/mapper/fk_$N /mnt/$N
  populate /mnt/$N > $N.manifest
  sync; umount /mnt/$N; cryptsetup close fk_$N; losetup -d $L
}
mkluks luks1-xts --type luks1 --cipher aes-xts-plain64 --key-size 512 --hash sha256 --pbkdf-force-iterations 1000
mkluks luks1-cbc --type luks1 --cipher aes-cbc-essiv:sha256 --key-size 256 --hash sha1 --pbkdf-force-iterations 1000
mkluks luks2-pbkdf2 --type luks2 --pbkdf pbkdf2 --pbkdf-force-iterations 1000 --cipher aes-xts-plain64 --key-size 512
mkluks luks2-argon --type luks2 --pbkdf argon2id --pbkdf-memory 65536 --pbkdf-parallel 2 --pbkdf-force-iterations 4 --cipher aes-xts-plain64 --key-size 512
# a typical modern distribution default: Argon2id with 1 GiB of memory (4 passes forced to keep creation fast)
mkluks luks2-1gib --type luks2 --pbkdf argon2id --pbkdf-memory 1048576 --pbkdf-parallel 4 --pbkdf-force-iterations 4 --cipher aes-xts-plain64 --key-size 512
# LVM: one PV file with two logical volumes (clean up leftovers of an earlier run first)
vgchange -an fkvg >/dev/null 2>&1 || true; vgremove -ff -y fkvg >/dev/null 2>&1 || true; dmsetup remove_all >/dev/null 2>&1 || true; for l in $(losetup -a | grep lvm.img | cut -d: -f1); do losetup -d $l; done
rm -f lvm.img; truncate -s 96M lvm.img
L=$(losetup -f --show lvm.img)
pvcreate -ff -y $L >/dev/null; vgcreate fkvg $L >/dev/null
export DM_DISABLE_UDEV=1 LVM_SUPPRESS_FD_WARNINGS=1
lvcreate -y -Zn -Wn --noudevsync -L 24M -n first fkvg >/dev/null; lvcreate -y -Zn -Wn --noudevsync -L 24M -n second fkvg >/dev/null; vgmknodes 2>/dev/null || true
mkfs.ext4 -q -L FKLVM1 /dev/fkvg/first; mkfs.ext4 -q -L FKLVM2 /dev/fkvg/second
mkdir -p /mnt/l1 /mnt/l2; mount /dev/fkvg/first /mnt/l1; mount /dev/fkvg/second /mnt/l2
populate /mnt/l1 > lvm-first.manifest; printf "second volume\n" > /mnt/l2/only-here.txt
(cd /mnt/l2 && printf "%s\t%s\t%s\n" "$(sha256sum only-here.txt | cut -d" " -f1)" 14 only-here.txt) > lvm-second.manifest
sync; umount /mnt/l1 /mnt/l2; vgchange -an fkvg >/dev/null; losetup -d $L
ls -la *.img
'
