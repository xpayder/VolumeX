package com.fatalpuppet.volumex.storage.usb

import android.hardware.usb.*
import android.util.Log
import com.fatalpuppet.volumex.storage.disk.BlockDeviceReader
import com.fatalpuppet.volumex.storage.scsi.ScsiCommandFactory
import com.fatalpuppet.volumex.storage.scsi.ScsiDebug
import com.fatalpuppet.volumex.storage.scsi.ScsiExecutor
import com.fatalpuppet.volumex.storage.scsi.ScsiInquiryResponseParser
import com.fatalpuppet.volumex.storage.scsi.ScsiTransaction

class UsbBlockDeviceReader(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
) : BlockDeviceReader {
    private var connection: UsbDeviceConnection? = null
    private val interfaceScanner = UsbInterfaceScanner()
    private var massStorage: UsbMassStorageInterface? = null
    private var claimed = false
    private var transport: BulkUsbTransport? = null
    private var connectionInfo: UsbConnectionInfo? = null
    private var scsiExecutor: ScsiExecutor? = null

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
        connection = usbManager.openDevice(device)
        if (connection == null) {
            Log.e(
                "VolumeX",
                "UsbBlockDeviceReader.open(): openDevice() returned null"
            )
            return false
        }
        Log.d(
            "VolumeX",
            "UsbDeviceConnection opened"
        )

        interfaceScanner.inspectDevice(device)

        massStorage =
            interfaceScanner.findMassStorageInterface(device)

        if (massStorage == null) {
            Log.e(
                "VolumeX",
                "UsbBlockDeviceReader.open(): Mass Storage interface not found"
            )
            return false
        }
        Log.d(
            "VolumeX",
            "Mass Storage interface found"
        )

        claimed = connection!!.claimInterface(
            massStorage!!.usbInterface,
            true
        )
        Log.d(
            "VolumeX",
            "USB interface claimed = $claimed"
        )
        if (!claimed) {
            Log.e(
                "VolumeX",
                "UsbBlockDeviceReader.open(): claimInterface() failed"
            )
            return false
        }

        Log.d("VolumeX", "UsbBlockDeviceReader.open()")
        if (claimed) {
            transport = BulkUsbTransport(
                connection = connection!!,
                bulkIn = massStorage!!.bulkIn,
                bulkOut = massStorage!!.bulkOut
            )
            connectionInfo = getConnectionInfo()
        }
        val bulkTransport = BulkOnlyTransport(
            transport!!
        )
        scsiExecutor = ScsiExecutor(bulkTransport)
        Log.d("VolumeX", "SCSI Executor created")

        val transaction = testUnitReady()
        Log.d("VolumeX", "Calling TEST UNIT READY")

        Log.d("VolumeX", "Logging transaction")

/*
        Log.d("VolumeX", "Calling SCSI INQUIRY")
        val inquiryTransaction = scsiExecutor?.inquiry()
        ScsiDebug.transaction(transaction)
        inquiryTransaction.data?.let { data ->
            Log.d(
                "VolumeX",
                "INQUIRY response length = ${data.size}"
            )
            Log.d(
                "VolumeX",
                "INQUIRY raw = ${
                    data.joinToString(" ") {
                        "%02X".format(it)
                    }
                }"
            )
        }



        if (inquiryTransaction == null) {
            Log.e(
                "VolumeX",
                "SCSI INQUIRY: executor unavailable"
            )
        } else {
            Log.d(
                "VolumeX",
                "SCSI INQUIRY success = ${inquiryTransaction.success}"
            )

            Log.d(
                "VolumeX",
                "SCSI INQUIRY message = ${inquiryTransaction.message}"
            )

            ScsiDebug.transaction(inquiryTransaction)
        }


*/

        Log.d("VolumeX", "Calling SCSI INQUIRY")

        val inquiryTransaction = scsiExecutor?.inquiry()

        if (inquiryTransaction != null) {
            ScsiDebug.transaction(inquiryTransaction)
            inquiryTransaction.data?.let { data ->
                Log.d(
                    "VolumeX",
                    "INQUIRY response length = ${data.size}"
                )
                Log.d(
                    "VolumeX",
                    "INQUIRY raw = ${
                        data.joinToString(" ") {
                            "%02X".format(it)
                        }
                    }"
                )

                val inquiry =
                    ScsiInquiryResponseParser.parse(data)

                if (inquiry != null) {

                    Log.i(
                        "VolumeX",
                        "INQUIRY Vendor = ${inquiry.vendor}"
                    )

                    Log.i(
                        "VolumeX",
                        "INQUIRY Product = ${inquiry.product}"
                    )

                    Log.i(
                        "VolumeX",
                        "INQUIRY Revision = ${inquiry.revision}"
                    )

                    Log.i(
                        "VolumeX",
                        "INQUIRY Removable = ${inquiry.removable}"
                    )

                    Log.i(
                        "VolumeX",
                        "INQUIRY Device Type = ${inquiry.peripheralDeviceType}"
                    )

                    Log.i(
                        "VolumeX",
                        "INQUIRY SCSI Version = ${inquiry.scsiVersion}"
                    )
                }

            }
        } else {
            Log.e(
                "VolumeX",
                "SCSI INQUIRY: executor unavailable"
            )
        }
        Log.d("VolumeX", "USB interface claimed = $claimed")
        return claimed
    }
    override fun close() {
        if (claimed) {
            massStorage?.let {
                connection?.releaseInterface(
                    it.usbInterface
                )
                Log.d(
                    "VolumeX",
                    "USB interface released"
                )
            }
        }
        connection?.close()
        connection = null
        claimed = false
    }
    override fun readSector(
        lba: Long
    ): ByteArray? {
        return null
    }
    override fun sectorSize(): Int {
        return 512
    }

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