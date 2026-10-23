package dev.municipal.blelab.core

import java.util.Locale

internal fun jsonString(value: String): String = buildString {
    append('"')
    for (ch in value) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
        }
    }
    append('"')
}

internal fun jsonNumber(value: Double): String {
    if (!value.isFinite()) return "null"
    val raw = String.format(Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')
    return if (raw == "-0") "0" else raw
}

internal fun jsonValue(value: Any?): String = when (value) {
    null -> "null"
    is String -> jsonString(value)
    is Boolean -> if (value) "true" else "false"
    is Int, is Long -> value.toString()
    is Double -> jsonNumber(value)
    else -> error("Tipo no exportable: ${value::class.java.name}")
}

internal fun jsonObject(fields: List<Pair<String, Any?>>): String =
    fields.joinToString(prefix = "{", postfix = "}") { (key, value) ->
        "${jsonString(key)}:${jsonValue(value)}"
    }
