#!/bin/bash
# Builds app/build/fixtures/ntfs.img (MBR + NTFS with fragmented, sparse, compressed files and a 600-entry directory) in a
# privileged Linux container (needs Docker). Usage: docker run --rm --privileged -v <fixturesdir>:/out ubuntu:24.04 bash /out/make-ntfs-fixture.sh
set -e
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq >/dev/null && apt-get install -y -qq ntfs-3g util-linux fdisk parted attr >/dev/null 2>&1
cd /out
rm -f ntfs.img; truncate -s 256M ntfs.img
echo 'start=2048, type=7' | sfdisk ntfs.img >/dev/null
LOOP=$(losetup -f --show -o $((2048*512)) --sizelimit $((255*1024*1024)) ntfs.img)
echo "loop $LOOP"
mkntfs -Q -L VXNTFS $LOOP >/dev/null
mkdir -p /mnt/n && ntfs-3g $LOOP /mnt/n
M=/mnt/n
printf 'Hello NTFS VolumeX\n' > $M/hello.txt
: > $M/empty.txt
head -c 5242880 /dev/urandom > $M/big.bin
head -c 4097 /dev/urandom > $M/odd4097.bin
head -c 700 /dev/urandom > $M/resident700.bin
mkdir -p $M/dir/sub; printf 'deep file\n' > $M/dir/sub/deep.txt
printf 'unicode name\n' > $M/"Привет мир.txt"
mkdir -p $M/many; for i in $(seq 1 600); do printf 'file %s\n' $i > $M/many/"file_with_a_rather_long_name_$i.txt"; done
# fragmented file: interleave writes of two files
for i in $(seq 1 40); do head -c 65536 /dev/urandom >> $M/frag_a.bin; head -c 65536 /dev/urandom >> $M/frag_b.bin; done
rm $M/frag_b.bin
for i in $(seq 1 20); do head -c 65536 /dev/urandom >> $M/frag_a.bin; head -c 4096 /dev/urandom >> $M/pad.bin; done
# sparse file
truncate -s 10M $M/sparse.bin; printf 'tail' | dd of=$M/sparse.bin bs=1 seek=9000000 conv=notrunc 2>/dev/null
# compressed file (NTFS LZNT1) — ntfs-3g can create it via the ntfs.compression flag
mkdir $M/comp
setfattr -h -v 0x00000800 -n system.ntfs_attrib_be $M/comp
head -c 300000 /dev/zero | tr '\0' 'A' > $M/comp/compressible.txt
(for i in $(seq 1 3000); do echo "line $i of a compressible text file with repeating words repeating words"; done) > $M/comp/text.txt
getfattr -h -n system.ntfs_attrib_be $M/comp/text.txt || true
cd $M && find . -type f ! -path './.*' -print0 | sort -z | while IFS= read -r -d '' f; do printf '%s\t%s\t%s\n' "$(sha256sum "$f" | cut -d' ' -f1)" "$(stat -c %s "$f")" "${f#./}"; done > /out/ntfs.manifest
cd /; sync; umount /mnt/n; losetup -d $LOOP
echo built; wc -l /out/ntfs.manifest
