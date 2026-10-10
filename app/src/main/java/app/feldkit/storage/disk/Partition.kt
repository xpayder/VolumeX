package app.feldkit.storage.disk

data class Partition(

    val name: String,

    val startSector: Long,

    val sectorCount: Long,

    val typeGuid: String

)