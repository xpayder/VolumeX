# VolumeX

VolumeX is an open-source Android application designed to read Apple APFS formatted drives directly from Android devices without requiring a Mac.

> VolumeX aims to become an open-source Android reader for fancy,
> legacy, deprecated, and otherwise uncommon filesystems.

Current development status:
- UI framework ✅
- USB detection ✅
- USB Mass Storage communication 🟢
- SCSI engine ⏳
- GPT parser ⏳
- APFS parser ⏳

## Current Status

VolumeX is currently in active development.

### Working

- Android USB Host detection
- USB device permission handling
- USB Mass Storage interface detection
- USB interface claiming
- Bulk-Only Transport initialization
- SCSI TEST UNIT READY
- SCSI INQUIRY
- SCSI INQUIRY response parsing
- USB storage device identification

### In Progress

- SCSI READ CAPACITY
- Raw sector reading
- GPT detection
- Filesystem detection
- APFS volume parsing
- File listing and file access

### Planned

- HFS/HFS+
- ext2/ext3/ext4
- Additional legacy and uncommon filesystems