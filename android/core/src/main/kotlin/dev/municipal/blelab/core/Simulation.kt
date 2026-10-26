package dev.municipal.blelab.core

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

data class KioskFrame(
    val step: Long,
    val token: String,
    val qrText: String,
    val bleIdentifier: Long,
    val serviceData: ByteArray,
)

data class QrFields(
    val version: String,
    val kioskId: String,
    val token: String,
    val step: Long,
)

data class CheckInPayload(
    val kind: String,
    val kioskId: String,
    val token: String,
    val step: Long,
    val bleIdentifier: Long,
    val qrText: String,
)

enum class CheckInPhase {
    LISTENING,
    ACCEPTED,
    RETRY,
    ASSISTED,
}

data class CheckInView(
    val phase: CheckInPhase,
    val message: String,
    val payload: CheckInPayload?,
    val consecutiveFailures: Int,
)

object CheckInCopy {
    const val SUCCESS = "Ingreso correcto"
    const val RETRY = "No se pudo marcar. Intentá de nuevo."
    const val ASSISTED = "Si sigue sin poder, pedile al encargado una fichada asistida. Podés intentar otra vez."
    const val LISTENING = "Escuchando el QR y el BLE"
}

object Simulation {
    const val SECRET = "lab-secreto-mock"
    const val KIOSK_ID = "lab-kiosco-01"
    const val LABEL = "asis-ble-v1"
    const val PERIOD_SECONDS = 15
    const val QR_VERSION = "v2"
    const val PAYLOAD_KIND = "lab-checkin"
    const val CHECKIN_LIMIT_MS = 15_000L

    fun step(unixEpochSeconds: Long): Long = Math.floorDiv(unixEpochSeconds, PERIOD_SECONDS.toLong())

    fun counterBytes(step: Long): ByteArray {
        val out = ByteArray(8)
        var value = step
        for (index in 7 downTo 0) {
            out[index] = (value and 0xFF).toByte()
            value = value shr 8
        }
        return out
    }

    fun token(step: Long, secret: String = SECRET): String {
        val mac = hmac("HmacSHA1", secret.toByteArray(Charsets.UTF_8), counterBytes(step))
        val offset = mac.last().toInt() and 0x0F
        val binary = ((mac[offset].toInt() and 0x7F) shl 24) or
            ((mac[offset + 1].toInt() and 0xFF) shl 16) or
            ((mac[offset + 2].toInt() and 0xFF) shl 8) or
            (mac[offset + 3].toInt() and 0xFF)
        return "%06d".format(binary % 1_000_000)
    }

    fun bleIdentifier(step: Long, secret: String = SECRET, label: String = LABEL): Long {
        val derived = hmac("HmacSHA256", secret.toByteArray(Charsets.UTF_8), label.toByteArray(Charsets.UTF_8))
        val mac = hmac("HmacSHA256", derived, counterBytes(step))
        return ((mac[0].toLong() and 0xFF) shl 24) or
            ((mac[1].toLong() and 0xFF) shl 16) or
            ((mac[2].toLong() and 0xFF) shl 8) or
            (mac[3].toLong() and 0xFF)
    }

    fun qrText(kioskId: String = KIOSK_ID, token: String, step: Long): String =
        "$QR_VERSION|$kioskId|$token|$step"

    fun kioskFrame(
        unixEpochSeconds: Long,
        measuredPower: Int = LabConstants.DEFAULT_MEASURED_POWER,
    ): KioskFrame {
        val step = step(unixEpochSeconds)
        val token = token(step)
        val ble = bleIdentifier(step)
        return KioskFrame(
            step = step,
            token = token,
            qrText = qrText(token = token, step = step),
            bleIdentifier = ble,
            serviceData = CustomCodec.encode(
                identifier = ble,
                periodSeconds = PERIOD_SECONDS,
                measuredPower = measuredPower,
                timeDerived = true,
            ),
        )
    }

    fun parseQr(text: String): QrFields? {
        val parts = text.split('|')
        if (parts.size != 4) return null
        if (parts[0] != QR_VERSION) return null
        val kioskId = parts[1]
        if (kioskId.isEmpty()) return null
        val token = parts[2]
        if (!token.matches(Regex("\\d{6}"))) return null
        val step = parts[3].toLongOrNull() ?: return null
        return QrFields(parts[0], kioskId, token, step)
    }

    fun pairValid(qrText: String, observedBle: Long, phoneStep: Long): Boolean {
        val qr = parseQr(qrText) ?: return false
        if (qr.kioskId != KIOSK_ID) return false
        if (qr.token != token(qr.step)) return false
        val expected = listOf(bleIdentifier(qr.step), bleIdentifier(qr.step - 1), bleIdentifier(qr.step + 1))
        if (observedBle !in expected) return false
        return abs(qr.step - phoneStep) <= 1
    }

    fun payload(qrText: String, bleIdentifier: Long): CheckInPayload? {
        val qr = parseQr(qrText) ?: return null
        return CheckInPayload(
            kind = PAYLOAD_KIND,
            kioskId = qr.kioskId,
            token = qr.token,
            step = qr.step,
            bleIdentifier = bleIdentifier,
            qrText = qrText,
        )
    }

    /** Mock en el proceso. No llama a la red y no es el contrato de la API. */
    fun mockAccept(payload: CheckInPayload, phoneStep: Long): Boolean {
        if (payload.kind != PAYLOAD_KIND) return false
        val qr = parseQr(payload.qrText) ?: return false
        if (payload.kioskId != qr.kioskId || payload.token != qr.token || payload.step != qr.step) return false
        if (payload.qrText.contains(SECRET) || payload.token.contains(SECRET)) return false
        return pairValid(payload.qrText, payload.bleIdentifier, phoneStep)
    }

    fun resolveCheckIn(
        qrText: String?,
        bleIdentifier: Long?,
        phoneUnixSeconds: Long,
        elapsedMs: Long,
        consecutiveFailures: Int,
    ): CheckInView {
        val phoneStep = step(phoneUnixSeconds)
        if (qrText != null && bleIdentifier != null && elapsedMs <= CHECKIN_LIMIT_MS) {
            val body = payload(qrText, bleIdentifier)
            if (body != null && pairValid(qrText, bleIdentifier, phoneStep)) {
                if (mockAccept(body, phoneStep)) {
                    return CheckInView(CheckInPhase.ACCEPTED, CheckInCopy.SUCCESS, body, 0)
                }
                return failure(consecutiveFailures)
            }
        }
        if (elapsedMs >= CHECKIN_LIMIT_MS) return failure(consecutiveFailures)
        return CheckInView(CheckInPhase.LISTENING, CheckInCopy.LISTENING, null, consecutiveFailures)
    }

    private fun failure(consecutiveFailures: Int): CheckInView {
        val failures = consecutiveFailures + 1
        val assisted = failures >= 2
        return CheckInView(
            phase = if (assisted) CheckInPhase.ASSISTED else CheckInPhase.RETRY,
            message = if (assisted) CheckInCopy.ASSISTED else CheckInCopy.RETRY,
            payload = null,
            consecutiveFailures = failures,
        )
    }

    private fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(key, algorithm))
        return mac.doFinal(data)
    }
}
