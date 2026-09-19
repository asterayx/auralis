import SwiftUI

@main
struct AuralisScene: App {
    @StateObject private var store = AppStore()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
        }
        #if os(macOS)
        .defaultSize(width: 1100, height: 740)
        #endif
    }
}
