package dev.municipal.blelab.core

import org.json.JSONObject
import java.io.File

internal fun fixture(name: String): JSONObject {
    val file = listOf(
        File("../../protocol/fixtures/$name"),
        File("protocol/fixtures/$name"),
    ).firstOrNull { it.isFile } ?: error("No está el vector $name. cwd=${File(".").absolutePath}")
    return JSONObject(file.readText())
}
