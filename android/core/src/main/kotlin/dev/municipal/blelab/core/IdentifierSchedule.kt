package dev.municipal.blelab.core

data class RotatingIdentifier(
    val windowIndex: Long,
    val identifier: Long,
    val major: Int,
    val minor: Int,
)

object IdentifierSchedule {
    fun ofEpoch(unixEpochSeconds: Long, periodSeconds: Int): RotatingIdentifier {
        require(periodSeconds > 0)
        return fromWindowIndex(Math.floorDiv(unixEpochSeconds, periodSeconds.toLong()))
    }

    fun fromRaw(identifier: Long): RotatingIdentifier = fromWindowIndex(identifier and 0xFFFFFFFFL)

    fun fromWindowIndex(index: Long): RotatingIdentifier {
        val masked = index and 0xFFFFFFFFL
        val major = ((masked shr 16) and 0xFFFF).toInt()
        val minor = (masked and 0xFFFF).toInt()
        return RotatingIdentifier(windowIndex = index, identifier = masked, major = major, minor = minor)
    }

    fun windowIndex(unixEpochSeconds: Long, periodSeconds: Int): Long {
        require(periodSeconds > 0)
        return Math.floorDiv(unixEpochSeconds, periodSeconds.toLong())
    }

    /** En el límite exacto el remanente es el período completo: la ventana acaba de empezar. */
    fun secondsRemaining(unixEpochSeconds: Long, periodSeconds: Int): Int {
        require(periodSeconds > 0)
        val into = Math.floorMod(unixEpochSeconds, periodSeconds.toLong())
        return (periodSeconds - into).toInt()
    }

    fun millisUntilNextChange(wallEpochMillis: Long, periodSeconds: Int): Long {
        require(periodSeconds > 0)
        val periodMs = periodSeconds * 1000L
        val mod = Math.floorMod(wallEpochMillis, periodMs)
        return if (mod == 0L) periodMs else periodMs - mod
    }
}
