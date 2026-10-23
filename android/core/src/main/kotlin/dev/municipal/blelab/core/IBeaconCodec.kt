package dev.municipal.blelab.core

data class IBeaconFrame(
    val uuid: String,
    val major: Int,
    val minor: Int,
    val measuredPower: Int,
    val identifier: Long,
)

data class ManufacturerFilter(
    val companyId: Int,
    val data: ByteArray,
    val mask: ByteArray,
)

object IBeaconCodec {
    const val PAYLOAD_BYTES = 23

    fun encodePayload(uuid: String, major: Int, minor: Int, measuredPower: Int): ByteArray {
        require(major in 0..0xFFFF && minor in 0..0xFFFF)
        val out = ByteArray(PAYLOAD_BYTES)
        out[0] = 0x02
        out[1] = 0x15
        parseUuidBytes(uuid).copyInto(out, destinationOffset = 2)
        out[18] = (major shr 8).toByte()
        out[19] = (major and 0xFF).toByte()
        out[20] = (minor shr 8).toByte()
        out[21] = (minor and 0xFF).toByte()
        out[22] = measuredPower.toByte()
        return out
    }

    fun decodePayload(payload: ByteArray): IBeaconFrame? {
        if (payload.size < PAYLOAD_BYTES) return null
        if (payload[0] != 0x02.toByte() || payload[1] != 0x15.toByte()) return null
        val uuid = formatUuid(payload.copyOfRange(2, 18))
        val major = ((payload[18].toInt() and 0xFF) shl 8) or (payload[19].toInt() and 0xFF)
        val minor = ((payload[20].toInt() and 0xFF) shl 8) or (payload[21].toInt() and 0xFF)
        val power = payload[22].toInt()
        val identifier = (major.toLong() shl 16) or minor.toLong()
        return IBeaconFrame(uuid, major, minor, power, identifier)
    }

    /**
     * Major, minor y measured power quedan fuera de la máscara para que la rotación no sea filtrada.
     * El dato de la máscara es el payload sin el company id, que es lo que espera ScanFilter.
     */
    fun manufacturerFilter(uuid: String): ManufacturerFilter {
        val data = encodePayload(uuid, major = 0, minor = 0, measuredPower = 0)
        val mask = ByteArray(data.size)
        mask[0] = 0xFF.toByte()
        mask[1] = 0xFF.toByte()
        for (index in 2 until 18) mask[index] = 0xFF.toByte()
        return ManufacturerFilter(LabConstants.APPLE_COMPANY_ID, data, mask)
    }

    /** 1 longitud + 1 tipo + 2 company id + 23 de payload. Flags opcionales de 3 bytes. */
    fun advertisingBytes(includeFlags: Boolean): Int = 27 + if (includeFlags) 3 else 0
}
