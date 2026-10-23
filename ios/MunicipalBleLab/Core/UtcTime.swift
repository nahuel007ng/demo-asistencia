import Foundation

/// ISO-8601 UTC con milisegundos. Misma cuenta que el módulo Android, sin depender del huso del teléfono.
func formatUtc(_ epochMillis: Int64) -> String {
    let millisOfDay = floorMod(epochMillis, 86_400_000)
    let days = floorDiv(epochMillis, 86_400_000)
    let hour = Int(millisOfDay / 3_600_000)
    let minute = Int((millisOfDay / 60_000) % 60)
    let second = Int((millisOfDay / 1_000) % 60)
    let millis = Int(millisOfDay % 1_000)
    let civil = civilFromDays(days)
    return String(
        format: "%04d-%02d-%02dT%02d:%02d:%02d.%03dZ",
        civil.year, civil.month, civil.day, hour, minute, second, millis
    )
}

/// `days` son días desde 1970-01-01. Algoritmo civil_from_days de Howard Hinnant.
func civilFromDays(_ daysSinceEpoch: Int64) -> (year: Int, month: Int, day: Int) {
    let z = daysSinceEpoch + 719468
    let era = z >= 0 ? z / 146097 : (z - 146096) / 146097
    let doe = Int(z - era * 146097)
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    var year = yoe + Int(era) * 400
    let dayOfYear = doe - (365 * yoe + yoe / 4 - yoe / 100)
    let monthPart = (5 * dayOfYear + 2) / 153
    let day = dayOfYear - (153 * monthPart + 2) / 5 + 1
    let month = monthPart + (monthPart < 10 ? 3 : -9)
    if month <= 2 {
        year += 1
    }
    return (year, month, day)
}

/// División que redondea hacia -∞. Con épocas positivas coincide con `/` de Swift, que trunca hacia cero.
func floorDiv(_ value: Int64, _ divisor: Int64) -> Int64 {
    precondition(divisor > 0)
    let quotient = value / divisor
    let remainder = value % divisor
    if value < 0 && remainder != 0 {
        return quotient - 1
    }
    return quotient
}

func floorMod(_ value: Int64, _ divisor: Int64) -> Int64 {
    precondition(divisor > 0)
    let remainder = value % divisor
    return remainder >= 0 ? remainder : remainder + divisor
}
