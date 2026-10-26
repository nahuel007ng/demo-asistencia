package dev.municipal.blelab.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class QrMatrixTest {
    @Test
    fun theFixtureTextSurvivesAQrRoundTrip() {
        val candidates = listOf(
            File("../../protocol/fixtures/simulation.json"),
            File("protocol/fixtures/simulation.json"),
        )
        val file = candidates.firstOrNull { it.isFile } ?: error("No está simulation.json")
        val qr = Regex(""""qr"\s*:\s*"([^"]+)"""").find(file.readText())?.groupValues?.get(1)
            ?: error("El vector no trae qr")
        assertEquals(qr, QrMatrix.decode(QrMatrix.encode(qr)))
    }
}
