import SwiftUI

struct SiteMenuPopover: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager
    @Binding var isPresented: Bool
    @Binding var globalSearchQuery: String

    var body: some View {
        ZStack(alignment: .top) {
            if isPresented {
                // Dimmed background
                Color.black.opacity(0.4)
                    .ignoresSafeArea()
                    .onTapGesture {
                        withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) {
                            isPresented = false
                        }
                    }
                    .transition(.opacity)

                // Popover Card
                VStack(spacing: 16) {
                    // Site Info Card
                    HStack {
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Image(systemName: "bolt.fill") // Using bolt or shippingbox
                                    .foregroundStyle(theme.current.accentColor)
                                    .font(.subheadline)
                                Text(store.groupName ?? "Homebox")
                                    .font(.headline)
                                    .foregroundStyle(.primary)
                            }
                            HStack(spacing: 12) {
                                Label("\(store.locationsFlat.count)", systemImage: "mappin.and.ellipse")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                Label("\(store.cachedItemTotal ?? 0)", systemImage: "shippingbox")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        Spacer()
                        Image(systemName: "cube.fill")
                            .font(.largeTitle)
                            .foregroundStyle(theme.current.accentColor)
                    }
                    .padding()
                    .background(theme.current.accentColor.opacity(0.15))
                    .clipShape(RoundedRectangle(cornerRadius: 12))

                    // Settings Button
                    Button {
                        withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) {
                            isPresented = false
                        }
                        // We need to navigate to Settings.
                        // A clean way is to trigger a NavigationStack path or show it as a sheet.
                        // For now, let's post a notification or use an environment binding to present Settings sheet.
                        NotificationCenter.default.post(name: .showSettings, object: nil)
                    } label: {
                        HStack {
                            Spacer()
                            Text("Settings")
                                .font(.headline)
                                .foregroundStyle(.white)
                            Spacer()
                        }
                        .padding()
                        .background(theme.current.accentColor)
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                    }
                }
                .padding(16)
                .background(theme.current.backgroundColor)
                .clipShape(RoundedRectangle(cornerRadius: 20))
                .shadow(color: .black.opacity(0.2), radius: 20, y: 10)
                .padding(.horizontal, 16)
                // Position it near the top left
                .padding(.top, 60)
                .transition(.move(edge: .top).combined(with: .opacity).combined(with: .scale(scale: 0.95, anchor: .topLeading)))
            }
        }
    }
}

extension Notification.Name {
    static let showSettings = Notification.Name("showSettings")
}
