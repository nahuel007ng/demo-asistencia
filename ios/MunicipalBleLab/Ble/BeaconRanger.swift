import CoreLocation
import Foundation

/// Ranging clásico de iBeacon. No usa CLMonitor: hace falta iOS 17 y no entrega el historial de este laboratorio.
final class BeaconRanger: NSObject, CLLocationManagerDelegate {
    var onBeacons: ([CLBeacon]) -> Void = { _ in }
    var onFailure: (String) -> Void = { _ in }
    var onAuthorization: (CLAuthorizationStatus) -> Void = { _ in }

    private let manager = CLLocationManager()
    private let uuid = UUID(uuidString: LabConstants.ibeaconUUID)!

    override init() {
        super.init()
        manager.delegate = self
    }

    var authorization: CLAuthorizationStatus { manager.authorizationStatus }

    func requestWhenInUse() {
        manager.requestWhenInUseAuthorization()
    }

    func start() {
        manager.startRangingBeacons(satisfying: CLBeaconIdentityConstraint(uuid: uuid))
    }

    func stop() {
        manager.stopRangingBeacons(satisfying: CLBeaconIdentityConstraint(uuid: uuid))
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        onAuthorization(manager.authorizationStatus)
    }

    func locationManager(
        _ manager: CLLocationManager,
        didRange beacons: [CLBeacon],
        satisfying beaconConstraint: CLBeaconIdentityConstraint
    ) {
        onBeacons(beacons)
    }

    func locationManager(
        _ manager: CLLocationManager,
        didFailRangingFor beaconConstraint: CLBeaconIdentityConstraint,
        error: Error
    ) {
        onFailure(error.localizedDescription)
    }
}
