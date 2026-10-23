import Foundation

enum LabConstants {
    static let schema = "blelab.session"
    static let schemaVersion = 1
    static let ibeaconUUID = "7B2E4A10-9C3D-4F58-A1E6-2D8B5C04F917"
    static let serviceUUID = "C4A15E20-6B17-4D3E-9F80-11A2B3C4D5E6"
    static let appleCompanyId = 0x004C
    static let protocolVersion = 1
    static let defaultMeasuredPower = -59
    static let defaultPeriodSeconds = 15
    static let legacyAdvertisingBytes = 31
    static let captureDurationMs: Int64 = 5_000
    static let captureGapMs: Int64 = 2_000
    static let clockNote =
        "wall_time_utc y wall_epoch_ms de dos dispositivos no están sincronizados. " +
        "capture_latency_ms y los tiempos entre códigos usan el reloj monotónico del dispositivo que midió."
    static let allowedPeriods = [10, 15, 30]
}

enum EventTypes {
    static let sessionStart = "session_start"
    static let sessionStop = "session_stop"
    static let advertiseStarted = "advertise_started"
    static let advertiseStartFailed = "advertise_start_failed"
    static let advertiseDataSet = "advertise_data_set"
    static let advertiseDataFailed = "advertise_data_failed"
    static let advertiseStopped = "advertise_stopped"
    static let rotationScheduled = "rotation_scheduled"
    static let rotationApplied = "rotation_applied"
    static let bluetoothOff = "bluetooth_off"
    static let bluetoothOn = "bluetooth_on"
    static let permissionMissing = "permission_missing"
    static let permissionRevoked = "permission_revoked"
    static let recovery = "recovery"
    static let scanStarted = "scan_started"
    static let scanStopped = "scan_stopped"
    static let scanFailed = "scan_failed"
    static let observation = "observation"
    static let captureAttempt = "capture_attempt"
    static let unsupportedVersion = "unsupported_version"
    static let error = "error"
}
