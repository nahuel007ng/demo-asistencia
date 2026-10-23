package dev.municipal.blelab.core

enum class CodeClass {
    CURRENT,
    PREVIOUS,
    NEXT,
    OTHER,
    ;

    val wire: String get() = name.lowercase()
}

data class Classification(
    val base: CodeClass,
    val boundaryGrace: Boolean,
)

object CodeClassifier {
    fun classify(
        code: Long,
        unixEpochSeconds: Long,
        periodSeconds: Int,
        graceSeconds: Int,
    ): Classification {
        require(periodSeconds > 0)
        val observed = code and 0xFFFFFFFFL
        val current = IdentifierSchedule.ofEpoch(unixEpochSeconds, periodSeconds).identifier
        val previous = IdentifierSchedule.ofEpoch(unixEpochSeconds - periodSeconds, periodSeconds).identifier
        val next = IdentifierSchedule.ofEpoch(unixEpochSeconds + periodSeconds, periodSeconds).identifier
        val base = when (observed) {
            current -> CodeClass.CURRENT
            previous -> CodeClass.PREVIOUS
            next -> CodeClass.NEXT
            else -> CodeClass.OTHER
        }
        val into = Math.floorMod(unixEpochSeconds, periodSeconds.toLong())
        val grace = base == CodeClass.PREVIOUS && graceSeconds > 0 && into < graceSeconds
        return Classification(base, grace)
    }

    /**
     * Con código esperado, la captura es correcta solo si coincide.
     * Sin él, hace falta la ventana actual o un previous dentro de la gracia configurada.
     */
    fun captureAccepted(code: Long, classification: Classification, expectedCode: Long?): Boolean {
        if (expectedCode != null) return (code and 0xFFFFFFFFL) == (expectedCode and 0xFFFFFFFFL)
        return classification.base == CodeClass.CURRENT || classification.boundaryGrace
    }
}
