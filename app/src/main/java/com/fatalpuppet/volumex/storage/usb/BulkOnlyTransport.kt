package com.fatalpuppet.volumex.storage.usb

import com.fatalpuppet.volumex.storage.scsi.CommandStatusWrapperParser
import com.fatalpuppet.volumex.storage.scsi.ScsiResponseValidator
import com.fatalpuppet.volumex.storage.scsi.ScsiResult

class BulkOnlyTransport(
    private val transport: BulkUsbTransport
) {
    fun execute(
        cbw: ByteArray,
        expectedLength: Int
    ): ScsiResult {

        val send = transport.send(cbw)

        if (!send.success) {
            return ScsiResult(
                false,
                null,
                "CBW transfer failed"
            )
        }

        val payload =
            if (expectedLength > 0)
                transport.receive(expectedLength)
            else
                BulkTransferResult(true, 0, null)

        val csw = transport.receive(13)

        if (!csw.success || csw.data == null) {
            return ScsiResult(
                false,
                null,
                "CSW transfer failed"
            )
        }

        val status =
            CommandStatusWrapperParser.parse(csw.data)

        if (!ScsiResponseValidator.validCsw(status)) {
            return ScsiResult(
                false,
                payload.data,
                "SCSI status ${status.status}"
            )
        }

        return ScsiResult(
            true,
            payload.data,
            "OK"
        )
    }
}