package com.fatalpuppet.volumex.storage.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import com.fatalpuppet.volumex.storage.disk.BlockDeviceReader
import com.fatalpuppet.volumex.storage.filesystem.FilesystemDetector
import com.fatalpuppet.volumex.storage.filesystem.FilesystemType
import com.fatalpuppet.volumex.storage.filesystem.partition.MbrPartitionTable
import com.fatalpuppet.volumex.storage.scsi.ScsiCapacityResponseParser
import com.fatalpuppet.volumex.storage.scsi.ScsiCommandFactory
import com.fatalpuppet.volumex.storage.scsi.ScsiDebug
import com.fatalpuppet.volumex.storage.scsi.ScsiExecutor
import com.fatalpuppet.volumex.storage.scsi.ScsiInquiryResponseParser
import com.fatalpuppet.volumex.storage.scsi.ScsiTransaction
import com.fatalpuppet.volumex.storage.filesystem.exfat.ExFatBootSector
import com.fatalpuppet.volumex.storage.filesystem.exfat.ExFatBootSectorParser
import com.fatalpuppet.volumex.storage.filesystem.partition.GptPartitionTable
import com.fatalpuppet.volumex.storage.scsi.UasExecutor

class UsbBlockDeviceReader(
    private val usbManager: UsbManager,
    private val device: UsbDevice
) : BlockDeviceReader {

    companion object {
        private const val TAG = "VolumeX"
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

    override fun open(): Boolean {

        Log.i(
            "VolumeX",
            "========== UsbBlockDeviceReader.open() =========="
        )
        val usbConnection = usbManager.openDevice(device)

        if (usbConnection == null) {
            Log.e(TAG, "Unable to open USB device")
            return false
        }

        connection = usbConnection

        interfaceScanner.inspectDevice(device)

        Log.i(
            "VolumeX",
            "About to inspect USB mass-storage interfaces for UAS"
        )

        val uasInterface = interfaceScanner.findUasInterface(device)

        if (uasInterface != null) {
            Log.i(
                "VolumeX",
                "UAS interface DETECTED: interface=${uasInterface.interfaceNumber}"
            )

        } else {

            Log.i(
                "VolumeX",
                "UAS interface NOT detected"
            )
        }

        val storageInterface = interfaceScanner.findMassStorageInterface(device)

        if (storageInterface == null) {
            Log.e(TAG, "Mass Storage interface not found")
            close()
            return false
        }

        if (uasInterface != null) {

            Log.i(
                "VolumeX",
                "UAS interface detected at interface " +
                        uasInterface.interfaceNumber
            )

        } else {

            Log.i(
                "VolumeX",
                "No UAS interface detected"
            )
        }

        massStorage = storageInterface

        if (!usbConnection.claimInterface(
                storageInterface.usbInterface,
                true
            )
        ) {
            Log.e(TAG, "Unable to claim USB Mass Storage interface")
            close()
            return false
        }

        if (uasInterface != null) {

            val claimed = usbConnection.claimInterface(
                uasInterface!!.usbInterface,
                true
            )

            Log.i(
                "VolumeX",
                "UAS interface claim result = $claimed"
            )
        }

        val uasTransport = UsbUasTransport(
            connection = usbConnection,
            uasInterface = uasInterface!!
        )

        val uasExecutor = UasExecutor(uasTransport)

        val uasReady = uasExecutor.testUnitReady()

        Log.i(
            "VolumeX",
            "UAS TEST UNIT READY result = $uasReady"
        )

        claimed = true

        transport = BulkUsbTransport(
            connection = usbConnection,
            bulkIn = storageInterface.bulkIn,
            bulkOut = storageInterface.bulkOut
        )

        connectionInfo = getConnectionInfo()

        scsiExecutor = ScsiExecutor(
            BulkOnlyTransport(transport!!)
        )

        if (!runScsiChecks()) {
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

    private fun runScsiChecks(): Boolean {

        val executor =
            scsiExecutor
                ?: run {
                    Log.e(TAG, "SCSI executor unavailable")
                    return false
                }

        // TEST UNIT READY
        val ready = executor.execute(
            "TEST UNIT READY",
            ScsiCommandFactory.testUnitReady(),
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


    /**
     * Reads the primary GPT header at LBA 1 and, when valid, reads the
     * partition-entry array described by that header.
     */
    private fun readGptLayout(
        executor: ScsiExecutor,
        blockSize: Int
    ) {
        Log.i(TAG, "Reading GPT header at LBA 1")

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

        val gptHeader =
            GptPartitionTable.parseHeader(headerData)
                ?: run {
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

        gptPartitions.forEach { partition ->
            Log.i(
                TAG,
                "GPT Partition ${partition.index}: " +
                        "type=${partition.typeGuid}, " +
                        "start=${partition.startLba}, " +
                        "end=${partition.endLba}, " +
                        "name=${partition.name}"
            )
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

    override fun readSector(
        lba: Long
    ): ByteArray? {
        val executor = scsiExecutor ?: return null

        return executor.read10(
            lba = lba,
            blockCount = 1,
            blockSize = sectorSize()
        ).data
    }

    override fun sectorSize(): Int = 512

    fun testUnitReady(): ScsiTransaction {
        val executor =
            scsiExecutor
                ?: return ScsiTransaction(
                    "TEST UNIT READY",
                    false,
                    0,
                    "Transport unavailable"
                )

        return executor.execute(
            "TEST UNIT READY",
            ScsiCommandFactory.testUnitReady(),
            0
        )
    }

    override fun isOpen(): Boolean =
        connection != null &&
                claimed &&
                transport != null
}