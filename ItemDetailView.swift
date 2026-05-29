import SwiftUI
import PhotosUI
import UIKit

/// Detail view for a single item: photos, all fields, edit + delete.
struct ItemDetailView: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager
    @Environment(\.dismiss) var dismiss

    let itemId: String
    /// Called whenever the item changes or is deleted, so the parent list refreshes.
    var onChange: () -> Void = {}

    @State private var item: HBItemDetail?
    @State private var children: [HBItem] = []
    @State private var maintenance: [HBMaintenanceEntry] = []
    @State private var isLoading = false
    @State private var loadError: String?
    @State private var showEdit = false
    @State private var confirmDelete = false
    @State private var isDeleting = false
    @State private var showAddSubItem = false
    @State private var showMaintenanceSheet = false
    @State private var editingEntry: HBMaintenanceEntry? = nil

    var body: some View {
        ZStack {
            theme.current.backgroundColor.ignoresSafeArea()
            content
        }
        .navigationTitle(item?.name ?? "Item")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button { showEdit = true } label: { Label("Edit", systemImage: "pencil") }
                        .disabled(item == nil)
                    Button {
                        Task { await toggleArchive() }
                    } label: {
                        Label(item?.archived == true ? "Unarchive" : "Archive",
                              systemImage: item?.archived == true ? "archivebox.fill" : "archivebox")
                    }
                    .disabled(item == nil)
                    Divider()
                    Button(role: .destructive) { confirmDelete = true } label: { Label("Delete", systemImage: "trash") }
                } label: {
                    Image(systemName: "ellipsis.circle").font(.title3)
                }
            }
        }
        .task { await load() }
        .sheet(isPresented: $showEdit) {
            if let item {
                EditItemSheet(original: item) { updated in
                    self.item = updated
                    onChange()
                }
                .environmentObject(store)
                .environmentObject(theme)
            }
        }
        .floatingCardCover(
            isPresented: $showAddSubItem,
            onDismiss: { Task { await load() } }
        ) {
            if let item = item {
                AddItemView(
                    parentId: item.id,
                    parentName: item.name,
                    parentLocationId: item.location?.id,
                    onDismiss: { showAddSubItem = false }
                )
                .environmentObject(store)
                .environmentObject(theme)
            }
        }
        .floatingCardCover(
            isPresented: $showMaintenanceSheet,
            onDismiss: { Task { await load() } }
        ) {
            MaintenanceEntrySheet(
                itemId: itemId,
                itemName: item?.name ?? "",
                existing: editingEntry
            )
            .environmentObject(store)
            .environmentObject(theme)
        }
        .alert("Delete item?", isPresented: $confirmDelete) {
            Button("Cancel", role: .cancel) {}
            Button("Delete", role: .destructive) { Task { await performDelete() } }
        } message: {
            Text("This removes the item from Homebox permanently.")
        }
        .toolbar(showAddSubItem ? .hidden : .visible, for: .tabBar)
    }

    @ViewBuilder
    private var content: some View {
        if isLoading && item == nil {
            ProgressView("Loading…")
        } else if let item {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    photoCarousel(item)
                    headerCard(item)
                    if hasAnyExtraField(item) {
                        extraDetailsCard(item)
                    }
                    if !(item.notes ?? "").isEmpty {
                        notesCard(item)
                    }
                    subItemsCard(item)
                    maintenanceCard(item)
                }
                .padding(16)
                .padding(.bottom, 60)
            }
            .scrollIndicators(.hidden)
        } else if let loadError {
            VStack(spacing: 10) {
                Image(systemName: "exclamationmark.triangle").font(.system(size: 32)).foregroundStyle(.orange)
                Text("Couldn't load").font(.title3.weight(.semibold))
                Text(loadError).font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center).padding(.horizontal, 24)
                Button("Try again") { Task { await load() } }.buttonStyle(.glass)
            }
        }
    }

    // MARK: - Cards

    private func photoCarousel(_ item: HBItemDetail) -> some View {
        let photos = (item.attachments ?? []).filter { $0.type.lowercased() == "photo" }
        return Group {
            if photos.isEmpty {
                EmptyView()
            } else if photos.count == 1, let only = photos.first {
                AuthImage(itemId: item.id, attachmentId: only.id)
                    .frame(maxWidth: .infinity)
                    .frame(height: 280)
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                    .overlay(RoundedRectangle(cornerRadius: 16).stroke(theme.current.accentColor.opacity(0.18), lineWidth: 1))
            } else {
                TabView {
                    ForEach(photos) { att in
                        AuthImage(itemId: item.id, attachmentId: att.id)
                            .frame(maxWidth: .infinity)
                            .clipShape(RoundedRectangle(cornerRadius: 16))
                    }
                }
                .tabViewStyle(.page)
                .frame(height: 280)
            }
        }
    }

    private func headerCard(_ item: HBItemDetail) -> some View {
        GlassCard {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .firstTextBaseline) {
                    Text(item.name).font(.title2.weight(.semibold))
                    Spacer()
                    if item.archived == true {
                        Label("Archived", systemImage: "archivebox")
                            .font(.caption.weight(.medium))
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 8).padding(.vertical, 3)
                            .background(Capsule().fill(Color.secondary.opacity(0.15)))
                    }
                    if item.quantityInt > 1 {
                        CountBadge(count: item.quantityInt,
                                   font: .callout.monospacedDigit().weight(.semibold))
                    }
                }
                if let parent = item.parent {
                    NavigationLink(value: ItemDetailRoute(id: parent.id)) {
                        HStack(spacing: 4) {
                            Image(systemName: "arrow.up.square").font(.caption)
                            Text("Part of: \(parent.name)").font(.callout)
                        }
                        .foregroundStyle(theme.current.accentColor)
                    }
                }
                if let loc = item.location {
                    HStack(spacing: 4) {
                        Image(systemName: "mappin.and.ellipse").font(.caption)
                        Text(locationPath(loc)).font(.callout).monospaced()
                    }
                    .foregroundStyle(.secondary)
                }
                if let desc = item.description, !desc.isEmpty {
                    Text(desc).font(.body).foregroundStyle(.primary).fixedSize(horizontal: false, vertical: true)
                }
                if let tags = item.tags, !tags.isEmpty {
                    TagChipsRow(tags: tags)
                }
            }
        }
    }

    private func extraDetailsCard(_ item: HBItemDetail) -> some View {
        GlassCard(title: "Details") {
            VStack(spacing: 8) {
                detailRow("Asset ID", value: item.assetId, predicate: { !$0.isEmpty && $0 != "0" && $0 != "000-000" })
                detailRow("Manufacturer", value: item.manufacturer)
                detailRow("Model", value: item.modelNumber)
                detailRow("Serial", value: item.serialNumber)
                detailRow("Purchase from", value: item.purchaseFrom)
                detailRow("Purchase date", value: item.purchaseTime, predicate: isRealDate)
                detailRow("Purchase price", value: priceString(item.purchasePrice))
                detailRow("Warranty expires", value: item.warrantyExpires, predicate: isRealDate)
            }
        }
    }

    private func notesCard(_ item: HBItemDetail) -> some View {
        GlassCard(title: "Notes") {
            Text(item.notes ?? "").font(.body).fixedSize(horizontal: false, vertical: true)
        }
    }

    @ViewBuilder
    private func detailRow(_ label: String, value: String?, predicate: (String) -> Bool = { !$0.isEmpty }) -> some View {
        if let v = value, predicate(v) {
            HStack(alignment: .firstTextBaseline) {
                Text(label).font(.caption.weight(.medium)).foregroundStyle(.secondary)
                Spacer()
                Text(v).font(.callout).foregroundStyle(.primary).multilineTextAlignment(.trailing)
            }
        }
    }

    // MARK: - Sub-items & Maintenance cards

    private func subItemsCard(_ item: HBItemDetail) -> some View {
        GlassCard(title: "Components") {
            VStack(alignment: .leading, spacing: 8) {
                ForEach(children.sorted { $0.name.lowercased() < $1.name.lowercased() }) { child in
                    NavigationLink(value: ItemDetailRoute(id: child.id)) {
                        HStack {
                            Text(child.name).font(.body).foregroundStyle(.primary)
                            Spacer()
                            Image(systemName: "chevron.right").foregroundStyle(.tertiary).font(.caption)
                        }
                    }
                    .buttonStyle(.plain)
                }
                Button {
                    withAnimation(.spring(response: 0.28, dampingFraction: 0.8)) {
                        showAddSubItem = true
                    }
                } label: {
                    Label("Add component", systemImage: "plus.circle")
                        .font(.callout)
                        .foregroundStyle(theme.current.accentColor)
                }
                .buttonStyle(.plain)
                .padding(.top, 2)
            }
        }
    }

    private func maintenanceCard(_ item: HBItemDetail) -> some View {
        GlassCard(title: "Maintenance") {
            VStack(alignment: .leading, spacing: 8) {
                let sorted = maintenance.sorted {
                    let a = $0.scheduledDate ?? $0.completedDate ?? $0.createdAt ?? ""
                    let b = $1.scheduledDate ?? $1.completedDate ?? $1.createdAt ?? ""
                    return a < b
                }
                ForEach(sorted) { entry in
                    MaintenanceRow(entry: entry)
                        .contentShape(Rectangle())
                        .onTapGesture {
                            editingEntry = entry
                            showMaintenanceSheet = true
                        }
                        .swipeActions(edge: .leading, allowsFullSwipe: true) {
                            Button {
                                Task { await markDone(entry) }
                            } label: { Label("Done", systemImage: "checkmark.circle.fill") }
                            .tint(.green)
                        }
                        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                            Button(role: .destructive) {
                                Task { await deleteMaintEntry(entry) }
                            } label: { Label("Delete", systemImage: "trash") }
                        }
                }
                if maintenance.isEmpty {
                    Text("Nothing scheduled yet")
                        .font(.callout).foregroundStyle(.secondary)
                }
                Button {
                    editingEntry = nil
                    showMaintenanceSheet = true
                } label: {
                    Label("Schedule maintenance", systemImage: "calendar.badge.plus")
                        .font(.callout)
                        .foregroundStyle(theme.current.accentColor)
                }
                .buttonStyle(.plain)
                .padding(.top, 2)
            }
        }
    }

    // MARK: - Helpers

    private func locationPath(_ loc: HBLocationSummary) -> String {
        let path = store.pathString(forLocationId: loc.id)
        return path.isEmpty ? loc.name : path
    }

    private func hasAnyExtraField(_ item: HBItemDetail) -> Bool {
        let fields: [String?] = [
            item.manufacturer, item.modelNumber, item.serialNumber,
            item.purchaseFrom, item.purchaseTime, item.warrantyExpires,
        ]
        if fields.contains(where: { ($0 ?? "").isEmpty == false }) { return true }
        if let p = item.purchasePrice, p > 0 { return true }
        if let asset = item.assetId, !asset.isEmpty, asset != "0", asset != "000-000" { return true }
        return false
    }

    private func isRealDate(_ s: String) -> Bool {
        !s.isEmpty && !s.hasPrefix("0001-01-01")
    }

    private func priceString(_ p: Double?) -> String? {
        guard let p, p > 0 else { return nil }
        return String(format: "%.2f", p)
    }

    private func load() async {
        isLoading = true; loadError = nil
        let pending = store.localDB.pendingMaintenanceOps(for: itemId).map { $0.asDisplayEntry() }
        if store.isOffline {
            if let cached = store.localDB.items.first(where: { $0.id == itemId }) {
                item = HBItemDetail(offline: cached)
            }
            maintenance = pending
            isLoading = false
            return
        }
        guard let client = store.client else { isLoading = false; return }
        do {
            async let itemTask  = client.getItem(id: itemId)
            async let childTask = client.listItems(parentIds: [itemId], pageSize: 500)
            async let maintTask = client.listMaintenance(itemId: itemId)
            item     = try await itemTask
            children = (try? await childTask)?.items ?? []
            let fetched = (try? await maintTask) ?? []
            maintenance = (fetched + pending).sorted {
                let a = $0.scheduledDate ?? $0.completedDate ?? $0.createdAt ?? ""
                let b = $1.scheduledDate ?? $1.completedDate ?? $1.createdAt ?? ""
                return a > b
            }
        } catch {
            loadError = error.localizedDescription
        }
        isLoading = false
    }

    private func toggleArchive() async {
        guard !store.isOffline else {
            NotificationCenter.default.post(name: .showToast, object: nil, userInfo: ["message": "Not available in offline mode"])
            return
        }
        guard let client = store.client, let current = item else { return }
        var update = HBItemUpdate(from: current)
        update.archived = !(current.archived ?? false)
        do {
            try await client.updateItem(update)
            item = try await client.getItem(id: itemId)
            let msg = update.archived ? "Item archived" : "Item unarchived"
            NotificationCenter.default.post(name: .showToast, object: nil, userInfo: ["message": msg])
            onChange()
        } catch {
            NotificationCenter.default.post(name: .showToast, object: nil, userInfo: ["message": "Failed: \(error.localizedDescription)"])
        }
    }

    private func deleteMaintEntry(_ entry: HBMaintenanceEntry) async {
        if entry.id.hasPrefix("pending-") {
            let localId = String(entry.id.dropFirst("pending-".count))
            store.localDB.dequeueMaintenance(id: localId)
            maintenance.removeAll { $0.id == entry.id }
            return
        }
        guard !store.isOffline else {
            NotificationCenter.default.post(name: .showToast, object: nil, userInfo: ["message": "Not available in offline mode"])
            return
        }
        guard let client = store.client else { return }
        do {
            try await client.deleteMaintenance(id: entry.id)
            NotificationManager.shared.cancel(entryId: entry.id)
            maintenance.removeAll { $0.id == entry.id }
        } catch {
            NotificationCenter.default.post(name: .showToast, object: nil, userInfo: ["message": "Delete failed"])
        }
    }

    private func markDone(_ entry: HBMaintenanceEntry) async {
        guard !store.isOffline else {
            NotificationCenter.default.post(name: .showToast, object: nil, userInfo: ["message": "Not available in offline mode"])
            return
        }
        guard let client = store.client else { return }
        do {
            let update = HBMaintenanceCreate(
                name: entry.name,
                description: entry.description ?? "",
                completedDate: MaintenanceEntrySheet.dateOnly(Date()),
                scheduledDate: entry.scheduledDate ?? "",
                cost: entry.cost ?? 0
            )
            try await client.updateMaintenance(id: entry.id, entry: update)
            NotificationManager.shared.cancel(entryId: entry.id)
            await load()
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            NotificationCenter.default.post(name: .showToast, object: nil, userInfo: ["message": "Marked as done"])
        } catch {
            NotificationCenter.default.post(name: .showToast, object: nil, userInfo: ["message": "Failed: \(error.localizedDescription)"])
        }
    }

    private func performDelete() async {
        guard !store.isOffline else {
            NotificationCenter.default.post(name: .showToast, object: nil, userInfo: ["message": "Not available in offline mode"])
            return
        }
        guard let client = store.client else { return }
        isDeleting = true
        do {
            try await client.deleteItem(id: itemId)
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            onChange()
            dismiss()
        } catch {
            loadError = error.localizedDescription
            UINotificationFeedbackGenerator().notificationOccurred(.error)
        }
        isDeleting = false
    }
}

