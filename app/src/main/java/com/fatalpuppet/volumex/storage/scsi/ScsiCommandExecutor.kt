package com.fatalpuppet.volumex.storage.scsi

import com.fatalpuppet.volumex.storage.usb.BulkUsbTransport

class ScsiCommandExecutor(
    private val transport: BulkUsbTransport
) {
    fun execute(
        cbw: ByteArray,
        expectedLength: Int
    ): ScsiResult {

        val send = transport.send(cbw)
        ScsiCommandLogger.sent(cbw)

        if (!send.success) {
            return ScsiResult(
                false,
                null,
                "Failed sending CBW"
            )
        }

        val data = transport.receive(expectedLength)
        data.data?.let {
            ScsiCommandLogger.received(it)
        }

        if (!data.success) {
            return ScsiResult(
                false,
                null,
                "Failed reading data"
            )
        }

        return ScsiResult(
            true,
            data.data,
            "OK"
        )
    }

    fun inquiry(): ScsiResult {
        return execute(
            ScsiInquiry.command(),
            36
        )
    }

    fun testUnitReady(): ScsiResult {

        val command = ScsiTestUnitReady.command()
        val cbw = CommandBlockWrapper(
            tag = CommandTagGenerator.next(),
            dataTransferLength = 0,
            flags = 0.toByte(),
            lun = 0.toByte(),
            commandLength = command.size.toByte(),
            command = command
        )

        return execute(
            CommandBlockWrapperBuilder.build(cbw),
            ScsiTestUnitReady.EXPECTED_LENGTH
        )

    }



}