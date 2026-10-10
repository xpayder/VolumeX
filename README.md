# VolumeX

VolumeX is a free, open-source Android app that reads (and writes) Apple APFS and HFS+ formatted drives over USB OTG — without root, without a Mac, without an account.

> VolumeX aims to become the open-source reference implementation for reading fancy, legacy, and otherwise uncommon filesystems on Android.

---

## Filesystem support (verified on macOS-made images)

Every row below is checked by automated tests that build real disk images with the macOS tools
(`tools/make-fixtures.sh`), run the app's own mount/read/write code on them, and then let macOS
judge the result (`tools/verify-written.sh`: `fsck_apfs` / `fsck_hfs -f` / `fsck_exfat` / `fsck_msdos`,
mount, SHA-256 comparison).

| Format | Read | Write | Notes |
|--------|:----:|:-----:|-------|
| **exFAT** | yes | yes | NoFatChain files, 128 KB clusters, 8 GB volume tested |
| **FAT32** | yes | yes | long names, `~N` aliases, FSInfo |
| **HFS+ / HFS+J** | yes | yes | catalog B-tree split/merge, attribute cleanup on delete; refuses unclean/journal-pending volumes |
| **APFS** | yes | experimental | in-place editing (not copy-on-write); off by default (Settings > Experimental); needs an unencrypted volume with no snapshots; creates missing chunk bitmaps (verified with `fsck_apfs`, 150 MB file across chunks) |
| ext2/3/4 | yes | no | read-only |
| FileVault (APFS) | yes (password or recovery key) | no | verified against a volume encrypted by macOS (`filevault.img`, tests in `FileVaultTest`); opened read-only |
| NTFS | yes | experimental | MFT, attribute lists, sparse + compressed (LZNT1) files, large directories; verified on an image made by `mkntfs`/ntfs-3g (`tools/make-ntfs-fixture.sh`). Write (off by default): create / rename / delete with MFT growth and B+tree index splits, refuses dirty or hibernated volumes; verified with ntfs-3g and `ntfsresize` (`tools/verify-ntfs.sh`), **not** with Windows chkdsk. Not readable: EFS-encrypted files |
| BitLocker (Windows 7+, To Go) | yes (password or 48-digit recovery key) | no | XTS and CBC ciphers (not the Windows 7 diffuser mode); NTFS / exFAT / FAT32 inside; fixtures opened by cryptsetup and dislocker (`tools/make-bitlocker.py`) |
| LUKS / LVM | partial | no | unit-untested; treat as unverified |

Write operations: add files (streamed), new folder, rename, delete (recursive). Multi-partition GPT/MBR
drives are supported (EFI partition skipped).

### Known limits
- APFS write is in-place (no copy-on-write): an unplugged cable mid-write can leave the volume needing `fsck_apfs`. Encrypted and snapshotted volumes stay read-only.
- HFS+ write does not grow the catalog file and cannot delete files whose attributes or extents live in overflow structures.
- 4Kn (4096-byte logical sector) drives are not supported; sector size is assumed to be 512.
- A power loss while an APFS write is in progress can leave the drive needing repair on a Mac.

### Test it yourself
```
tools/make-fixtures.sh          # builds app/build/fixtures/*.img with macOS tools
./gradlew :app:testDebugUnitTest
tools/verify-written.sh         # asks macOS (fsck + mount + hashes) about every written image
tools/phone-image.sh push|open|import|pull   # run the debug app on a phone against an image
```

---

## Architecture

```
app/
├── provider/
│   ├── DriveFileProvider        # ContentProvider pipe-streams files ("Open With" / Share)
│   └── DriveDocumentsProvider   # DocumentsProvider (Android file picker integration)
├── security/
│   └── KeystorePasswordManager  # AES-GCM + Android Keystore + BiometricPrompt
├── services/
│   └── TransferService          # Foreground Service for non-blocking transfers
├── storage/
│   ├── ActiveDriveSession       # Singleton for active reader (used by ContentProviders)
│   ├── FileOperationManager     # copy / delete / mkdir with progress callbacks
│   ├── crypto/
│   │   ├── AesXts               # Manual AES-XTS (GF(2^128) tweak advance)
│   │   ├── ApfsKeyDerivation    # PBKDF2-HMAC-SHA256 KEK, base-32 recovery key decode
│   │   ├── ApfsKeybag           # TLV keybag parser + RFC 3394 AES key unwrap
│   │   └── ApfsCrypto           # FileVault keybag / PBKDF2 / key unwrap
│   ├── disk/                    # BlockDeviceReader, DiskScanner, Partition
│   ├── filesystem/
│   │   ├── apfs/                # APFS container/volume superblock, OMAP, B-tree, reader
│   │   ├── hfsplus/             # HFS+ volume header, catalog B-tree, reader
│   │   ├── fat32/               # FAT32 boot sector, cluster chain, LFN reader
│   │   ├── ext/                 # ext2/3/4 superblock, BGD, extent tree reader
│   │   ├── exfat/               # exFAT boot sector
│   │   └── partition/           # GPT + MBR partition table parsers
│   ├── scsi/                    # SCSI command set (INQUIRY, READ CAPACITY, READ 10)
│   └── usb/                     # USB Bulk-Only Transport, UAS transport, interface scanner
└── ui/
    ├── screens/
    │   ├── HomeScreen            # USB connect flow, device info
    │   ├── VolumePickerScreen    # Multi-partition volume selector
    │   ├── FileBrowserScreen     # Directory listing, search, grid/list, operations
    │   ├── FilePreviewScreen     # Image / audio / video preview
    │   ├── FileVaultUnlockScreen # Password / recovery key entry
    │   ├── TransferScreen        # Transfer queue and progress
    │   └── SettingsScreen        # Hidden files, view mode, saved passwords
    ├── components/               # GlassCard, FileListItem, DeviceVolumeCard, ...
    └── theme/                    # Liquid Glass palette (deep navy + frosted glass)
```

---

## Requirements

- Android 8.0 (API 26) or newer with USB OTG support
- USB-C cable or USB-OTG adapter; large bus-powered drives may need a powered hub
- No root, no Mac, no account required

## Credits
See [CREDITS.md](CREDITS.md).

## License

See [LICENSE](LICENSE).
