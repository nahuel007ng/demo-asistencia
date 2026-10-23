package dev.municipal.blelab.core

fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "Hex de longitud impar" }
    return ByteArray(hex.length / 2) { index ->
        hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

fun parseUuidBytes(uuid: String): ByteArray {
    val hex = uuid.replace("-", "")
    require(hex.length == 32) { "UUID inválido: $uuid" }
    return hexToBytes(hex)
}

fun formatUuid(bytes: ByteArray): String {
    require(bytes.size == 16)
    val hex = bytes.toHex()
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
        "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
}
