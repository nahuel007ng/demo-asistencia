import CoreBluetooth
import Foundation

/// Escaneo legacy sin filtro de servicio. El anuncio propio no incluye la lista de 128 bits
/// para caber en 31 bytes, así que iOS no puede filtrar por UUID en el controlador.
final class CustomScanner: NSObject, CBCentralManagerDelegate {
    var onPacket: (Data, Int) -> Void = { _, _ in }
    var onState: (CBManagerState) -> Void = { _ in }

    private(set) var managerState: CBManagerState = .unknown
    private(set) var scanning = false
    var allowDuplicates = true

    private var central: CBCentralManager!
    private let service = CBUUID(string: LabConstants.serviceUUID)

    override init() {
        super.init()
        central = CBCentralManager(
            delegate: self,
            queue: nil,
            options: [CBCentralManagerOptionShowPowerAlertKey: false]
        )
        managerState = central.state
    }

    func start() {
        guard central.state == .poweredOn else { return }
        central.scanForPeripherals(
            withServices: nil,
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: allowDuplicates]
        )
        scanning = true
    }

    func stop() {
        central.stopScan()
        scanning = false
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        managerState = central.state
        if central.state != .poweredOn {
            scanning = false
        }
        onState(central.state)
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        guard let map = advertisementData[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data] else { return }
        guard let payload = map[service] else { return }
        onPacket(payload, RSSI.intValue)
    }
}
