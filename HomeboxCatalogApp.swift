import SwiftUI
import UIKit

@main
struct HomeboxCatalogApp: App {
    @StateObject private var store = HomeboxStore()
    @StateObject private var theme = ThemeManager()

    init() {
        let nav = UINavigationBarAppearance()
        nav.configureWithTransparentBackground()
        UINavigationBar.appearance().standardAppearance = nav
        UINavigationBar.appearance().scrollEdgeAppearance = nav
        UINavigationBar.appearance().compactAppearance = nav

        UITableView.appearance().backgroundColor = .clear
        UITableViewCell.appearance().backgroundColor = .clear
        UIScrollView.appearance().backgroundColor = .clear
        UICollectionView.appearance().backgroundColor = .clear
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(store)
                .environmentObject(theme)
                .tint(theme.current.accentColor)
                .preferredColorScheme(theme.current.preferredColorScheme)
                .background(theme.current.backgroundColor.ignoresSafeArea())
        }
    }
}

struct ContentView: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager
    @State private var selectedTab = 0
    @State private var showSiteMenu = false
    @State private var globalSearchQuery = ""
    @State private var showSettingsSheet = false

    var body: some View {
        if store.isAuthenticated {
            ZStack {
                TabView(selection: $selectedTab) {
                    ItemsListView(globalSearchQuery: $globalSearchQuery)
                        .tabItem { Label("Items", systemImage: "shippingbox.fill") }
                        .tag(0)
                    LocationsTabView(globalSearchQuery: $globalSearchQuery)
                        .tabItem { Label("Locations", systemImage: "mappin.and.ellipse") }
                        .tag(1)
                    TagsTabView(globalSearchQuery: $globalSearchQuery)
                        .tabItem { Label("Tags", systemImage: "tag.fill") }
                        .tag(2)
                }
                .environment(\.showSiteMenu, $showSiteMenu)

                SiteMenuPopover(isPresented: $showSiteMenu, globalSearchQuery: $globalSearchQuery)
                    .environmentObject(store)
                    .environmentObject(theme)
                    .zIndex(100) // Ensure it floats on top of everything
            }
            .task {
                try? await store.refreshGroup()
            }
            .onReceive(NotificationCenter.default.publisher(for: .showSettings)) { _ in
                showSettingsSheet = true
            }
            .sheet(isPresented: $showSettingsSheet) {
                SettingsView()
                    .environmentObject(store)
                    .environmentObject(theme)
            }
        } else {
            OnboardingView()
                .transition(.opacity)
        }
    }
}

// Environment key for passing the binding down to toolbars
private struct ShowSiteMenuKey: EnvironmentKey {
    static let defaultValue: Binding<Bool> = .constant(false)
}

extension EnvironmentValues {
    var showSiteMenu: Binding<Bool> {
        get { self[ShowSiteMenuKey.self] }
        set { self[ShowSiteMenuKey.self] = newValue }
    }
}

struct BrandMark: View {
    @EnvironmentObject var theme: ThemeManager

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "shippingbox.fill")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(theme.current.accentColor)
            Text("HomeBoy")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(.primary)
        }
    }
}
