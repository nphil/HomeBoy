import SwiftUI

struct ArchivedItemsView: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager

    @State private var items: [HBItem] = []
    @State private var isLoading = false
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
            ItemListRowContent(item: item, thumbStore: thumbStore)
                .contentShape(Rectangle())
                .onTapGesture {
                    if selectMode { toggleSelection(item) }
                }
        }
        .background {
            RoundedRectangle(cornerRadius: 14).fill(.ultraThinMaterial)
            RoundedRectangle(cornerRadius: 14).fill(theme.current.accentColor.opacity(isSelected ? 0.15 : 0.06))
        }
        .overlay(RoundedRectangle(cornerRadius: 14)
            .stroke(isSelected ? theme.current.accentColor.opacity(0.6) : theme.current.accentColor.opacity(0.18),
                    lineWidth: isSelected ? 2 : 1))
        .highPriorityGesture(LongPressGesture(minimumDuration: 0.4).onEnded { _ in
            if !selectMode {
                withAnimation { selectMode = true; selectedIds.insert(item.id) }
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            }
        })
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
        for id in ids {
            do {
                let detail = try await client.getItem(id: id)
                var update = HBItemUpdate(from: detail)
                update.archived = false
                try await client.updateItem(update)
                succeeded.append(id)
            } catch {}
        }
        await MainActor.run {
            withAnimation { items.removeAll { succeeded.contains($0.id) } }
            selectedIds = []
            selectMode = false
            UINotificationFeedbackGenerator().notificationOccurred(.success)
        }
    }

    private func load() async {
        guard let client = store.client else { return }
        isLoading = true
        do {
            let resp = try await client.listItems(includeArchived: true, pageSize: 1000)
            items = resp.items.filter { $0.archived == true }
                .sorted { $0.name.lowercased() < $1.name.lowercased() }
        } catch {}
        isLoading = false
    }
}
