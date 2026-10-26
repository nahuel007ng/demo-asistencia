package dev.municipal.blelab.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ProtocolTest {
    @Test
    fun windowsMatchTheSharedVectors() {
        val cases = fixture("windows.json").getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val row = cases.getJSONObject(index)
            val epoch = row.getLong("unixEpochSeconds")
            val period = row.getInt("periodSeconds")
            val id = IdentifierSchedule.ofEpoch(epoch, period)
            assertEquals(row.getString("name"), row.getLong("windowIndex"), id.windowIndex)
            assertEquals(row.getLong("identifier"), id.identifier)
            assertEquals(row.getInt("major"), id.major)
            assertEquals(row.getInt("minor"), id.minor)
            assertEquals(row.getInt("secondsRemaining"), IdentifierSchedule.secondsRemaining(epoch, period))
            assertEquals(id.identifier, (id.major.toLong() shl 16) or id.minor.toLong())
        }
    }

    @Test
    fun rotationDelayHitsTheUtcBoundary() {
        val boundary = 1_699_999_995_000L
        assertEquals(15_000L, IdentifierSchedule.millisUntilNextChange(boundary, 15))
        assertEquals(14_500L, IdentifierSchedule.millisUntilNextChange(boundary + 500, 15))
        assertEquals(1L, IdentifierSchedule.millisUntilNextChange(boundary - 1, 15))
    }

    @Test
    fun iBeaconPayloadMatchesTheFixtureAndFitsLegacy() {
        val row = fixture("ibeacon-payload.json")
        val encoded = IBeaconCodec.encodePayload(
            row.getString("uuid"),
            row.getInt("major"),
            row.getInt("minor"),
            row.getInt("measuredPower"),
        )
        assertEquals(row.getString("hex"), encoded.toHex())
        val decoded = IBeaconCodec.decodePayload(encoded)!!
        assertEquals(LabConstants.IBEACON_UUID, decoded.uuid)
        assertEquals(113333333L, decoded.identifier)
        assertEquals(-59, decoded.measuredPower)
        assertEquals(row.getInt("adBytesWithoutFlags"), IBeaconCodec.advertisingBytes(false))
        assertEquals(row.getInt("adBytesWithFlags"), IBeaconCodec.advertisingBytes(true))
        assertTrue(IBeaconCodec.advertisingBytes(true) <= LabConstants.LEGACY_ADVERTISING_BYTES)
        assertNull(IBeaconCodec.decodePayload(byteArrayOf(0x02, 0x15)))
    }

    @Test
    fun iBeaconFilterKeepsMajorAndMinorOpen() {
        val filter = IBeaconCodec.manufacturerFilter(LabConstants.IBEACON_UUID)
        assertEquals(LabConstants.APPLE_COMPANY_ID, filter.companyId)
        assertEquals(0x02.toByte(), filter.data[0])
        assertEquals(0x15.toByte(), filter.data[1])
        assertArrayEquals(parseUuidBytes(LabConstants.IBEACON_UUID), filter.data.copyOfRange(2, 18))
        assertEquals(0xFF.toByte(), filter.mask[0])
        assertEquals(0xFF.toByte(), filter.mask[17])
        for (index in 18 until filter.mask.size) {
            assertEquals(0.toByte(), filter.mask[index])
        }
    }

    @Test
    fun customPayloadRoundTripAndUnknownVersion() {
        val row = fixture("custom-payload.json")
        val encoded = CustomCodec.encode(
            identifier = row.getLong("identifier"),
            periodSeconds = row.getInt("periodSeconds"),
            measuredPower = row.getInt("measuredPower"),
            timeDerived = true,
        )
        assertEquals(row.getString("hex"), encoded.toHex())
        val decoded = CustomCodec.decode(encoded) as CustomDecode.Frame
        assertEquals(1729, decoded.frame.major)
        assertEquals(21589, decoded.frame.minor)
        assertTrue(decoded.frame.timeDerived)
        assertEquals(row.getInt("adBytesWithFlags"), CustomCodec.advertisingBytes(true))
        assertTrue(CustomCodec.advertisingBytes(true) <= LabConstants.LEGACY_ADVERTISING_BYTES)

        val fixed = CustomCodec.decode(CustomCodec.encode(7, 15, -59, timeDerived = false)) as CustomDecode.Frame
        assertFalse(fixed.frame.timeDerived)
        assertEquals(7L, fixed.frame.identifier)

        val rejected = CustomCodec.decode(hexToBytes(row.getString("unsupportedVersionHex")))
        assertTrue(rejected is CustomDecode.UnsupportedVersion)
        assertEquals(2, (rejected as CustomDecode.UnsupportedVersion).version)
        assertTrue(CustomCodec.decode(byteArrayOf(1, 2, 3)) is CustomDecode.Malformed)
    }

    @Test
    fun classificationMatchesTheSharedVectors() {
        val file = fixture("classification.json")
        val epoch = file.getLong("unixEpochSeconds")
        val period = file.getInt("periodSeconds")
        val cases = file.getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val row = cases.getJSONObject(index)
            val result = CodeClassifier.classify(
                code = row.getLong("code"),
                unixEpochSeconds = epoch,
                periodSeconds = period,
                graceSeconds = row.getInt("graceSeconds"),
            )
            assertEquals(row.getString("classification"), result.base.wire)
            assertEquals(row.getBoolean("boundaryGrace"), result.boundaryGrace)
        }
    }

    @Test
    fun utcFormatterMatchesJavaTimeOnTheJvm() {
        val samples = longArrayOf(0, 1, 999, 86_400_000L, 1_700_000_000_000L, 1_699_999_995_000L, -1_000L)
        for (millis in samples) {
            val formatted = formatUtc(millis)
            val parsed = Instant.parse(formatted).toEpochMilli()
            assertEquals(millis, parsed)
        }
    }

    @Test
    fun permissionsDoNotUseNeverForLocationAndFollowTheSdk() {
        assertEquals(listOf(LabPermissions.FINE_LOCATION), LabPermissions.forReceiver(24))
        assertTrue(LabPermissions.SCAN in LabPermissions.forReceiver(31))
        assertTrue(LabPermissions.FINE_LOCATION in LabPermissions.forReceiver(36))
        assertFalse(LabPermissions.FINE_LOCATION in LabPermissions.forEmitter(36))
        assertTrue(LabPermissions.ADVERTISE in LabPermissions.forEmitter(31))
        assertTrue(LabPermissions.CAMERA in LabPermissions.forCheckIn(31))
        assertFalse(LabPermissions.CAMERA in LabPermissions.forReceiver(31))
        assertFalse(LabPermissions.CAMERA in LabPermissions.forEmitter(31))
        assertEquals(160, AdvertiseIntervals.toAdvertisingSetUnits(100))
        assertEquals(400, AdvertiseIntervals.toAdvertisingSetUnits(250))
        assertEquals(1600, AdvertiseIntervals.toAdvertisingSetUnits(1000))
    }
}
