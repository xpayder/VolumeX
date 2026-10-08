# VolumeX

VolumeX is a free, open-source Android app that reads (and writes) Apple APFS and HFS+ formatted drives over USB OTG — without root, without a Mac, without an account.

> VolumeX aims to become the open-source reference implementation for reading fancy, legacy, and otherwise uncommon filesystems on Android.

---

## Features

### Filesystem support

| Format | Read | Write | Notes |
|--------|------|-------|-------|
| **APFS** | ✅ | ⚠️ planned | Full B-tree traversal, OMAP resolution, extents |
| **HFS+** | ✅ | ⚠️ planned | Catalog B-tree, fork data, linked-list leaf scan |
| **exFAT** | ✅ | ✅ | Boot sector parsed; full file access |
| **FAT32** | ✅ | ✅ | Cluster chains, LFN long-filename entries |
| **ext2/3/4** | ✅ | — | Extent tree + block-map inodes, dir entries |
| **FileVault** | ✅ | ✅ | AES-XTS, PBKDF2 password + recovery-key paths |
| **LUKS1/2** | ⏳ | — | Planned |
| **LVM** | ⏳ | — | Linear volumes planned |
| **NTFS** | — | — | Out of scope (see separate apps) |

### File manager

- Browse, search, and sort (name / size / date)
- Grid view (photo thumbnails) and list view
- Toggle hidden files (`.` prefix)
- Folder details: recursive item count and total size
- Multi-partition drives: volume picker screen when >1 volume detected
- Drives larger than 2 TB supported

### File operations

- **Copy to phone** — streaming copy to Android Downloads with live progress, speed, and ETA
- **Copy to drive** — import files from phone storage to APFS / HFS+ / FAT32 / exFAT
- **Delete / rename / mkdir** on supported filesystems
- Non-blocking transfers run as a Foreground Service — browse while copying
- Atomic writes: an unplugged drive mid-write is recoverable via Mac's fsck

### Preview & sharing

- **Image preview** — full-screen Coil AsyncImage directly off the drive (no copy needed)
- **Audio preview** — Android MediaPlayer via pipe-streaming ContentProvider
- **Video preview** — Android MediaPlayer via pipe-streaming ContentProvider
- **"Open with"** — fires `ACTION_VIEW` with a drive-backed content URI
- **Share** — fires `ACTION_SEND` so any app can receive a file from the drive
- **Android file picker integration** — DriveDocumentsProvider makes the drive appear in Gmail, Drive, WhatsApp, etc.

### Security

- FileVault: password unlock and personal recovery-key unlock
- Saved passwords encrypted with AES-256-GCM in Android Keystore
- Biometric (fingerprint / face) protection for saved passwords
- No analytics, no ads, no crash-reporting SDKs, no trackers

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
│   │   └── FileVaultDecryptor   # High-level FileVault unlock
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

## Status

### Implemented ✅

- USB OTG host detection and permission handling
- USB Mass Storage Bulk-Only Transport (BOT) and UAS transport
- SCSI: TEST UNIT READY, INQUIRY, READ CAPACITY(10), READ(10)
- MBR and GPT partition table detection and parsing
- APFS: container superblock (NXSB), object map (OMAP), volume superblock (APSB),
  B-tree traversal (fixed and variable K/V), inode records, directory entries, extents
- HFS+: volume header, catalog B-tree leaf scan, file/folder records, fork data
- FAT32: boot sector, FAT cluster chain, LFN long-filename entries
- ext2/3/4: superblock, block group descriptors, extent-tree and block-map inodes, dir entries
- FileVault: AES-XTS-128/256 decryption, PBKDF2-HMAC-SHA256 key derivation, keybag TLV,
  RFC 3394 key unwrap, password and recovery-key unlock paths
- File copy from drive to Android Downloads (streaming, with progress)
- File copy from Android to drive
- Liquid Glass UI: dark navy palette, frosted glass cards, breadcrumb navigation
- Search, sort, grid/list toggle, hidden files toggle, folder details
- Multi-partition volume picker
- ContentProvider for "Open With" / Share without copying
- DocumentsProvider for Android file picker integration
- Image preview directly off the drive (Coil)
- Audio and video preview via MediaPlayer
- Android Keystore password storage with biometric unlock
- Non-blocking Foreground Service transfers
- Settings screen

### Planned / In Progress ⏳

- APFS write (create, rename, delete): requires COW B-tree mutations,
  space-manager allocation, and checkpointing — complex, in progress
- HFS+ write: allocation bitmap, catalog B-tree insertion, journal updates — in progress
- LUKS1/2 encrypted volumes
- LVM linear volumes
- exFAT full file-manager integration (reader exists, write pending)

---

## Requirements

- Android 8.0 (API 26) or newer with USB OTG support
- USB-C cable or USB-OTG adapter; large bus-powered drives may need a powered hub
- No root, no Mac, no account required

## License

See [LICENSE](LICENSE).
