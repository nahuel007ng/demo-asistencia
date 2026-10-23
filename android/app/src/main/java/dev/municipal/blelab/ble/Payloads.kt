package dev.municipal.blelab.ble

import android.bluetooth.le.AdvertiseData
import android.os.ParcelUuid
import dev.municipal.blelab.core.CustomCodec
import dev.municipal.blelab.core.IBeaconCodec
import dev.municipal.blelab.core.IdentifierSchedule
import dev.municipal.blelab.core.LabConstants
import dev.municipal.blelab.core.RotatingIdentifier
import dev.municipal.blelab.session.EmitterConfig
import java.util.UUID

data class BuiltAdvertisement(
    val data: AdvertiseData,
    val identifier: RotatingIdentifier,
    val timeDerived: Boolean,
)

fun buildAdvertisement(config: EmitterConfig, unixEpochSeconds: Long): BuiltAdvertisement {
    val identifier = if (config.fixedIdentifier) {
        IdentifierSchedule.fromRaw(config.fixedValue ?: 0L)
    } else {
        IdentifierSchedule.ofEpoch(unixEpochSeconds, config.periodSeconds)
    }
    val timeDerived = !config.fixedIdentifier
    val data = if (config.protocol == "ibeacon") {
        AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(
                LabConstants.APPLE_COMPANY_ID,
                IBeaconCodec.encodePayload(
                    LabConstants.IBEACON_UUID,
                    identifier.major,
                    identifier.minor,
                    config.measuredPower ?: LabConstants.DEFAULT_MEASURED_POWER,
                ),
            )
            .build()
    } else {
        AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(
                ParcelUuid(UUID.fromString(LabConstants.SERVICE_UUID)),
                CustomCodec.encode(
                    identifier = identifier.identifier,
                    periodSeconds = config.periodSeconds,
                    measuredPower = config.measuredPower ?: LabConstants.DEFAULT_MEASURED_POWER,
                    timeDerived = timeDerived,
                ),
            )
            .build()
    }
    return BuiltAdvertisement(data, identifier, timeDerived)
}