// MARK: - Edit sheet

struct EditItemSheet: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager
    @Environment(\.dismiss) var dismiss

    let original: HBItemDetail
    /// Called with the updated detail once the PUT succeeds.
    var onSaved: (HBItemDetail) -> Void = { _ in }

    @State private var name: String
    @State private var quantity: Int
    @State private var description: String
    @State private var notes: String
    @State private var serial: String
    @State private var model: String
    @State private var manufacturer: String
    @State private var locationId: String?
    @State private var tagIds: Set<String>
    @State private var showLocationPicker = false
    @State private var showTagPicker = false
    @State private var photo: UIImage?
    @State private var pickerItem: PhotosPickerItem?
    @State private var showCamera = false
    @State private var isSaving = false
    @State private var errorMsg: String?
    @State private var attachmentsToDelete: Set<String> = []
    @State private var showBarcodeScanner = false
    @State private var showProductMatch = false
    @State private var pendingProducts: [HBBarcodeProduct] = []

    init(original: HBItemDetail, onSaved: @escaping (HBItemDetail) -> Void = { _ in }) {
        self.original = original
        self.onSaved = onSaved
        _name = State(initialValue: original.name)
        _quantity = State(initialValue: original.quantityInt)
        _description = State(initialValue: original.description ?? "")
        _notes = State(initialValue: original.notes ?? "")
        _serial = State(initialValue: original.serialNumber ?? "")
        _model = State(initialValue: original.modelNumber ?? "")
        _manufacturer = State(initialValue: original.manufacturer ?? "")
        _locationId = State(initialValue: original.location?.id)
        _tagIds = State(initialValue: Set(original.tags?.map { $0.id } ?? []))
    }

    private var existingPhotos: [HBAttachmentRef] {
        (original.attachments ?? []).filter { $0.type.lowercased() == "photo" && !attachmentsToDelete.contains($0.id) }
    }

    @ViewBuilder private var itemSection: some View {
        Section("Item") {
            HStack {
                TextField("Name", text: $name).textInputAutocapitalization(.sentences)
                Button { showBarcodeScanner = true } label: {
                    Image(systemName: "barcode.viewfinder")
                        .foregroundStyle(theme.current.accentColor)
                }
                .buttonStyle(.borderless)
            }
            Stepper("Quantity: \(quantity)", value: $quantity, in: 1...9999)
            TextField("Description", text: $description, axis: .vertical).lineLimit(1...4)
        }
    }

    @ViewBuilder private var locationSection: some View {
        Section("Location") {
            Button { showLocationPicker = true } label: {
                HStack {
                    Image(systemName: "mappin.and.ellipse")
                    Text(locationId.flatMap { store.pathString(forLocationId: $0) } ?? "Pick location")
                        .foregroundStyle(locationId == nil ? .secondary : .primary)
                    Spacer()
                    Image(systemName: "chevron.right").foregroundStyle(.tertiary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
    }

    @ViewBuilder private var tagsSection: some View {
        Section("Tags") {
            Button { showTagPicker = true } label: {
                HStack {
                    Image(systemName: "tag")
                    Text(tagIds.isEmpty ? "Pick tags" : "\(tagIds.count) selected")
                        .foregroundStyle(tagIds.isEmpty ? .secondary : .primary)
                    Spacer()
                    Image(systemName: "chevron.right").foregroundStyle(.tertiary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
    }

    @ViewBuilder private var identificationSection: some View {
        Section("Identification") {
            TextField("Manufacturer", text: $manufacturer).textInputAutocapitalization(.words)
            TextField("Model number", text: $model).textInputAutocapitalization(.never).autocorrectionDisabled()
            TextField("Serial number", text: $serial).textInputAutocapitalization(.never).autocorrectionDisabled()
        }
    }

    @ViewBuilder private var notesSection: some View {
        Section("Notes") {
            TextField("Notes", text: $notes, axis: .vertical).lineLimit(1...6)
        }
    }

    @ViewBuilder private var photosSection: some View {
        Section("Photos") {
            if !existingPhotos.isEmpty {
                ForEach(existingPhotos) { att in
                    HStack {
                        AuthImage(itemId: original.id, attachmentId: att.id, allowsFullScreen: false)
                            .frame(width: 50, height: 50)
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                        
                        Text(att.title ?? "Photo")
                            .font(.callout)
                            .foregroundStyle(.secondary)
                        
                        Spacer()
                        
                        Button(role: .destructive) {
                            withAnimation {
                                _ = attachmentsToDelete.insert(att.id)
                            }
                        } label: {
                            Image(systemName: "trash")
                                .foregroundStyle(.red)
                        }
                        .buttonStyle(.borderless)
                    }
                }
            }
            
            if let photo {
                HStack {
                    Image(uiImage: photo).resizable().scaledToFill()
                        .frame(width: 50, height: 50)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                    Text("New photo will be uploaded on save")
                        .font(.caption).foregroundStyle(.secondary)
                    Spacer()
                    Button(role: .destructive) { self.photo = nil; pickerItem = nil } label: { Image(systemName: "xmark.circle.fill") }
                        .buttonStyle(.borderless)
                }
            } else {
                HStack(spacing: 10) {
                    if UIImagePickerController.isSourceTypeAvailable(.camera) {
                        Button { showCamera = true } label: { Label("Camera", systemImage: "camera").frame(maxWidth: .infinity) }
                            .buttonStyle(.bordered)
                    }
                    PhotosPicker(selection: $pickerItem, matching: .images, photoLibrary: .shared()) {
                        Label("Library", systemImage: "photo").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                }
            }
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                itemSection
                locationSection
                tagsSection
                identificationSection
                notesSection
                photosSection

                if let errorMsg {
                    Section { Label(errorMsg, systemImage: "exclamationmark.triangle.fill").foregroundStyle(.red).font(.callout) }
                }
            }
            .scrollContentBackground(.hidden)
            .scrollIndicators(.hidden)
            .background(theme.current.backgroundColor.ignoresSafeArea())
            .navigationTitle("Edit item")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        Task { await save() }
                    } label: {
                        if isSaving { ProgressView().controlSize(.small) }
                        else { Text("Save").bold() }
                    }
                    .disabled(isSaving || name.trimmingCharacters(in: .whitespaces).isEmpty || locationId == nil)
                }
            }
            .sheet(isPresented: $showLocationPicker) {
                LocationPickerSheet(selectedId: $locationId).environmentObject(store).environmentObject(theme)
            }
            .sheet(isPresented: $showTagPicker) {
                TagPickerSheet(selectedIds: $tagIds).environmentObject(store).environmentObject(theme)
            }
            .sheet(isPresented: $showCamera) {
                CameraSheet { img in photo = downscale(img) }.ignoresSafeArea()
            }
            .sheet(isPresented: $showBarcodeScanner) {
                BarcodeScannerSheet(mode: .barcode) { code in
                    showBarcodeScanner = false
                    Task { await lookupBarcodeForEdit(code) }
                }
                .ignoresSafeArea()
            }
            .sheet(isPresented: $showProductMatch) {
                ProductMatchSheet(
                    products: pendingProducts,
                    onAccept: { applyProduct($0) },
                    onScanAgain: { showBarcodeScanner = true }
                )
                .environmentObject(theme)
            }
            .onChange(of: pickerItem) { _, newItem in
                guard let newItem else { return }
                Task {
                    if let data = try? await newItem.loadTransferable(type: Data.self),
                       let img = UIImage(data: data) {
                        await MainActor.run { photo = downscale(img) }
                    }
                }
            }
        }
    }

    private func lookupBarcodeForEdit(_ code: String) async {
        guard let client = store.client else { return }
        do {
            let products = try await client.searchFromBarcode(data: code)
            await MainActor.run {
                if products.isEmpty {
                    NotificationCenter.default.post(name: .showToast, object: nil,
                                                    userInfo: ["message": "No product found for that barcode"])
                } else {
                    pendingProducts = products
                    showProductMatch = true
                }
            }
        } catch {
            NotificationCenter.default.post(name: .showToast, object: nil,
                                            userInfo: ["message": "Barcode lookup failed"])
        }
    }

    private func applyProduct(_ product: HBBarcodeProduct) {
        if let n = product.item?.name, !n.isEmpty { name = n }
        if let mfr = product.manufacturer { manufacturer = mfr }
        if let m = product.modelNumber { model = m }
        if let d = product.item?.description, !d.isEmpty { description = d }
    }

    private func save() async {
        guard !store.isOffline else { errorMsg = "Not available in offline mode"; return }
        guard let client = store.client, let locId = locationId else { return }
        errorMsg = nil; isSaving = true
        var update = HBItemUpdate(from: original, overrideLocationId: locId, overrideTagIds: Array(tagIds))
        update.name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        update.quantity = Double(quantity)
        update.description = description
        update.notes = notes
        update.serialNumber = serial
        update.modelNumber = model
        update.manufacturer = manufacturer
        do {
            try await client.updateItem(update)
            
            // Delete marked attachments
            for attId in attachmentsToDelete {
                try await client.deleteAttachment(itemId: original.id, attachmentId: attId)
            }

            if let photo, let data = photo.jpegData(compressionQuality: 0.82) {
                let filename = "photo-\(Int(Date().timeIntervalSince1970)).jpg"
                let remainingPhotosCount = (original.attachments ?? []).filter { $0.type.lowercased() == "photo" && !attachmentsToDelete.contains($0.id) }.count
                let setPrimary = remainingPhotosCount == 0
                try await client.uploadAttachment(itemId: original.id, fileData: data, filename: filename, primary: setPrimary)
            }
            let fresh = try await client.getItem(id: original.id)
            await MainActor.run {
                onSaved(fresh)
                UINotificationFeedbackGenerator().notificationOccurred(.success)
                dismiss()
            }
        } catch {
            await MainActor.run {
                errorMsg = error.localizedDescription
                UINotificationFeedbackGenerator().notificationOccurred(.error)
            }
        }
        isSaving = false
    }
}

// MARK: - Auth-aware image view

/// AsyncImage doesn't allow custom headers; this fetches via HomeboxClient,
/// which adds the Bearer token, and renders the UIImage.
struct AuthImage: View {
    @EnvironmentObject var store: HomeboxStore
    let itemId: String
    let attachmentId: String
    var allowsFullScreen: Bool = true

    @State private var image: UIImage?
    @State private var failed = false
    @State private var showFullScreen = false

    var body: some View {
        Group {
            if let image {
                if allowsFullScreen {
                    Image(uiImage: image).resizable().scaledToFill()
                        .contentShape(Rectangle())
                        .onTapGesture { showFullScreen = true }
                        .fullScreenCover(isPresented: $showFullScreen) {
                            FullScreenImageView(image: image)
                        }
                } else {
                    Image(uiImage: image).resizable().scaledToFill()
                }
            } else if failed {
                ZStack {
                    Color.gray.opacity(0.15)
                    Image(systemName: "photo.fill").font(.system(size: 36)).foregroundStyle(.secondary)
                }
            } else {
                ZStack {
                    Color.gray.opacity(0.10)
                    ProgressView()
                }
            }
        }
        .task(id: attachmentId) {
            guard let client = store.client else { return }
            do {
                let data = try await client.attachmentData(itemId: itemId, attachmentId: attachmentId)
                if let img = UIImage(data: data) {
                    await MainActor.run { self.image = img }
                } else {
                    await MainActor.run { self.failed = true }
                }
            } catch {
                await MainActor.run { self.failed = true }
            }
        }
    }
}

// MARK: - Full Screen Viewer

struct FullScreenImageView: View {
    let image: UIImage
    @Environment(\.dismiss) var dismiss

    @State private var scale: CGFloat = 1.0
    @State private var lastScale: CGFloat = 1.0
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero
    @State private var showToast = false

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .scaleEffect(scale)
                    .offset(offset)
                    .gesture(
                        MagnificationGesture()
                            .onChanged { value in
                                let delta = value / lastScale
                                lastScale = value
                                scale = min(max(scale * delta, 1), 4)
                            }
                            .onEnded { _ in
                                lastScale = 1.0
                                if scale < 1 {
                                    withAnimation { scale = 1; offset = .zero }
                                }
                            }
                    )
                    .simultaneousGesture(
                        DragGesture()
                            .onChanged { value in
                                if scale > 1 {
                                    offset = CGSize(
                                        width: lastOffset.width + value.translation.width,
                                        height: lastOffset.height + value.translation.height
                                    )
                                } else if value.translation.height > 50 {
                                    dismiss() // Swipe down to dismiss when not zoomed
                                }
                            }
                            .onEnded { _ in
                                lastOffset = offset
                                if scale == 1 {
                                    withAnimation { offset = .zero; lastOffset = .zero }
                                }
                            }
                    )
                    .onTapGesture(count: 2) {
                        withAnimation {
                            if scale > 1 {
                                scale = 1
                                offset = .zero
                                lastOffset = .zero
                            } else {
                                scale = 2
                            }
                        }
                    }

                if showToast {
                    VStack {
                        Spacer()
                        Text("Saved to Photos")
                            .font(.callout.weight(.medium))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 20)
                            .padding(.vertical, 10)
                            .background(Capsule().fill(Color.black.opacity(0.7)))
                            .padding(.bottom, 40)
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(.black.opacity(0.6), for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Done") { dismiss() }
                        .fontWeight(.semibold)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        UIImageWriteToSavedPhotosAlbum(image, nil, nil, nil)
                        UINotificationFeedbackGenerator().notificationOccurred(.success)
                        withAnimation { showToast = true }
                        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                            withAnimation { showToast = false }
                        }
                    } label: {
                        Image(systemName: "arrow.down.to.line")
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    ShareLink(item: Image(uiImage: image), preview: SharePreview("Homebox Photo", image: Image(uiImage: image))) {
                        Image(systemName: "square.and.arrow.up")
                    }
                }
            }
        }
    }
}

// MARK: - Maintenance row

private struct MaintenanceRow: View {
    let entry: HBMaintenanceEntry

    private var isCompleted: Bool { !(entry.completedDate ?? "").isEmpty && !isEpoch(entry.completedDate) }
    private var isOverdue: Bool {
        guard !isCompleted,
              let s = entry.scheduledDate, !s.isEmpty, !isEpoch(s),
              let d = parseDate(s) else { return false }
        return d < Date()
    }
    private var isScheduled: Bool { !isCompleted && !(entry.scheduledDate ?? "").isEmpty && !isEpoch(entry.scheduledDate) }

    private var dotColor: Color {
        if isCompleted { return .green }
        if isOverdue   { return .red }
        if isScheduled { return .orange }
        return Color.secondary.opacity(0.4)
    }

    var body: some View {
        HStack(spacing: 10) {
            Circle().fill(dotColor).frame(width: 9, height: 9)

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(entry.name).font(.body.weight(.medium)).foregroundStyle(.primary)
                    let cadence = NotificationManager.shared.cadence(for: entry.id)
                    if !cadence.isOneTime {
                        Text(cadence.displayLabel)
                            .font(.caption2.weight(.medium))
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 6).padding(.vertical, 2)
                            .background(Capsule().fill(Color.secondary.opacity(0.12)))
                    }
                }
                if let d = entry.description, !d.isEmpty {
                    Text(d).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                if isCompleted, let ds = formatDate(entry.completedDate) {
                    HStack(spacing: 3) {
                        Image(systemName: "checkmark.circle.fill").font(.caption2).foregroundStyle(.green)
                        Text("Done \(ds)").font(.caption2).foregroundStyle(.secondary)
                    }
                } else if isOverdue, let ds = formatDate(entry.scheduledDate) {
                    HStack(spacing: 3) {
                        Image(systemName: "exclamationmark.circle.fill").font(.caption2).foregroundStyle(.red)
                        Text("Overdue · \(ds)").font(.caption2).foregroundStyle(.red)
                    }
                } else if isScheduled, let ds = formatDate(entry.scheduledDate) {
                    HStack(spacing: 3) {
                        Image(systemName: "calendar").font(.caption2)
                        Text(ds).font(.caption2)
                    }
                    .foregroundStyle(.orange)
                }
            }
            Spacer()
            if entry.id.hasPrefix("pending-") {
                Image(systemName: "arrow.triangle.2.circlepath")
                    .font(.caption2)
                    .foregroundStyle(.orange)
            }
            Image(systemName: "chevron.right").foregroundStyle(.tertiary).font(.caption)
        }
        .padding(.vertical, 4)
    }

    private func isEpoch(_ s: String?) -> Bool { (s ?? "").hasPrefix("0001-01-01") }

    private func parseDate(_ s: String) -> Date? {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = f.date(from: s) { return d }
        f.formatOptions = [.withInternetDateTime]
        if let d = f.date(from: s) { return d }
        // date-only "YYYY-MM-DD" format
        let df = DateFormatter()
        df.dateFormat = "yyyy-MM-dd"
        df.timeZone = TimeZone(identifier: "UTC")
        return df.date(from: s)
    }

    private func formatDate(_ s: String?) -> String? {
        guard let s, !s.isEmpty, !isEpoch(s), let d = parseDate(s) else { return nil }
        return d.formatted(date: .abbreviated, time: .omitted)
    }
}

