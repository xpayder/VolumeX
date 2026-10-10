package app.feldkit.utils

object HexUtils {
    fun toHex(
        data: ByteArray
    ): String {
        return data.joinToString(" ") {
            "%02X".format(it)
        }
    }
    fun Long.toHex(): String {

        return "0x" + this.toString(16).uppercase()

    }
}