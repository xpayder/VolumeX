package com.fatalpuppet.volumex.storage.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import android.content.Intent
import android.app.PendingIntent
import com.fatalpuppet.volumex.storage.disk.BlockDeviceReader
import com.fatalpuppet.volumex.storage.filesystem.FilesystemDetector
import com.fatalpuppet.volumex.storage.filesystem.FilesystemType
import com.fatalpuppet.volumex.storage.filesystem.apfs.ApfsBTree
import com.fatalpuppet.volumex.storage.filesystem.apfs.ApfsVolumeSuperblockParser
import com.fatalpuppet.volumex.storage.filesystem.partition.MbrPartitionTable
import com.fatalpuppet.volumex.storage.scsi.ScsiCapacityResponseParser
import com.fatalpuppet.volumex.storage.scsi.ScsiDebug
import com.fatalpuppet.volumex.storage.scsi.ScsiExecutor
import com.fatalpuppet.volumex.storage.scsi.ScsiInquiryResponseParser
import com.fatalpuppet.volumex.storage.scsi.ScsiTransaction
import com.fatalpuppet.volumex.storage.filesystem.exfat.ExFatBootSector
import com.fatalpuppet.volumex.storage.filesystem.exfat.ExFatBootSectorParser
import com.fatalpuppet.volumex.storage.filesystem.apfs.ApfsContainerSuperblockParser
import com.fatalpuppet.volumex.storage.filesystem.apfs.ApfsFileEntry
import com.fatalpuppet.volumex.storage.filesystem.partition.GptPartitionTable
import com.fatalpuppet.volumex.storage.scsi.CommandBlockWrapper
import com.fatalpuppet.volumex.storage.scsi.CommandTagGenerator
import com.fatalpuppet.volumex.storage.scsi.ScsiCommand

import com.fatalpuppet.volumex.storage.scsi.UasExecutor

