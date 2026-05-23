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
    @EnvironmentObject var theme: ThemeManager

    var body: some View {
        TabView {
            AddItemView()
                .tabItem { Label("Add", systemImage: "plus.circle.fill") }
            ItemsListView()
                .tabItem { Label("Items", systemImage: "shippingbox.fill") }
            SettingsView()
                .tabItem { Label("Settings", systemImage: "gearshape.fill") }
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
            Text("Catalog")
                .font(.system(size: 18, weight: .semibold))
        }
    }
}
