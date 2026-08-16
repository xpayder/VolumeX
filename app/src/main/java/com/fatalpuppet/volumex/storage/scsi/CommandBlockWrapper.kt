// file: app/src/main/java/com/fatalpuppet/volumex/storage/scsi/CommandBlockWrapper.kt
package com.fatalpuppet.volumex.storage.scsi

import com.fatalpuppet.volumex.storage.usb.UsbStorageConstants
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

// Data class for Command Block Wrapper
data class CommandBlockWrapper(
    val tag: Int,
    val dataTransferLength: Int,
    val flags: Byte,
    val lun: Byte,
    val commandLength: Byte,
    val command: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CommandBlockWrapper
        if (tag != other.tag) return false
        if (dataTransferLength != other.dataTransferLength) return false
        if (flags != other.flags) return false
        if (lun != other.lun) return false
        if (commandLength != other.commandLength) return false
        if (!command.contentEquals(other.command)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = tag
        result = 31 * result + dataTransferLength
        result = 31 * result + flags.toInt()
        result = 31 * result + lun.toInt()
        result = 31 * result + commandLength.toInt()
        result = 31 * result + command.contentHashCode()
        return result
    }
}

// Helper object for generating tags
object CommandTagGenerator {
    private val counter = AtomicInteger(1)

    fun next(): Int {
        return counter.getAndIncrement()
    }
}

// Builder that converts CommandBlockWrapper to ByteArray
object CommandBlockWrapperBuilder {
    fun build(cbw: CommandBlockWrapper): ByteArray {
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

        // Pad remaining bytes with 0
        repeat(16 - cbw.command.size) {
            buffer.put(0)
        }

        return buffer.array()
    }
}