package dev.municipal.blelab.ble

import android.bluetooth.le.ScanResult
import android.os.ParcelUuid
import dev.municipal.blelab.core.CustomCodec
import dev.municipal.blelab.core.CustomDecode
import dev.municipal.blelab.core.IBeaconCodec
import dev.municipal.blelab.core.LabConstants
import java.util.UUID

sealed class ParsedFrame {
    data class Observation(
        val code: Long,
        val major: Int,
        val minor: Int,
        val packetPeriod: Int?,
    ) : ParsedFrame()

    data class Unsupported(val version: Int) : ParsedFrame()
}

private val serviceUuid = ParcelUuid(UUID.fromString(LabConstants.SERVICE_UUID))

fun parseScanResult(result: ScanResult, protocol: String): ParsedFrame? {
    val record = result.scanRecord ?: return null
    if (protocol == "ibeacon") {
        val payload = record.getManufacturerSpecificData(LabConstants.APPLE_COMPANY_ID) ?: return null
        val frame = IBeaconCodec.decodePayload(payload) ?: return null
        if (!frame.uuid.equals(LabConstants.IBEACON_UUID, ignoreCase = true)) return null
        return ParsedFrame.Observation(frame.identifier, frame.major, frame.minor, packetPeriod = null)
    }
    val payload = record.getServiceData(serviceUuid) ?: return null
    return when (val decoded = CustomCodec.decode(payload)) {
        is CustomDecode.Frame -> ParsedFrame.Observation(
            code = decoded.frame.identifier,
            major = decoded.frame.major,
            minor = decoded.frame.minor,
            packetPeriod = decoded.frame.periodSeconds,
        )
        is CustomDecode.UnsupportedVersion -> ParsedFrame.Unsupported(decoded.version)
        CustomDecode.Malformed -> null
    }
}
