import SwiftUI
import UIKit
import UserNotifications

@main
struct HomeboxCatalogApp: App {
    @StateObject private var store = HomeboxStore()
    @StateObject private var theme = ThemeManager()
    @StateObject private var ai = AIModelManager()

    init() {
        UNUserNotificationCenter.current().delegate = NotificationManager.shared
        NotificationManager.shared.registerCategories()

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
                .environmentObject(ai)
                .tint(theme.current.accentColor)
                .preferredColorScheme(theme.current.preferredColorScheme)
                .background(theme.current.backgroundColor.ignoresSafeArea())
        }
    }
}

struct ContentView: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager
    @EnvironmentObject var ai: AIModelManager
    @State private var selectedTab = 0
    @State private var globalSearchQuery = ""
    @State private var showSettingsSheet = false
    @State private var toastMessage: String?

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

                // Toast notification — floats above everything
                VStack {
                    Spacer()
                    if let msg = toastMessage {
                        Text(msg)
                            .font(.callout.weight(.medium))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 20)
                            .padding(.vertical, 12)
                            .background(Color.black.opacity(0.78))
                            .clipShape(Capsule())
                            .padding(.bottom, 90)
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                }
                .animation(.spring(response: 0.35, dampingFraction: 0.8), value: toastMessage)
                .allowsHitTesting(false)
                .zIndex(200)
            }
            .task {
                // Refresh group list + location tree + item count concurrently
                async let g: Void = store.refreshGroups()
                async let l: Void = store.refreshLocations()
                try? await g
                try? await l
                await store.refreshItemTotal()
                // Once we know the groups, fetch per-group stats for the popover cards
                await store.refreshAllGroupStats()
            }
            .onReceive(NotificationCenter.default.publisher(for: .showSettings)) { _ in
                showSettingsSheet = true
            }
            .onReceive(NotificationCenter.default.publisher(for: .showToast)) { notif in
                if let msg = notif.userInfo?["message"] as? String {
                    showToast(msg)
                }
            }
            .sheet(isPresented: $showSettingsSheet) {
                SettingsView()
                    .environmentObject(store)
                    .environmentObject(theme)
                    .environmentObject(ai)
            }
        } else {
            OnboardingView()
                .transition(.opacity)
        }
    }

    private func showToast(_ message: String) {
        toastMessage = message
        // The toast is visual-only; make sure VoiceOver users hear it too.
        UIAccessibility.post(notification: .announcement, argument: message)
        Task {
            try? await Task.sleep(for: .seconds(2.5))
            toastMessage = nil
        }
    }
}

struct BrandMark: View {
    @EnvironmentObject var theme: ThemeManager

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "shippingbox.fill")
                .font(.headline)
                .foregroundStyle(theme.current.accentColor)
            Text("HomeBoy")
                .font(.headline)
                .foregroundStyle(.primary)
        }
    }
}
