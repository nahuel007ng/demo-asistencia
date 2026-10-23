package dev.municipal.blelab.core

/**
 * ISO-8601 UTC con milisegundos. No usa java.time: ese paquete no está en Android 7
 * sin desugaring, y el módulo tiene que correr igual en la JVM de los tests.
 */
fun formatUtc(epochMillis: Long): String {
    val millisOfDay = Math.floorMod(epochMillis, 86_400_000L)
    val days = Math.floorDiv(epochMillis, 86_400_000L)
    val hour = (millisOfDay / 3_600_000L).toInt()
    val minute = ((millisOfDay / 60_000L) % 60).toInt()
    val second = ((millisOfDay / 1_000L) % 60).toInt()
    val millis = (millisOfDay % 1_000L).toInt()
    val (year, month, day) = civilFromDays(days)
    return "%04d-%02d-%02dT%02d:%02d:%02d.%03dZ".format(year, month, day, hour, minute, second, millis)
}

/** Algoritmo civil_from_days de Howard Hinnant. `days` son días desde 1970-01-01. */
internal fun civilFromDays(daysSinceEpoch: Long): Triple<Int, Int, Int> {
    var z = daysSinceEpoch + 719468
    val era = if (z >= 0) z / 146097 else (z - 146096) / 146097
    val doe = (z - era * 146097).toInt()
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    var year = yoe + era.toInt() * 400
    val dayOfYear = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val monthPart = (5 * dayOfYear + 2) / 153
    val day = dayOfYear - (153 * monthPart + 2) / 5 + 1
    val month = monthPart + if (monthPart < 10) 3 else -9
    if (month <= 2) year += 1
    return Triple(year, month, day)
}
