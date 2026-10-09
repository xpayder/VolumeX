// file: app/src/main/java/com/fatalpuppet/volumex/storage/scsi/ScsiExecutor.kt
package com.fatalpuppet.volumex.storage.scsi

import com.fatalpuppet.volumex.storage.usb.BulkOnlyTransport
import android.util.Log

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
                // Build the CBW byte array and execute
                val cbwBytes = CommandBlockWrapperBuilder.build(cbw)
                transport.execute(cbwBytes, expectedLength)
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
        val command = ScsiInquiry.command()
        val cbw = CommandBlockWrapper(
            tag = CommandTagGenerator.next(),
            dataTransferLength = 36,
            flags = 0x80.toByte(),
            lun = 0,
            commandLength = command.size.toByte(),
            command = command
        )

        return execute(
            name = "INQUIRY",
            cbw = cbw,
            expectedLength = 36
        )
    }

    fun readCapacity(): ScsiTransaction {
        val command = ScsiReadCapacity.command()
        val cbw = CommandBlockWrapper(
            tag = CommandTagGenerator.next(),
            dataTransferLength = 8,
            flags = 0x80.toByte(),
            lun = 0,
            commandLength = command.size.toByte(),
            command = command
        )

        return execute(
            name = "READ CAPACITY(10)",
            cbw = cbw,
            expectedLength = 8
        )
    }

    fun read10(lba: Long, blockCount: Int, blockSize: Int): ScsiTransaction {
        val transferLength = blockCount * blockSize
        val command = ScsiRead10.command(
            lba = lba,
            transferLength = blockCount
        )

        val cbw = CommandBlockWrapper(
            tag = CommandTagGenerator.next(),
            dataTransferLength = transferLength,
            flags = 0x80.toByte(),
            lun = 0,
            commandLength = command.size.toByte(),
            command = command
        )

        Log.d(
            "VolumeX",
            "READ(10) CDB = ${
                cbw.command.joinToString(" ") {
                    "%02X".format(it)
                }
            }"
        )

        Log.d(
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

    fun write10(lba: Long, data: ByteArray, blockSize: Int): ScsiTransaction {
        val blockCount = data.size / blockSize
        val command = byteArrayOf(
            ScsiOpcodes.WRITE_10,
            0x00,
            ((lba shr 24) and 0xFF).toByte(),
            ((lba shr 16) and 0xFF).toByte(),
            ((lba shr 8) and 0xFF).toByte(),
            (lba and 0xFF).toByte(),
            0x00,
            ((blockCount shr 8) and 0xFF).toByte(),
            (blockCount and 0xFF).toByte(),
            0x00
        )

        val cbw = CommandBlockWrapper(
            tag = CommandTagGenerator.next(),
            dataTransferLength = data.size,
            flags = 0x00.toByte(), // DATA OUT
            lun = 0,
            commandLength = command.size.toByte(),
            command = command
        )

        val cbwBytes = CommandBlockWrapperBuilder.build(cbw)
        val (result, elapsed) = TransactionTimer.measure {
            transport.executeDataOut(cbwBytes, data)
        }
        return ScsiTransaction(
            command = "WRITE(10) LBA $lba",
            success = result.success,
            elapsedMs = elapsed,
            message = result.message,
            data = null
        )
    }
}