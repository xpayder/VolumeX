package app.feldkit.storage.scsi

import java.nio.ByteBuffer
import java.nio.ByteOrder

object CommandStatusWrapperParser {

    fun parse(data: ByteArray): CommandStatusWrapper {

        val buffer = ByteBuffer
            .wrap(data)
            .order(ByteOrder.LITTLE_ENDIAN)

        buffer.int // Signature

        val tag = buffer.int

        val residue = buffer.int

        val status = buffer.get()

        return CommandStatusWrapper(

            tag = tag,

            residue = residue,

            status = status

        )

    }

}