class UsbBlockDeviceReader(
    private val usbManager: UsbManager,
    private val device: UsbDevice
) : BlockDeviceReader {

    companion object {
        private const val TAG = "VolumeX"
    }

    // Add this function to the UsbBlockDeviceReader class
    // Replace the parseApfsFilesystem function with this corrected version
    fun parseApfsFilesystem(containerStartLba: Long, blockSize: Int): List<ApfsFileEntry>? {
        val executor = scsiExecutor ?: return null

        // Read APFS container superblock (located at LBA = containerStartLba + 0x20)
        val superblockLba = containerStartLba + 0x20
        Log.i(TAG, "Reading APFS container superblock at LBA $superblockLba")

        // Fix: Use readSector or the transaction properly
        val transaction = executor.read10(
            lba = superblockLba,
            blockCount = 1,
            blockSize = blockSize
        )

        if (!transaction.success || transaction.data == null) {
            Log.e(TAG, "Failed to read APFS container superblock")
            return null
        }

        val containerSb = ApfsContainerSuperblockParser.parse(transaction.data!!)
        if (containerSb == null) {
            Log.e(TAG, "Invalid APFS container superblock")
            return null
        }

        Log.i(TAG, "APFS Container: ${containerSb.containerUuidString}")
        Log.i(TAG, "Block size: ${containerSb.blockSize}")
        Log.i(TAG, "Volume count: ${containerSb.volumeCount}")

        // For now, we'll read the first volume
        // In a real implementation, you'd walk the volume list
        val volumeLba = containerStartLba + 0x40 // Simplified volume location
        val volumeTransaction = executor.read10(
            lba = volumeLba,
            blockCount = 1,
            blockSize = blockSize
        )

        if (!volumeTransaction.success || volumeTransaction.data == null) {
            Log.e(TAG, "Failed to read APFS volume")
            return null
        }

        val volumeSb = ApfsVolumeSuperblockParser.parse(volumeTransaction.data!!)
        if (volumeSb == null) {
            Log.e(TAG, "Invalid APFS volume superblock")
            return null
        }

        Log.i(TAG, "APFS Volume: ${volumeSb.volumeName}")
        Log.i(TAG, "Root directory object ID: ${volumeSb.rootDirectoryObjectId}")

        // Walk the B-Tree
        val btree = ApfsBTree(
            reader = this,
            containerStartLba = containerStartLba,
            blockSize = containerSb.blockSize,
            rootObjectId = volumeSb.rootDirectoryObjectId
        )

        return btree.walkTree()
    }

    private var connection: UsbDeviceConnection? = null
    private var massStorage: UsbMassStorageInterface? = null
    private var transport: BulkUsbTransport? = null
    private var scsiExecutor: ScsiExecutor? = null
    private var connectionInfo: UsbConnectionInfo? = null
    private var claimed = false
    private var uasInterface: UsbUasInterface? = null

    private val interfaceScanner = UsbInterfaceScanner()

    fun getConnectionInfo(): UsbConnectionInfo? {
        val storage = massStorage ?: return null

        return UsbConnectionInfo(
            vendorId = device.vendorId,
            productId = device.productId,
            manufacturer = device.manufacturerName,
            product = device.productName,
            interfaceNumber = storage.interfaceNumber,
            endpointIn = storage.bulkIn.address,
            endpointOut = storage.bulkOut.address
        )
    }
    // Alternative: Try UAS first, fall back to BOT
    override fun open(): Boolean {
        Log.i(TAG, "========== UsbBlockDeviceReader.open() ==========")
        val usbConnection = usbManager.openDevice(device)

        if (usbConnection == null) {
            Log.e(TAG, "Unable to open USB device")
            return false
        }

        connection = usbConnection
        interfaceScanner.inspectDevice(device)

        val storageInterface = interfaceScanner.findMassStorageInterface(device)

        if (storageInterface == null) {
            Log.e(TAG, "Mass Storage interface not found")
            close()
            return false
        }

        // Try UAS first
        val uasInterface = interfaceScanner.findUasInterface(device)
        var useUas = false

        if (uasInterface != null) {
            Log.i(TAG, "UAS interface detected, attempting UAS...")

            // Try to claim UAS interface
            if (usbConnection.claimInterface(uasInterface.usbInterface, true)) {
                Log.i(TAG, "UAS interface claimed successfully")

                // Try UAS transport
                try {
                    val uasTransport = UsbUasTransport(
                        connection = usbConnection,
                        uasInterface = uasInterface
                    )
                    val uasExecutor = UasExecutor(uasTransport)
                    val uasReady = uasExecutor.testUnitReady()

                    if (uasReady) {
                        Log.i(TAG, "UAS TEST UNIT READY successful!")
                        useUas = true
                        // Set up UAS transport
                        // Note: You'll need to implement UAS support in ScsiExecutor
                    } else {
                        Log.w(TAG, "UAS TEST UNIT READY failed, falling back to BOT")
                        usbConnection.releaseInterface(uasInterface.usbInterface)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "UAS initialization failed: ${e.message}")
                    usbConnection.releaseInterface(uasInterface.usbInterface)
                }
            }
        }

        // Fall back to BOT if UAS failed
        if (!useUas) {
            Log.i(TAG, "Using Bulk-Only Transport (BOT)")

            if (!usbConnection.claimInterface(storageInterface.usbInterface, true)) {
                Log.e(TAG, "Unable to claim USB Mass Storage interface")
                close()
                return false
            }

            transport = BulkUsbTransport(
                connection = usbConnection,
                bulkIn = storageInterface.bulkIn,
                bulkOut = storageInterface.bulkOut
            )
        }

        claimed = true
        connectionInfo = getConnectionInfo()

        // Initialize SCSI executor with BOT
        scsiExecutor = ScsiExecutor(BulkOnlyTransport(transport!!))

        if (!runScsiChecks()) {
            Log.e(TAG, "SCSI checks failed")
            close()
            return false
        }

        return readDiskLayout()
    }

    fun readExFatBootSector(
        partitionStartLba: Long,
        blockSize: Int
    ): ExFatBootSector? {

        val executor =
            scsiExecutor
                ?: return null

        Log.i(
            "VolumeX",
            "Reading exFAT boot sector at LBA $partitionStartLba"
        )

        val transaction =
            executor.read10(
                lba = partitionStartLba,
                blockCount = 1,
                blockSize = blockSize
            )

        val sector =
            transaction.data
                ?: return null

        Log.i(
            "VolumeX",
            "exFAT boot sector read: ${sector.size} bytes"
        )

        return ExFatBootSectorParser.parse(
            sector = sector,
            partitionStartLba = partitionStartLba
        )
    }

    // In UsbBlockDeviceReader.kt, update runScsiChecks():
    private fun runScsiChecks(): Boolean {
        val executor = scsiExecutor ?: run {
            Log.e(TAG, "SCSI executor unavailable")
            return false
        }

        // TEST UNIT READY
        val testCommand = ScsiCommand.testUnitReady()
        val readyCbw = CommandBlockWrapper(
            tag = CommandTagGenerator.next(),
            dataTransferLength = 0,
            flags = 0x00.toByte(),
            lun = 0,
            commandLength = testCommand.size.toByte(),
            command = testCommand
        )

        val ready = executor.execute(
            "TEST UNIT READY",
            readyCbw,
            0
        )

        ScsiDebug.transaction(ready)

        if (!ready.success) {
            Log.e(TAG, "Device is not ready")
            return false
        }

        // INQUIRY
        val inquiry = executor.inquiry()
        ScsiDebug.transaction(inquiry)

        inquiry.data?.let { data ->
            ScsiInquiryResponseParser.parse(data)?.let { response ->
                Log.i(
                    TAG,
                    "SCSI device: ${response.vendor} ${response.product} " +
                            "${response.revision}"
                )
                Log.i(
                    TAG,
                    "Removable=${response.removable}, " +
                            "Type=${response.peripheralDeviceType}, " +
                            "SCSI=${response.scsiVersion}"
                )
            }
        }

        if (!inquiry.success) {
            Log.e(TAG, "SCSI INQUIRY failed")
            return false
        }

        return true
    }

    private fun readDiskLayout(): Boolean {

        val executor =
            scsiExecutor
                ?: return false

        // READ CAPACITY(10)
        val capacityTransaction = executor.readCapacity()

        ScsiDebug.transaction(capacityTransaction)

        val capacityData =
            capacityTransaction.data
                ?: run {
                    Log.e(TAG, "READ CAPACITY returned no data")
                    return false
                }

        val capacity =
            ScsiCapacityResponseParser.parse(capacityData)
                ?: run {
                    Log.e(TAG, "Unable to parse READ CAPACITY response")
                    return false
                }

        Log.i(
            TAG,
            "Disk: ${capacity.blockCount} blocks × " +
                    "${capacity.blockSize} bytes"
        )

        Log.i(
            TAG,
            "Capacity: ${capacity.capacityBytes} bytes"
        )

        // READ MBR
        val mbrTransaction = executor.read10(
            lba = 0,
            blockCount = 1,
            blockSize = capacity.blockSize
        )

        ScsiDebug.transaction(mbrTransaction)

        val mbrData =
            mbrTransaction.data
                ?: run {
                    Log.e(TAG, "Unable to read MBR")
                    return false
                }

        val partitions =
            MbrPartitionTable.parse(mbrData)
                ?: run {
                    Log.i(TAG, "No MBR partition table detected")
                    return true
                }

        Log.i(
            TAG,
            "MBR: ${partitions.size} partition(s)"
        )

        partitions.forEachIndexed { index, partition ->
            Log.i(
                TAG,
                "Partition ${index + 1}: " +
                        "type=0x%02X, ".format(partition.partitionType) +
                        "start=${partition.startLba}, " +
                        "sectors=${partition.sectorCount}, " +
                        "end=${partition.endLba}, " +
                        "bootable=${partition.bootable}"
            )
        }

        // Now that LBA 0 has been read successfully, inspect the GPT header
        // at LBA 1 and, if present, read the GPT partition-entry array.
        readGptLayout(
            executor = executor,
            blockSize = capacity.blockSize
        )


        if (partitions.isNotEmpty()) {
            //probeFilesystem(
            //    partitions[0].startLba,
            //   capacity.blockSize
            //)
        }


        val partition = partitions.firstOrNull()

        if (partition != null) {

            val exFat =
                readExFatBootSector(
                    partitionStartLba = partition.startLba,
                    blockSize = capacity.blockSize
                )

            if (exFat != null) {

                Log.i(
                    "VolumeX",
                    "exFAT filesystem detected"
                )

                Log.i(
                    "VolumeX",
                    "FAT offset = ${exFat.fatOffset}"
                )

                Log.i(
                    "VolumeX",
                    "FAT length = ${exFat.fatLength}"
                )

                Log.i(
                    "VolumeX",
                    "Cluster heap offset = ${exFat.clusterHeapOffset}"
                )

                Log.i(
                    "VolumeX",
                    "Cluster count = ${exFat.clusterCount}"
                )

                Log.i(
                    "VolumeX",
                    "Root directory cluster = ${exFat.rootDirectoryCluster}"
                )

                Log.i(
                    "VolumeX",
                    "Bytes per sector = ${exFat.bytesPerSector}"
                )

                Log.i(
                    "VolumeX",
                    "Sectors per cluster = ${exFat.sectorsPerCluster}"
                )

                Log.i(
                    "VolumeX",
                    "Bytes per cluster = ${exFat.bytesPerCluster}"
                )
            }
        }

        return true
    }
    // Replace the entire readGptLayout function
    private fun readGptLayout(
        executor: ScsiExecutor,
        blockSize: Int
    ) {
        Log.i(TAG, "Reading GPT header at LBA 1")

        // Read GPT header at LBA 1
        val headerTransaction = executor.read10(
            lba = 1,
            blockCount = 1,
            blockSize = blockSize
        )

        ScsiDebug.transaction(headerTransaction)

        if (!headerTransaction.success) {
            Log.i(TAG, "GPT header read failed")
            return
        }

        val headerData = headerTransaction.data ?: run {
            Log.i(TAG, "GPT header returned no data")
            return
        }

        val gptHeader = GptPartitionTable.parseHeader(headerData) ?: run {
            Log.i(TAG, "GPT header signature not found")
            return
        }

        Log.i(TAG, "GPT detected")
        Log.i(TAG, "GPT revision = 0x${gptHeader.revision.toString(16)}")
        Log.i(TAG, "GPT header size = ${gptHeader.headerSize}")
        Log.i(TAG, "GPT current LBA = ${gptHeader.currentLba}")
        Log.i(TAG, "GPT backup LBA = ${gptHeader.backupLba}")
        Log.i(TAG, "GPT first usable LBA = ${gptHeader.firstUsableLba}")
        Log.i(TAG, "GPT last usable LBA = ${gptHeader.lastUsableLba}")
        Log.i(TAG, "GPT partition entry LBA = ${gptHeader.partitionEntryLba}")
        Log.i(TAG, "GPT partition entry count = ${gptHeader.partitionEntryCount}")
        Log.i(TAG, "GPT partition entry size = ${gptHeader.partitionEntrySize}")

        val entrySize = gptHeader.partitionEntrySize.toInt()
        val entryCount = gptHeader.partitionEntryCount.toInt()

        if (entrySize <= 0 || entryCount <= 0) {
            Log.i(TAG, "GPT partition-entry information is invalid")
            return
        }

        val totalEntryBytes = entrySize * entryCount
        val blocksNeeded = (totalEntryBytes + blockSize - 1) / blockSize

        Log.i(
            TAG,
            "Reading GPT partition entries at LBA ${gptHeader.partitionEntryLba}"
        )
        Log.i(
            TAG,
            "GPT partition table size = $totalEntryBytes bytes ($blocksNeeded blocks)"
        )

        val entryTransaction = executor.read10(
            lba = gptHeader.partitionEntryLba,
            blockCount = blocksNeeded,
            blockSize = blockSize
        )

        ScsiDebug.transaction(entryTransaction)

        if (!entryTransaction.success) {
            Log.i(TAG, "GPT partition-entry read failed")
            return
        }

        val entryData = entryTransaction.data ?: run {
            Log.i(TAG, "GPT partition entries returned no data")
            return
        }

        val gptPartitions = GptPartitionTable.parseEntries(
            data = entryData,
            header = gptHeader
        )

        Log.i(TAG, "GPT partitions found = ${gptPartitions.size}")

        // Use a regular for loop instead of forEach to avoid type inference issues
        for (partition in gptPartitions) {
            Log.i(
                TAG,
                "GPT Partition ${partition.index}: " +
                        "type=${partition.typeGuid}, " +
                        "start=${partition.startLba}, " +
                        "end=${partition.endLba}, " +
                        "name=${partition.name}"
            )

            // APFS partition type GUID: 7C3457EF-0000-11AA-AA11-00306543ECAC
            if (partition.typeGuid.equals("7C3457EF-0000-11AA-AA11-00306543ECAC", ignoreCase = true)) {
                Log.i(TAG, "APFS partition detected!")
                Log.i(TAG, "APFS partition starts at LBA ${partition.startLba}")

                // Parse APFS filesystem
                val files = parseApfsFilesystem(partition.startLba, blockSize)
                if (files != null && files.isNotEmpty()) {
                    Log.i(TAG, "Found ${files.size} files/directories in APFS volume")
                    for (file in files) {
                        Log.i(TAG, "  ${if (file.isDirectory) "[DIR]" else "[FILE]"} ${file.name} (${file.fileSize} bytes)")
                    }
                }
            }
        }
    }

    private fun probeFilesystem(
        partitionStartLba: Long,
        blockSize: Int
    ) {
        val executor =
            scsiExecutor
                ?: return

        val probeOffsets = listOf(
            0L,
            1L,
            2L,
            3L,
            4L,
            5L,
            6L,
            7L
        )

        Log.i(
            TAG,
            "Scanning partition for filesystem boot sector..."
        )

        for (offset in probeOffsets) {

            val lba = partitionStartLba + offset

            val transaction = executor.read10(
                lba = lba,
                blockCount = 1,
                blockSize = blockSize
            )

            val sector =
                transaction.data
                    ?: continue


            val nonZeroBytes =
                sector.count { it.toInt() != 0 }

            Log.i(
                TAG,
                "LBA $lba: non-zero bytes = $nonZeroBytes"
            )


            val filesystem =
                FilesystemDetector.detect(sector)

            Log.i(
                TAG,
                "LBA $lba: $filesystem"
            )

            if (offset == 0L || nonZeroBytes > 0) {
                Log.i(
                    TAG,
                    "LBA $lba first 64 bytes = ${
                        sector
                            .take(64)
                            .joinToString(" ") {
                                "%02X".format(it)
                            }
                    }"
                )
            }

            if (filesystem != FilesystemType.UNKNOWN) {
                Log.i(
                    TAG,
                    "Filesystem detected: $filesystem"
                )

                Log.i(
                    TAG,
                    "Filesystem boot sector LBA = $lba"
                )

                return
            }
        }

        Log.i(
            TAG,
            "Filesystem boot sector not found in probe range"
        )
    }

    override fun close() {
        if (claimed) {
            massStorage?.let {
                connection?.releaseInterface(
                    it.usbInterface
                )
            }
        }

        connection?.close()

        connection = null
        massStorage = null
        transport = null
        scsiExecutor = null
        connectionInfo = null
        claimed = false
    }

    override fun readSector(lba: Long): ByteArray? {
        val executor = scsiExecutor ?: return null
        return executor.read10(lba = lba, blockCount = 1, blockSize = sectorSize()).data
    }

    override fun writeSector(lba: Long, data: ByteArray): Boolean {
        val executor = scsiExecutor ?: return false
        val result = executor.write10(lba = lba, data = data, blockSize = sectorSize())
        return result.success
    }

    override fun flushCache(): Boolean {
        val executor = scsiExecutor ?: return false
        val command = byteArrayOf(
            com.fatalpuppet.volumex.storage.scsi.ScsiOpcodes.SYNCHRONIZE_CACHE,
            0, 0, 0, 0, 0, 0, 0, 0, 0
        )
        val cbw = com.fatalpuppet.volumex.storage.scsi.CommandBlockWrapper(
            tag = com.fatalpuppet.volumex.storage.scsi.CommandTagGenerator.next(),
            dataTransferLength = 0,
            flags = 0x00.toByte(),
            lun = 0,
            commandLength = command.size.toByte(),
            command = command
        )
        val result = executor.execute("SYNCHRONIZE CACHE", cbw, 0)
        return result.success
    }

    override fun sectorSize(): Int = 512


    // Also update testUnitReady():
    fun testUnitReady(): ScsiTransaction {
        val executor = scsiExecutor ?: return ScsiTransaction(
            "TEST UNIT READY",
            false,
            0,
            "Transport unavailable"
        )

        val testCommand = ScsiCommand.testUnitReady()
        val cbw = CommandBlockWrapper(
            tag = CommandTagGenerator.next(),
            dataTransferLength = 0,
            flags = 0x00.toByte(),
            lun = 0,
            commandLength = testCommand.size.toByte(),
            command = testCommand
        )

        return executor.execute(
            "TEST UNIT READY",
            cbw,
            0
        )
    }

    override fun isOpen(): Boolean =
        connection != null &&
                claimed &&
                transport != null
}