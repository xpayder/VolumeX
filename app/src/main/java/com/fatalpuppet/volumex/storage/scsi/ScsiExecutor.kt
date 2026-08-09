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
}