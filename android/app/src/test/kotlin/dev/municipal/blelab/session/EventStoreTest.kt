package dev.municipal.blelab.session

import dev.municipal.blelab.core.SessionExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EventStoreTest {
    @Test
    fun createsTheSessionDirectoryBeforeOpeningTheLogs() {
        val root = File(System.getProperty("java.io.tmpdir"), "blelab-store-" + System.nanoTime())
        val directory = File(root, "sessions/20261023T232511355Z-emitter")
        try {
            val store = EventStore(directory)
            store.close()
            val csv = File(directory, "events.csv")
            assertTrue(csv.isFile)
            assertTrue(File(directory, "events.jsonl").isFile)
            assertEquals(SessionExport.csvHeader, csv.readLines().single())
        } finally {
            root.deleteRecursively()
        }
    }
}
