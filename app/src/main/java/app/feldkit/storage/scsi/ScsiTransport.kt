package app.feldkit.storage.scsi

interface ScsiTransport {

    fun testUnitReady(): ScsiResult

    fun inquiry(): ScsiResult

    fun readCapacity(): ScsiResult

    fun readSector(
        lba: Long
    ): ScsiResult

}