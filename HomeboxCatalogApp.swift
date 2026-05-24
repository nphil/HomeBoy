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
    @State private var selectedTab = 1
    @State private var showAddSheet = false

    var body: some View {
        if store.isAuthenticated {
            TabView(selection: Binding(
                get: { selectedTab },
                set: { newValue in
                    if newValue == 0 {
                        showAddSheet = true
                    } else {
                        selectedTab = newValue
                    }
                }
            )) {
                Color.clear
                    .tabItem { Label("Add", systemImage: "plus.circle.fill") }
                    .tag(0)
                ItemsListView()
                    .tabItem { Label("Items", systemImage: "shippingbox.fill") }
                    .tag(1)
                LocationsTabView()
                    .tabItem { Label("Locations", systemImage: "mappin.and.ellipse") }
                    .tag(2)
                TagsTabView()
                    .tabItem { Label("Tags", systemImage: "tag.fill") }
                    .tag(3)
                SettingsView()
                    .tabItem { Label("Settings", systemImage: "gearshape.fill") }
                    .tag(4)
            }
            .sheet(isPresented: $showAddSheet) {
                AddItemView()
                    .environmentObject(store)
                    .environmentObject(theme)
            }
        } else {
            OnboardingView()
                .transition(.opacity)
        }
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
        }
    }
}
