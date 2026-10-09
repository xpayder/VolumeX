package com.fatalpuppet.volumex.storage.scsi

object ScsiRead10 {

    fun command(
        lba: Long,
        transferLength: Int
    ): ByteArray {

        require(lba in 0..0xFFFFFFFFL)
        require(transferLength in 1..0xFFFF)

        // READ(10) CDB: opcode, flags, LBA[4] (bytes 2-5), group, length[2] (bytes 7-8), control.
        return ScsiCommand.read10(lba, transferLength)
    }
}