import SwiftUI

struct RootView: View {
    @EnvironmentObject var store: AppStore

    var body: some View {
        Group {
            if store.onboarded {
                main
            } else {
                OnboardingView()
            }
        }
        .preferredColorScheme(.dark)
    }

    private var main: some View {
        TabView(selection: $store.tab) {
            LibraryView().tabItem { Label("库", systemImage: "folder") }.tag(AppTab.library)
            LiveView(mode: .scribe).tabItem { Label("转写", systemImage: "mic") }.tag(AppTab.scribe)
            LiveView(mode: .translator).tabItem { Label("翻译", systemImage: "globe") }.tag(AppTab.translator)
            SettingsView().tabItem { Label("设置", systemImage: "gearshape") }.tag(AppTab.settings)
        }
    }
}