// MARK: - Maintenance entry create/edit sheet

struct MaintenanceEntrySheet: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager
    @Environment(\.dismiss) var dismiss

    let itemId: String
    let itemName: String
    let existing: HBMaintenanceEntry?

    @State private var name: String
    @State private var description: String
    @State private var scheduledDate: Date
    @State private var cadence: MaintenanceCadence
    @State private var isSaving = false
    @State private var costStr: String
    @State private var errorMsg: String?
    @FocusState private var nameFocused: Bool
    @FocusState private var descFocused: Bool

    fileprivate static func dateOnly(_ date: Date) -> String {
        let c = Calendar.current.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year!, c.month!, c.day!)
    }

    init(itemId: String, itemName: String, existing: HBMaintenanceEntry?) {
        self.itemId   = itemId
        self.itemName = itemName
        self.existing = existing
        _name        = State(initialValue: existing?.name ?? "")
        _description = State(initialValue: existing?.description ?? "")

        let parseDate: (String?) -> Date? = { s in
            guard let s, !s.isEmpty, !s.hasPrefix("0001-01-01") else { return nil }
            let f = ISO8601DateFormatter()
            f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            if let d = f.date(from: s) { return d }
            f.formatOptions = [.withInternetDateTime]
            return f.date(from: s)
        }
        let sd = parseDate(existing?.scheduledDate)
        _scheduledDate = State(initialValue: sd ?? Calendar.current.date(byAdding: .month, value: 1, to: Date()) ?? Date())
        _cadence = State(initialValue: existing.map { NotificationManager.shared.cadence(for: $0.id) } ?? .oneTime)
        let existingCost = existing?.cost ?? 0
        _costStr = State(initialValue: existingCost > 0 ? {
            let v = existingCost.truncatingRemainder(dividingBy: 1) == 0 ? "\(Int(existingCost))" : "\(existingCost)"
            return v
        }() : "")
    }

    var body: some View {
        VStack(spacing: 0) {
            // Grabber
            Capsule()
                .fill(Color.secondary.opacity(0.5))
                .frame(width: 36, height: 5)
                .padding(.top, 8).padding(.bottom, 4)

            // Header
            HStack {
                Button { dismiss() } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.title3).foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                Spacer()
                Text(existing == nil ? "Schedule Maintenance" : "Edit Maintenance")
                    .font(.headline)
                Spacer()
                Button { Task { await save() } } label: {
                    if isSaving { ProgressView().controlSize(.small) }
                    else {
                        Text("Save").fontWeight(.semibold)
                            .foregroundStyle(canSave ? theme.current.accentColor : Color.secondary)
                    }
                }
                .buttonStyle(.plain)
                .disabled(!canSave || isSaving)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 10)

            Divider().opacity(0.3)

            ScrollView(.vertical) {
                VStack(alignment: .leading, spacing: 20) {

                    // Name
                    fieldSection(label: "NAME") {
                        HStack(spacing: 12) {
                            Image(systemName: "wrench.and.screwdriver")
                                .foregroundStyle(theme.current.accentColor)
                                .frame(width: 22)
                            TextField("e.g. Oil change, Filter check", text: $name)
                                .focused($nameFocused)
                                .textInputAutocapitalization(.sentences)
                        }
                        .padding(.horizontal, 14).frame(height: 50)
                        .glassEffect(in: RoundedRectangle(cornerRadius: 14))
                    }

                    // Description
                    fieldSection(label: "DESCRIPTION (OPTIONAL)") {
                        HStack(spacing: 12) {
                            Image(systemName: "text.alignleft")
                                .foregroundStyle(theme.current.accentColor)
                                .frame(width: 22)
                            TextField("Additional notes…", text: $description)
                                .focused($descFocused)
                                .textInputAutocapitalization(.sentences)
                                .font(.callout)
                        }
                        .padding(.horizontal, 14).frame(height: 50)
                        .glassEffect(in: RoundedRectangle(cornerRadius: 14))
                    }

                    // Cost
                    fieldSection(label: "COST (OPTIONAL)") {
                        HStack(spacing: 12) {
                            Image(systemName: "dollarsign")
                                .foregroundStyle(theme.current.accentColor)
                                .frame(width: 22)
                            TextField("0", text: $costStr)
                                .keyboardType(.decimalPad)
                                .font(.body)
                        }
                        .padding(.horizontal, 14).frame(height: 50)
                        .glassEffect(in: RoundedRectangle(cornerRadius: 14))
                    }

                    // Date picker (compact row, no scroll needed)
                    fieldSection(label: "SCHEDULED DATE") {
                        HStack(spacing: 12) {
                            Image(systemName: "calendar")
                                .foregroundStyle(theme.current.accentColor)
                                .frame(width: 22)
                            DatePicker("", selection: $scheduledDate, displayedComponents: .date)
                                .labelsHidden()
                                .datePickerStyle(.compact)
                                .tint(theme.current.accentColor)
                            Spacer()
                        }
                        .padding(.horizontal, 14).frame(height: 50)
                        .glassEffect(in: RoundedRectangle(cornerRadius: 14))
                    }

                    // Repeating schedule
                    fieldSection(label: "REPEATS") {
                        VStack(spacing: 10) {
                            HStack {
                                Text("Repeating schedule").font(.callout)
                                Spacer()
                                Toggle("", isOn: Binding(
                                    get: { !cadence.isOneTime },
                                    set: { on in
                                        withAnimation(.spring(response: 0.3)) {
                                            cadence = on ? MaintenanceCadence(value: cadence.isOneTime ? 1 : cadence.value, unit: cadence.isOneTime ? .month : cadence.unit) : .oneTime
                                        }
                                    }
                                ))
                                .labelsHidden()
                                .tint(theme.current.accentColor)
                            }
                            .padding(.horizontal, 14).padding(.vertical, 10)
                            .glassEffect(in: RoundedRectangle(cornerRadius: 14))

                            if !cadence.isOneTime {
                                HStack(spacing: 12) {
                                    Text("Every").font(.callout)
                                    Spacer()
                                    QuantityControl(value: $cadence.value, range: 1...99)
                                        .environmentObject(theme)
                                    Picker("", selection: $cadence.unit) {
                                        ForEach(CadenceUnit.allCases) { unit in
                                            Text(unit.label(count: cadence.value)).tag(unit)
                                        }
                                    }
                                    .labelsHidden()
                                    .pickerStyle(.menu)
                                    .tint(theme.current.accentColor)
                                    .fixedSize()
                                }
                                .padding(.horizontal, 14).padding(.vertical, 10)
                                .glassEffect(in: RoundedRectangle(cornerRadius: 14))
                                .transition(.move(edge: .top).combined(with: .opacity))
                            }
                        }
                    }

                    if let errorMsg {
                        HStack(alignment: .top, spacing: 6) {
                            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.red)
                            Text(errorMsg)
                                .font(.callout).foregroundStyle(.red)
                                .textSelection(.enabled)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }

                    // Save button
                    Button { Task { await save() } } label: {
                        HStack(spacing: 8) {
                            if isSaving { ProgressView().controlSize(.small) }
                            else {
                                Image(systemName: "calendar.badge.checkmark")
                                Text(existing == nil ? "Schedule" : "Update")
                            }
                        }
                        .font(.body.weight(.semibold))
                        .frame(maxWidth: .infinity).frame(height: 50)
                    }
                    .buttonStyle(.glassProminent)
                    .disabled(!canSave || isSaving)
                    .padding(.bottom, 8)
                }
                .padding(.horizontal, 20)
                .padding(.top, 16)
                .padding(.bottom, 24)
            }
            .scrollBounceBehavior(.basedOnSize)
            .scrollIndicators(.hidden)
        }
    }

    @ViewBuilder
    private func fieldSection<Content: View>(label: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(label)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
                .tracking(0.5)
            content()
        }
    }

    private var canSave: Bool { !name.trimmingCharacters(in: .whitespaces).isEmpty }

    private func save() async {
        guard !store.isOffline else {
            let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
            let trimmedDesc = description.trimmingCharacters(in: .whitespacesAndNewlines)
            let cost = Double(costStr.replacingOccurrences(of: ",", with: ".")) ?? 0
            let op = PendingMaintenanceOp(
                id: UUID().uuidString,
                itemId: itemId,
                entryId: existing?.id,
                name: trimmedName,
                description: trimmedDesc,
                completedDate: existing.flatMap { e -> String? in
                    let d = e.completedDate ?? ""
                    return (d.isEmpty || d.hasPrefix("0001-01-01")) ? nil : d
                },
                scheduledDate: Self.dateOnly(scheduledDate),
                cost: cost,
                localCreatedAt: Date()
            )
            store.localDB.enqueueMaintenance(op)
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            dismiss()
            return
        }
        guard let client = store.client else { return }
        isSaving = true; errorMsg = nil

        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedDesc = description.trimmingCharacters(in: .whitespacesAndNewlines)
        let schedStr = Self.dateOnly(scheduledDate)
        let completedStr: String? = existing.flatMap { e -> String? in
            let d = e.completedDate ?? ""
            return (d.isEmpty || d.hasPrefix("0001-01-01")) ? nil : d
        }
        let entry = HBMaintenanceCreate(
            name: trimmedName,
            description: trimmedDesc,
            completedDate: completedStr,
            scheduledDate: schedStr,
            cost: Double(costStr.replacingOccurrences(of: ",", with: ".")) ?? 0
        )

        do {
            let savedEntry: HBMaintenanceEntry
            if let existing {
                try await client.updateMaintenance(id: existing.id, entry: entry)
                // Cancel old notification before rescheduling
                NotificationManager.shared.cancel(entryId: existing.id)
                savedEntry = existing
            } else {
                savedEntry = try await client.createMaintenance(itemId: itemId, entry: entry)
            }

            // Schedule notification if date is in the future
            if scheduledDate > Date() {
                let granted = await NotificationManager.shared.requestPermission()
                if granted {
                    NotificationManager.shared.schedule(
                        entryId: savedEntry.id,
                        entryName: trimmedName,
                        entryDescription: trimmedDesc,
                        itemId: itemId,
                        itemName: itemName,
                        date: scheduledDate,
                        cadence: cadence
                    )
                }
            } else {
                NotificationManager.shared.saveCadence(cadence, for: savedEntry.id)
            }

            UINotificationFeedbackGenerator().notificationOccurred(.success)
            dismiss()
        } catch {
            errorMsg = error.localizedDescription
            UINotificationFeedbackGenerator().notificationOccurred(.error)
        }
        isSaving = false
    }
}
