package dev.municipal.blelab.core

data class CustomFrame(
    val version: Int,
    val flags: Int,
    val identifier: Long,
    val periodSeconds: Int,
    val measuredPower: Int,
) {
    val timeDerived: Boolean get() = flags and FLAG_TIME_DERIVED != 0
    val major: Int get() = ((identifier shr 16) and 0xFFFF).toInt()
    val minor: Int get() = (identifier and 0xFFFF).toInt()

    companion object {
        const val FLAG_TIME_DERIVED = 0x01
    }
}

sealed class CustomDecode {
    data class Frame(val frame: CustomFrame) : CustomDecode()
    data class UnsupportedVersion(val version: Int) : CustomDecode()
    data object Malformed : CustomDecode()
}

object CustomCodec {
    const val PAYLOAD_BYTES = 9

    fun encode(
        identifier: Long,
        periodSeconds: Int,
        measuredPower: Int,
        timeDerived: Boolean,
        version: Int = LabConstants.PROTOCOL_VERSION,
    ): ByteArray {
        val out = ByteArray(PAYLOAD_BYTES)
        val masked = identifier and 0xFFFFFFFFL
        out[0] = version.toByte()
        out[1] = (if (timeDerived) CustomFrame.FLAG_TIME_DERIVED else 0).toByte()
        out[2] = (masked shr 24).toByte()
        out[3] = (masked shr 16).toByte()
        out[4] = (masked shr 8).toByte()
        out[5] = masked.toByte()
        out[6] = ((periodSeconds shr 8) and 0xFF).toByte()
        out[7] = (periodSeconds and 0xFF).toByte()
        out[8] = measuredPower.toByte()
        return out
    }

    fun decode(payload: ByteArray): CustomDecode {
        if (payload.size < PAYLOAD_BYTES) return CustomDecode.Malformed
        val version = payload[0].toInt() and 0xFF
        if (version != LabConstants.PROTOCOL_VERSION) return CustomDecode.UnsupportedVersion(version)
        val flags = payload[1].toInt() and 0xFF
        val identifier = ((payload[2].toLong() and 0xFF) shl 24) or
            ((payload[3].toLong() and 0xFF) shl 16) or
            ((payload[4].toLong() and 0xFF) shl 8) or
            (payload[5].toLong() and 0xFF)
        val period = ((payload[6].toInt() and 0xFF) shl 8) or (payload[7].toInt() and 0xFF)
        val power = payload[8].toInt()
        return CustomDecode.Frame(CustomFrame(version, flags, identifier, period, power))
    }

    /** 1 longitud + 1 tipo 0x21 + 16 de UUID + 9 de valor. Flags opcionales de 3 bytes. */
    fun advertisingBytes(includeFlags: Boolean): Int = 27 + if (includeFlags) 3 else 0
}
