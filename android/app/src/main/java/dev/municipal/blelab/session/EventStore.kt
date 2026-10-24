package dev.municipal.blelab.session

import dev.municipal.blelab.core.LabSummary
import dev.municipal.blelab.core.SessionEvent
import dev.municipal.blelab.core.SessionExport
import dev.municipal.blelab.core.toCsvRow
import dev.municipal.blelab.core.toJson
import dev.municipal.blelab.core.toJsonLine
import java.io.BufferedWriter
import java.io.File

class EventStore(val directory: File) {
    private val csvWriter: BufferedWriter
    private val jsonWriter: BufferedWriter
    private var closed = false

    init {
        // Los inicializadores de propiedad corren antes que init. Crear la carpeta ahí
        // abre events.csv con el directorio todavía inexistente y mata el hilo BLE.
        check(directory.mkdirs() || directory.isDirectory) {
            "No se pudo crear ${directory.absolutePath}"
        }
        csvWriter = File(directory, "events.csv").bufferedWriter()
        jsonWriter = File(directory, "events.jsonl").bufferedWriter()
        csvWriter.append(SessionExport.csvHeader).append('\n')
        csvWriter.flush()
    }

    fun append(event: SessionEvent) {
        if (closed) return
        csvWriter.append(event.toCsvRow()).append('\n')
        csvWriter.flush()
        jsonWriter.append(event.toJsonLine()).append('\n')
        jsonWriter.flush()
    }

    fun writeSummary(summary: LabSummary) {
        if (closed) return
        File(directory, "summary.json").writeText(summary.toJson())
    }

    fun close() {
        if (closed) return
        closed = true
        csvWriter.close()
        jsonWriter.close()
    }
}

fun writeExportJson(directory: File, summary: LabSummary): File {
    val out = File(directory, "session.json")
    val jsonl = File(directory, "events.jsonl")
    out.bufferedWriter().use { writer ->
        if (jsonl.isFile) {
            jsonl.bufferedReader().use { reader ->
                SessionExport.writeJson(summary, reader.lineSequence(), writer)
            }
        } else {
            SessionExport.writeJson(summary, emptySequence(), writer)
        }
    }
    return out
}
