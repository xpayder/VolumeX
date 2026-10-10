package app.feldkit.storage.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import android.content.Intent
import android.app.PendingIntent
import app.feldkit.storage.disk.BlockDeviceReader
import app.feldkit.storage.filesystem.FilesystemDetector
import app.feldkit.storage.filesystem.FilesystemType
import app.feldkit.storage.filesystem.apfs.ApfsBTree
import app.feldkit.storage.filesystem.apfs.ApfsVolumeSuperblockParser
import app.feldkit.storage.filesystem.partition.MbrPartitionTable
import app.feldkit.storage.scsi.ScsiCapacityResponseParser
import app.feldkit.storage.scsi.ScsiDebug
import app.feldkit.storage.scsi.ScsiExecutor
import app.feldkit.storage.scsi.ScsiInquiryResponseParser
import app.feldkit.storage.scsi.ScsiTransaction
import app.feldkit.storage.filesystem.exfat.ExFatBootSector
import app.feldkit.storage.filesystem.exfat.ExFatBootSectorParser
import app.feldkit.storage.filesystem.apfs.ApfsContainerSuperblockParser
import app.feldkit.storage.filesystem.apfs.ApfsFileEntry
import app.feldkit.storage.filesystem.partition.GptPartitionTable
import app.feldkit.storage.scsi.CommandBlockWrapper
import app.feldkit.storage.scsi.CommandTagGenerator
import app.feldkit.storage.scsi.ScsiCommand

import app.feldkit.storage.scsi.UasExecutor

