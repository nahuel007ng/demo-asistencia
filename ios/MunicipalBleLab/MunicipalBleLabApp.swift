import SwiftUI

@main
struct MunicipalBleLabApp: App {
    @StateObject private var lab = LabController()

    var body: some Scene {
        WindowGroup {
            LabScreen(lab: lab)
                .preferredColorScheme(.dark)
        }
    }
}
