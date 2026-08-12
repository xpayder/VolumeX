package com.fatalpuppet.volumex.storage.scsi

import com.fatalpuppet.volumex.storage.usb.BulkOnlyTransport

class ScsiExecutor(
    private val transport: BulkOnlyTransport
) {
    fun execute(
        name: String,
        cbw: CommandBlockWrapper,
        expectedLength: Int
    ): ScsiTransaction {
        val (result, elapsed) =
            TransactionTimer.measure {
                transport.execute(
                    CommandBlockWrapperBuilder.build(cbw),
                    expectedLength
                )
            }
        return ScsiTransaction(
            command = name,
            success = result.success,
            elapsedMs = elapsed,
            message = result.message,
            data = result.data
        )
    }
    fun inquiry(): ScsiTransaction {
        val cbw = CommandBlockWrapper(
            tag = CommandTagGenerator.next(),
            dataTransferLength = 36,
            flags = 0x80.toByte(),
            lun = 0,
            commandLength = 6,
            command = ScsiInquiry.command()
        )

        return execute(
            name = "INQUIRY",
            cbw = cbw,
            expectedLength = 36
        )
    }

    fun readCapacity(): ScsiTransaction {
        val cbw = CommandBlockWrapper(
            tag = CommandTagGenerator.next(),
            dataTransferLength = 8,
            flags = 0x80.toByte(),
            lun = 0,
            commandLength = 10,
            command = ScsiReadCapacity.command()
        )

        return execute(
            name = "READ CAPACITY(10)",
            cbw = cbw,
            expectedLength = 8
        )
    }


    fun read10(
        lba: Long,
        blockCount: Int,
        blockSize: Int
    ): ScsiTransaction {

        val transferLength = blockCount * blockSize

        val cbw = CommandBlockWrapper(
            tag = CommandTagGenerator.next(),
            dataTransferLength = transferLength,
            flags = 0x80.toByte(),
            lun = 0,
            commandLength = 10,
            command = ScsiRead10.command(
                lba = lba,
                transferLength = blockCount
            )
        )

        android.util.Log.d(
            "VolumeX",
            "READ(10) CDB = ${
                cbw.command.joinToString(" ") {
                    "%02X".format(it)
                }
            }"
        )

        android.util.Log.d(
            "VolumeX",
            "READ(10) CBW tag=${cbw.tag} " +
                    "transferLength=${cbw.dataTransferLength} " +
                    "flags=%02X".format(cbw.flags) +
                    " commandLength=${cbw.commandLength}"
        )

        return execute(
            name = "READ(10) LBA $lba",
            cbw = cbw,
            expectedLength = transferLength
        )
    }



}