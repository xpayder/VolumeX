set -e
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq >/dev/null && apt-get install -y -qq ntfs-3g exfatprogs exfat-fuse util-linux >/dev/null 2>&1
cd /out
populate() {
  M=$1
  printf 'Hello BitLocker VolumeX\n' > $M/hello.txt
  head -c 3000000 /dev/urandom > $M/big.bin
  mkdir -p $M/dir/sub; printf 'deep\n' > $M/dir/sub/deep.txt
  printf 'unicode\n' > $M/"Привет.txt"
  for i in $(seq 1 40); do printf 'f%s\n' $i > $M/dir/file_$i.txt; done
  (cd $M && find . -type f -print0 | sort -z | while IFS= read -r -d '' f; do printf '%s\t%s\t%s\n' "$(sha256sum "$f" | cut -d' ' -f1)" "$(stat -c %s "$f")" "${f#./}"; done)
}
rm -f raw-ntfs.img raw-exfat.img
truncate -s 64M raw-ntfs.img; mkntfs -F -Q -L BLNTFS raw-ntfs.img >/dev/null 2>&1
mkdir -p /mnt/a; ntfs-3g -o loop raw-ntfs.img /mnt/a 2>/dev/null || mount -o loop raw-ntfs.img /mnt/a
populate /mnt/a > raw-ntfs.manifest; sync; umount /mnt/a
truncate -s 64M raw-exfat.img; mkfs.exfat -L BLEXFAT raw-exfat.img >/dev/null
L=$(losetup -f --show raw-exfat.img); (mount -t exfat $L /mnt/a 2>/dev/null || mount.exfat-fuse $L /mnt/a)
populate /mnt/a > raw-exfat.manifest; sync; umount /mnt/a; losetup -d $L
ls -la raw-*; wc -l raw-*.manifest
