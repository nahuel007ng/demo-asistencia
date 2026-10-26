package dev.municipal.blelab.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulationTest {
    @Test
    fun fixtureMatchesTheSharedVector() {
        val row = fixture("simulation.json")
        val epoch = row.getLong("unixEpochSeconds")
        val frame = Simulation.kioskFrame(epoch)
        assertEquals(row.getLong("T"), frame.step)
        assertEquals(row.getString("token"), frame.token)
        assertEquals(row.getString("qr"), frame.qrText)
        assertEquals(row.getLong("bleIdentifier"), frame.bleIdentifier)
        assertEquals(row.getString("serviceDataHex").lowercase(), frame.serviceData.toHex().lowercase())
        assertNotEquals(frame.token, frame.bleIdentifier.toString())
        assertFalse(frame.qrText.contains(Simulation.SECRET))
    }

    @Test
    fun counterIsEightBigEndianBytesAndNotAThirtySecondTotp() {
        val step = 113_333_333L
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 0, 0x06, 0xC1.toByte(), 0x54, 0x55),
            Simulation.counterBytes(step),
        )
        assertFalse("113333333".toByteArray().contentEquals(Simulation.counterBytes(step)))
        val epoch = 1_699_999_995L
        assertEquals(step, Simulation.step(epoch))
        assertNotEquals(Simulation.token(epoch / 30), Simulation.token(Simulation.step(epoch)))
    }

    @Test
    fun qrAndBleStayInsideTheWindowAndChangeOnTheNextOne() {
        val start = 1_699_999_995L
        val inside = Simulation.kioskFrame(start + 14)
        val first = Simulation.kioskFrame(start)
        val next = Simulation.kioskFrame(start + 15)
        assertEquals(first.qrText, inside.qrText)
        assertEquals(first.bleIdentifier, inside.bleIdentifier)
        assertNotEquals(first.qrText, next.qrText)
        assertNotEquals(first.bleIdentifier, next.bleIdentifier)
        assertNotEquals(first.token, next.token)
    }

    @Test
    fun parserAcceptsTheFixtureAndRejectsABrokenQr() {
        val row = fixture("simulation.json")
        val qr = row.getString("qr")
        val step = row.getLong("T")
        val parsed = Simulation.parseQr(qr)
        assertEquals(Simulation.KIOSK_ID, parsed?.kioskId)
        assertEquals(row.getString("token"), parsed?.token)
        assertEquals(step, parsed?.step)
        assertNull(Simulation.parseQr("$qr|extra"))
        assertNull(Simulation.parseQr("v2|lab|kiosco-01|689177|$step"))
        assertNull(Simulation.parseQr("v1|${Simulation.KIOSK_ID}|689177|$step"))
        assertNull(Simulation.parseQr("v2|${Simulation.KIOSK_ID}|68917|$step"))
        assertNull(Simulation.parseQr("v2|${Simulation.KIOSK_ID}|6891771|$step"))
    }

    @Test
    fun pairAcceptsTheFixtureWindowAndOneNeighbour() {
        val row = fixture("simulation.json")
        val qr = row.getString("qr")
        val step = row.getLong("T")
        val ble = row.getLong("bleIdentifier")
        assertTrue(Simulation.pairValid(qr, ble, step))
        assertTrue(Simulation.pairValid(qr, Simulation.bleIdentifier(step - 1), step))
        assertTrue(Simulation.pairValid(qr, Simulation.bleIdentifier(step + 1), step))
        assertFalse(Simulation.pairValid(qr, Simulation.bleIdentifier(step + 2), step))
        assertFalse(Simulation.pairValid(qr, ble, step + 2))
        assertFalse(Simulation.pairValid(qr.replace(row.getString("token"), "000000"), ble, step))
        assertFalse(Simulation.pairValid(qr.replace(Simulation.KIOSK_ID, "otro-kiosco"), ble, step))
    }

    @Test
    fun payloadGoesToTheInProcessMockWithoutTheSecret() {
        val row = fixture("simulation.json")
        val qr = row.getString("qr")
        val step = row.getLong("T")
        val ble = row.getLong("bleIdentifier")
        val body = Simulation.payload(qr, ble)
        assertEquals(Simulation.PAYLOAD_KIND, body?.kind)
        assertEquals(Simulation.KIOSK_ID, body?.kioskId)
        assertEquals(row.getString("token"), body?.token)
        assertEquals(step, body?.step)
        assertEquals(ble, body?.bleIdentifier)
        assertEquals(qr, body?.qrText)
        assertFalse(body!!.qrText.contains(Simulation.SECRET))
        assertTrue(Simulation.mockAccept(body, step))
        assertFalse(Simulation.mockAccept(body.copy(token = "000000"), step))
    }

    @Test
    fun checkInAcceptsAPairAndAsksAgainAfterTheCeiling() {
        val row = fixture("simulation.json")
        val epoch = row.getLong("unixEpochSeconds")
        val qr = row.getString("qr")
        val ble = row.getLong("bleIdentifier")
        val accepted = Simulation.resolveCheckIn(qr, ble, epoch, 1_000, 0)
        assertEquals(CheckInPhase.ACCEPTED, accepted.phase)
        assertEquals(CheckInCopy.SUCCESS, accepted.message)
        assertEquals(qr, accepted.payload?.qrText)
        assertEquals(ble, accepted.payload?.bleIdentifier)
        assertEquals(0, accepted.consecutiveFailures)

        val waiting = Simulation.resolveCheckIn(null, ble, epoch, 1_000, 0)
        assertEquals(CheckInPhase.LISTENING, waiting.phase)

        val retry = Simulation.resolveCheckIn(qr, null, epoch, Simulation.CHECKIN_LIMIT_MS, 0)
        assertEquals(CheckInPhase.RETRY, retry.phase)
        assertEquals(CheckInCopy.RETRY, retry.message)
        assertEquals(1, retry.consecutiveFailures)

        val assisted = Simulation.resolveCheckIn(qr, null, epoch, Simulation.CHECKIN_LIMIT_MS, 1)
        assertEquals(CheckInPhase.ASSISTED, assisted.phase)
        assertEquals(CheckInCopy.ASSISTED, assisted.message)
        assertEquals(2, assisted.consecutiveFailures)
    }

    @Test
    fun exportKeepsTheQrAndTheCheckInWithoutCountingThemAsRadioErrors() {
        val row = fixture("simulation.json")
        val qr = row.getString("qr")
        val ble = row.getLong("bleIdentifier")
        val shown = baseEvent(EventTypes.QR_SHOWN, "emitter").copy(code = ble, errorMessage = qr)
        val checkIn = baseEvent(EventTypes.CHECKIN, "receiver").copy(
            code = ble,
            success = true,
            errorMessage = qr,
        )
        val failed = baseEvent(EventTypes.CHECKIN, "receiver").copy(success = false, errorCode = "timeout", errorMessage = CheckInCopy.RETRY)
        val csv = SessionExport.csv(listOf(shown, checkIn, failed))
        assertTrue(csv.contains("qr_shown"))
        assertTrue(csv.contains("checkin"))
        assertTrue(csv.contains(qr))
        assertTrue(csv.contains(ble.toString()))
        val stats = RunningStats()
        listOf(shown, checkIn, failed).forEach(stats::absorb)
        assertEquals(0, stats.summary().emitterErrors)
        assertEquals(0, stats.summary().receiverErrors)
    }

    private fun baseEvent(type: String, role: String) = SessionEvent(
        sessionId = "s1",
        role = role,
        manufacturer = "lab",
        model = "pixel",
        osVersion = "14",
        appVersion = "0.1.0",
        protocol = "custom",
        advertiseMethod = "restart",
        periodSeconds = 15,
        intervalMs = 100,
        connectable = false,
        eventType = type,
        monotonicMs = 0,
        wallTimeUtc = formatUtc(0),
        wallEpochMs = 0,
    )
}

private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
    org.junit.Assert.assertArrayEquals(expected, actual)
}