class UsbBlockDeviceReader(
    private val usbManager: UsbManager,
    private val device: UsbDevice
) : BlockDeviceReader {

    companion object {
        private const val TAG = "FeldKit"
        private const val ENABLE_UAS = false
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

        // UAS transport is not wired into ScsiExecutor yet (only BOT is), so stay on BOT.
        if (ENABLE_UAS && uasInterface != null) {
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
        val bot = BulkOnlyTransport(transport!!)
        // Start from a known state even if a previous session left the drive mid-command.
        bot.resetRecovery(storageInterface.usbInterface.id)
        scsiExecutor = ScsiExecutor(bot)

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
            "FeldKit",
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
            "FeldKit",
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

        // The first command after connecting commonly fails with UNIT ATTENTION
        // (and a spun-down SSD/HDD may need a moment): read sense data and retry.
        var ready = executor.execute("TEST UNIT READY", readyCbw, 0)
        var attempt = 1
        while (!ready.success && attempt < 10) {
            ScsiDebug.transaction(ready)
            val sense = executor.requestSense().data
            if (sense != null && sense.size >= 14) {
                Log.i(TAG, "TUR attempt $attempt failed, sense key=0x%02X ASC=0x%02X ASCQ=0x%02X".format(
                    sense[2].toInt() and 0x0F, sense[12].toInt() and 0xFF, sense[13].toInt() and 0xFF))
            }
            Thread.sleep(300)
            attempt++
            ready = executor.execute(
                "TEST UNIT READY",
                readyCbw.copy(tag = CommandTagGenerator.next()),
                0
            )
        }

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
                    "FeldKit",
                    "exFAT filesystem detected"
                )

                Log.i(
                    "FeldKit",
                    "FAT offset = ${exFat.fatOffset}"
                )

                Log.i(
                    "FeldKit",
                    "FAT length = ${exFat.fatLength}"
                )

                Log.i(
                    "FeldKit",
                    "Cluster heap offset = ${exFat.clusterHeapOffset}"
                )

                Log.i(
                    "FeldKit",
                    "Cluster count = ${exFat.clusterCount}"
                )

                Log.i(
                    "FeldKit",
                    "Root directory cluster = ${exFat.rootDirectoryCluster}"
                )

                Log.i(
                    "FeldKit",
                    "Bytes per sector = ${exFat.bytesPerSector}"
                )

                Log.i(
                    "FeldKit",
                    "Sectors per cluster = ${exFat.sectorsPerCluster}"
                )

                Log.i(
                    "FeldKit",
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
        transport?.closePool()
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

    // 4 KB-aligned read cache (FAT/directory/B-tree access is heavily repetitive); write-through invalidation.
    private val cache = object : LinkedHashMap<Long, ByteArray>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > 512
    }
    private val chunkSectors = 8

    private fun ifaceId() = massStorage?.usbInterface?.id ?: 0

    /** Rejected large command: remember to stay at 128 KB per command from now on and reset the BOT state. */
    private fun fallBackToSmallCommands(executor: app.feldkit.storage.scsi.ScsiExecutor) {
        Log.w(TAG, "drive rejected a ${UsbTuning.maxCommandSectors}-sector command; using ${UsbTuning.SAFE_COMMAND_SECTORS}")
        UsbTuning.maxCommandSectors = UsbTuning.SAFE_COMMAND_SECTORS
        executor.resetRecovery(ifaceId())
    }

    private fun rawRead(lba: Long, count: Int): ByteArray? {
        val executor = scsiExecutor ?: return null
        val out = ByteArray(count * 512)
        var done = 0
        while (done < count) {
            var n = minOf(UsbTuning.maxCommandSectors, count - done)
            var data = executor.read10(lba = lba + done, blockCount = n, blockSize = 512).data
            if ((data == null || data.size < n * 512) && n > UsbTuning.SAFE_COMMAND_SECTORS) {
                fallBackToSmallCommands(executor)
                n = minOf(UsbTuning.SAFE_COMMAND_SECTORS, count - done)
                data = executor.read10(lba = lba + done, blockCount = n, blockSize = 512).data
            }
            if (data == null || data.size < n * 512) return null
            System.arraycopy(data, 0, out, done * 512, n * 512)
            done += n
        }
        return out
    }

    /** Sequential read speed of the drive in MB/s over [megabytes] MB taken from the middle of the disk (not a filesystem test). */
    @Synchronized
    fun measureReadSpeed(megabytes: Int): Double? {
        val total = sectorCount()
        val sectors = megabytes * 2048
        if (total < sectors * 2L) return null
        val start = (total / 2) - (total / 2) % 8
        val t0 = System.nanoTime()
        var done = 0
        while (done < sectors) {
            val n = minOf(2048, sectors - done)
            rawRead(start + done, n) ?: return null
            done += n
        }
        val sec = (System.nanoTime() - t0) / 1e9
        return megabytes / sec
    }

    @Synchronized
    override fun readSector(lba: Long): ByteArray? {
        val chunk = lba / chunkSectors
        val cached = synchronized(cache) { cache[chunk] }
        val data = cached ?: (rawRead(chunk * chunkSectors, chunkSectors) ?: return rawRead(lba, 1))
            .also { d -> synchronized(cache) { cache[chunk] = d } }
        val o = ((lba % chunkSectors) * 512).toInt()
        return data.copyOfRange(o, o + 512)
    }

    @Synchronized
    override fun readSectors(startLba: Long, count: Int): ByteArray? {
        if (count <= 0) return ByteArray(0)
        if (count < chunkSectors) return super.readSectors(startLba, count)   // via the cache
        return rawRead(startLba, count)
    }

    override fun dropReadCache() { synchronized(cache) { cache.clear() } }

    private fun invalidate(lba: Long, count: Int) = synchronized(cache) {
        for (c in (lba / chunkSectors)..((lba + count - 1) / chunkSectors)) cache.remove(c)
    }

    @Synchronized
    override fun writeSector(lba: Long, data: ByteArray): Boolean {
        val executor = scsiExecutor ?: return false
        val result = executor.write10(lba = lba, data = data, blockSize = sectorSize())
        invalidate(lba, 1)
        return result.success
    }

    @Synchronized
    override fun writeSectors(startLba: Long, data: ByteArray): Boolean {
        val executor = scsiExecutor ?: return false
        val total = data.size / 512
        var done = 0
        while (done < total) {
            var n = minOf(UsbTuning.maxCommandSectors, total - done)
            var r = executor.write10(lba = startLba + done, data = data.copyOfRange(done * 512, (done + n) * 512), blockSize = 512)
            if (!r.success && n > UsbTuning.SAFE_COMMAND_SECTORS) {
                fallBackToSmallCommands(executor)
                n = minOf(UsbTuning.SAFE_COMMAND_SECTORS, total - done)
                r = executor.write10(lba = startLba + done, data = data.copyOfRange(done * 512, (done + n) * 512), blockSize = 512)
            }
            if (!r.success) { invalidate(startLba, total); return false }
            done += n
        }
        invalidate(startLba, total)
        return true
    }

    @Synchronized
    override fun flushCache(): Boolean {
        val executor = scsiExecutor ?: return false
        val command = byteArrayOf(
            app.feldkit.storage.scsi.ScsiOpcodes.SYNCHRONIZE_CACHE,
            0, 0, 0, 0, 0, 0, 0, 0, 0
        )
        val cbw = app.feldkit.storage.scsi.CommandBlockWrapper(
            tag = app.feldkit.storage.scsi.CommandTagGenerator.next(),
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