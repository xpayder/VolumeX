package com.fatalpuppet.volumex.storage.scsi

import com.fatalpuppet.volumex.storage.usb.UsbStorageConstants
import java.nio.ByteBuffer
import java.nio.ByteOrder
object CommandBlockWrapperBuilder {
    fun build(
        cbw: CommandBlockWrapper
    ): ByteArray {
        val buffer = ByteBuffer
            .allocate(31)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(UsbStorageConstants.CBW_SIGNATURE)
        buffer.putInt(cbw.tag)
        buffer.putInt(cbw.dataTransferLength)
        buffer.put(cbw.flags)
        buffer.put(cbw.lun)
        buffer.put(cbw.commandLength)
        buffer.put(cbw.command)
        repeat(16 - cbw.command.size) {
            buffer.put(0)
        }
        return buffer.array()
    }
}