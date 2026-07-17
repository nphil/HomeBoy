import SwiftUI

struct ArchivedItemsView: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager

    @State private var items: [HBItem] = []
    @State private var isLoading = false
    @State private var loadError: String?
    @State private var selectMode = false
    @State private var selectedIds: Set<String> = []
    @State private var thumbStore = ThumbnailStore()

    var body: some View {
        ZStack {
            theme.current.backgroundColor.ignoresSafeArea()
            content
        }
        .navigationTitle(selectMode ? (selectedIds.isEmpty ? "Select Items" : "\(selectedIds.count) Selected") : "Archived Items")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { toolbarContent }
        .toolbar(selectMode ? .hidden : .visible, for: .tabBar)
        .task { await load() }
        .refreshable { await load() }
    }

    // MARK: - Content

    @ViewBuilder
    private var content: some View {
        if isLoading && items.isEmpty {
            ProgressView("Loading…")
        } else if let loadError, items.isEmpty {
            VStack(spacing: 12) {
                Image(systemName: "exclamationmark.triangle").font(.system(size: 40)).foregroundStyle(.orange)
                Text("Couldn't load archived items").font(.title3.weight(.semibold))
                Text(loadError).font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center).padding(.horizontal, 24)
                Button("Try again") { Task { await load() } }.buttonStyle(.glass)
            }
        } else if items.isEmpty {
            VStack(spacing: 12) {
                Image(systemName: "archivebox").font(.system(size: 48)).foregroundStyle(.secondary)
                Text("No archived items").font(.title3.weight(.semibold))
                Text("Archived items appear here.").font(.callout).foregroundStyle(.secondary)
            }
        } else {
            ScrollView {
                LazyVStack(spacing: 6) {
                    ForEach(items) { item in
                        SwipeRevealRow(
                            buttonLabel: "Unarchive",
                            buttonIcon: "arrow.uturn.up.circle.fill",
                            buttonColor: theme.current.accentColor,
                            buttonForeground: theme.current.onAccentColor,
                            disabled: selectMode
                        ) {
                            Task { await unarchiveItem(item) }
                        } content: {
                            rowContent(item)
                        }
                        .padding(.horizontal, 16)
                    }
                }
                .padding(.vertical, 8)
                .padding(.bottom, 80)
            }
            .scrollIndicators(.hidden)
        }
    }

    @ViewBuilder
    private func rowContent(_ item: HBItem) -> some View {
        let isSelected = selectedIds.contains(item.id)
        HStack(spacing: 0) {
            if selectMode {
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(isSelected ? theme.current.accentColor : Color.secondary.opacity(0.5))
                    .font(.title3)
                    .padding(.leading, 12)
                    .transition(.move(edge: .leading).combined(with: .opacity))
            }
            ItemListRowContent(item: item, thumbStore: thumbStore,
                               breadcrumb: store.breadcrumb(for: item),
                               client: store.client, localDB: store.localDB)
                .contentShape(Rectangle())
                .onTapGesture {
                    if selectMode { toggleSelection(item) }
                }
        }
        .background(
            RoundedRectangle(cornerRadius: 14)
                .fill(theme.current.accentColor.opacity(isSelected ? 0.15 : 0.06))
        )
        .overlay(RoundedRectangle(cornerRadius: 14)
            .strokeBorder(isSelected ? theme.current.accentColor.opacity(0.6) : theme.current.accentColor.opacity(0.18),
                          lineWidth: isSelected ? 2 : 1))
    }

    // MARK: - Toolbar

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        if selectMode {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button("Done") {
                    withAnimation { selectMode = false; selectedIds = [] }
                }
            }
            ToolbarItemGroup(placement: .bottomBar) {
                Button("Select All") {
                    selectedIds = Set(items.map(\.id))
                }
                Spacer()
                Button("Deselect All") {
                    selectedIds = []
                }
                .disabled(selectedIds.isEmpty)
                Spacer()
                Button("Unarchive") {
                    Task { await unarchiveSelected() }
                }
                .bold()
                .disabled(selectedIds.isEmpty)
            }
        } else {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Select") {
                    withAnimation { selectMode = true }
                }
                .disabled(items.isEmpty)
                .accessibilityLabel("Select items")
            }
        }
    }

    // MARK: - Actions

    private func toggleSelection(_ item: HBItem) {
        if selectedIds.contains(item.id) { selectedIds.remove(item.id) }
        else { selectedIds.insert(item.id) }
    }

    private func unarchiveItem(_ item: HBItem) async {
        guard let client = store.client else { return }
        do {
            let detail = try await client.getItem(id: item.id)
            var update = HBItemUpdate(from: detail)
            update.archived = false
            try await client.updateItem(update)
            await MainActor.run {
                withAnimation { items.removeAll { $0.id == item.id } }
                UINotificationFeedbackGenerator().notificationOccurred(.success)
            }
        } catch {
            NotificationCenter.default.post(name: .showToast, object: nil,
                                            userInfo: ["message": "Unarchive failed"])
        }
    }

    private func unarchiveSelected() async {
        guard let client = store.client else { return }
        let ids = Array(selectedIds)
        var succeeded: [String] = []
        var lastError: String? = nil
        for id in ids {
            do {
                let detail = try await client.getItem(id: id)
                var update = HBItemUpdate(from: detail)
                update.archived = false
                try await client.updateItem(update)
                succeeded.append(id)
            } catch {
                lastError = error.localizedDescription
            }
        }
        let successCount = succeeded.count
        let totalCount = ids.count
        let failureMsg = lastError
        await MainActor.run {
            withAnimation {
                items.removeAll { succeeded.contains($0.id) }
                selectedIds = []
                selectMode = false
            }
            if successCount == totalCount {
                UINotificationFeedbackGenerator().notificationOccurred(.success)
                NotificationCenter.default.post(name: .showToast, object: nil,
                                                userInfo: ["message": "Unarchived \(successCount) item\(successCount == 1 ? "" : "s")"])
            } else if successCount > 0 {
                UINotificationFeedbackGenerator().notificationOccurred(.warning)
                NotificationCenter.default.post(name: .showToast, object: nil,
                                                userInfo: ["message": "Unarchived \(successCount) of \(totalCount). Last error: \(failureMsg ?? "unknown")"])
            } else {
                UINotificationFeedbackGenerator().notificationOccurred(.error)
                NotificationCenter.default.post(name: .showToast, object: nil,
                                                userInfo: ["message": "Unarchive failed: \(failureMsg ?? "unknown")"])
            }
        }
    }

    private func load() async {
        guard let client = store.client else { return }
        isLoading = true
        loadError = nil
        do {
            let resp = try await client.listItems(includeArchived: true, pageSize: 1000)
            items = resp.items.filter { $0.archived == true }
                .sorted { $0.name.lowercased() < $1.name.lowercased() }
        } catch {
            // Surface the failure instead of masquerading as an empty archive.
            loadError = error.localizedDescription
        }
        isLoading = false
    }
}
