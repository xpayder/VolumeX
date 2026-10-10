package app.feldkit.storage.disk

object HexDump {

    fun format(
        data: ByteArray
    ): String {
        return data.joinToString(" ") {
            "%02X".format(it)
        }
    }

    fun firstBytes(
        data: ByteArray,
        count: Int = 32
    ): String {
        return format(
            data.take(count).toByteArray()
        )
    }

}