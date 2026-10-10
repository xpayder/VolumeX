# FeldKit Changelog

## 0.1.1-alpha

### Added
- Initial Android Studio project
- Jetpack Compose UI
- Modular UI components
- HomeViewModel
- HomeUiState
- UsbState model
- USB service architecture
- APFS service placeholder
- App constants
- String resources

### Changed
- Renamed project to VolumeX
- Renamed package to com.fatalpuppet.volumex

### Fixed
- Package namespace issues
- Compose dependency issues
- Material Icons configuration

## [0.2.0-alpha] - 2026-08-02

### Added
- Automatic USB device detection.
- USB attach/detach broadcast handling.
- USB device information cards.
- DiskScanner framework.
- Storage package foundation.
- BlockDeviceReader skeleton.
- Partition data model.

### Changed
- Replaced simulated USB detection with real Android UsbManager integration.
- Improved USB status updates.

### Fixed
- HomeViewModel initialization.
- Android 13+ BroadcastReceiver compatibility.
- Safe handling of USB serial numbers without permission.

## [0.2.2-alpha]

### Added
- UsbBlockDeviceReader implementation.
- BlockDeviceReader interface expanded.
- RawSector model.
- DeviceConnectionState enum.
- HexUtils utility.

### Changed
- Storage layer prepared for raw sector access.
- 
## [0.3.0-alpha]

### Added
- UsbDeviceConnection management.
- SCSI command definitions.
- SCSI result model.
- Device connection state machine.
- Sector size constants.
- Hexadecimal utilities for storage debugging.

### Changed
- UsbBlockDeviceReader now maintains an active USB connection.

## [0.4.0-alpha] - 2026-08-03

### Added
- USB interface scanner.
- USB Mass Storage interface discovery.
- USB connection manager.
- USB connection information model.
- USB Mass Storage constants.
- Command Block Wrapper (CBW) model.
- Command Status Wrapper (CSW) model.
- SCSI INQUIRY response model.
- Diagnostics package foundation.

### Changed
- UsbBlockDeviceReader now opens, claims and releases USB interfaces.
- Improved internal USB architecture.

### Fixed
- USB interface detection.
- Endpoint discovery.

## Version 0.4.2-alpha

### Added
- Connected the UI to the real USB opening pipeline.
- Removed simulated USB connection workflow.
- Added repository support for opening the first detected USB device.
- Improved device state synchronization after USB attach/detach.

### Improved
- Simplified USB event registration.
- Removed duplicate repository methods.
- Cleaned orchestration between HomeScreen, HomeViewModel and UsbRepository.

## [0.5.0] - 2026-08-09

### Added
- Initial USB Mass Storage SCSI communication.
- Added SCSI TEST UNIT READY command support.
- Added SCSI INQUIRY command support.
- Added SCSI INQUIRY response parsing.
- Added USB storage device identification.
- Added detection of:
    - Device type
    - Removable media status
    - SCSI version
    - Vendor
    - Product
    - Revision
- Added SCSI transaction response data for diagnostic purposes.
- Added SCSI READ CAPACITY(10) support.
- Added logical block size and storage capacity detection.
- Added SCSI capacity response parsing.

### Improved
- Improved USB Mass Storage device initialization.
- Improved USB permission and device-opening workflow.
- Improved SCSI diagnostic logging.
- Improved USB device debugging and connection diagnostics.

### Tested
- Successfully tested against a physical Kingston DT microDuo 3C USB storage device.
- Successfully opened the device after Android USB permission was granted.
- Successfully claimed the USB Mass Storage interface.
- Successfully executed TEST UNIT READY.
- Successfully executed and parsed SCSI INQUIRY.

## [Unreleased]

### Added
- Added initial filesystem package structure.
- Added initial APFS container superblock model and parser.
- Added initial exFAT boot-sector model and parser as groundwork for future filesystem support.
- Organized filesystem implementations under `storage/filesystem/`.

### Completed
- USB mass-storage device detection and permission handling.
- USB bulk transport.
- USB Mass Storage Bulk-Only Transport.
- SCSI TEST UNIT READY.
- SCSI INQUIRY.
- SCSI READ CAPACITY (10).
- SCSI READ (10).
- MBR partition table detection and parsing.
- Successful raw sector reads from USB storage devices.

### Next
- Validate APFS partition layout, including GPT handling where required.
- Locate and validate the APFS container superblock (`NXSB`).
- Begin APFS container and volume parsing.
- Eventually expose the APFS directory structure and files.