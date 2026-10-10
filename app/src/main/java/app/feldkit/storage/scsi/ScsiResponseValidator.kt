package app.feldkit.storage.scsi

object ScsiResponseValidator {
    fun validCsw(
        csw: CommandStatusWrapper
    ): Boolean {
        return csw.status.toInt() == 0
    }
}