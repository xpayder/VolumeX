package app.feldkit.storage.scsi

data class ScsiInquiryResponse(
    val peripheralDeviceType: Int,
    val removable: Boolean,
    val scsiVersion: Int,
    val vendor: String,
    val product: String,
    val revision: String
)

object ScsiInquiryResponseParser {

    fun parse(data: ByteArray): ScsiInquiryResponse? {

        if (data.size < 36) {
            return null
        }

        val peripheralDeviceType =
            data[0].toInt() and 0x1F

        val removable =
            (data[1].toInt() and 0x80) != 0

        val scsiVersion =
            data[2].toInt() and 0xFF

        val vendor =
            data.copyOfRange(8, 16)
                .toString(Charsets.US_ASCII)
                .trim()

        val product =
            data.copyOfRange(16, 32)
                .toString(Charsets.US_ASCII)
                .trim()

        val revision =
            data.copyOfRange(32, 36)
                .toString(Charsets.US_ASCII)
                .trim()

        return ScsiInquiryResponse(
            peripheralDeviceType = peripheralDeviceType,
            removable = removable,
            scsiVersion = scsiVersion,
            vendor = vendor,
            product = product,
            revision = revision
        )
    }
}