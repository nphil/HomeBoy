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
    @State private var isLoading = false
    @State private var loadError: String?
    @State private var showEdit = false
    @State private var confirmDelete = false
    @State private var isDeleting = false

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
        .alert("Delete item?", isPresented: $confirmDelete) {
            Button("Cancel", role: .cancel) {}
            Button("Delete", role: .destructive) { Task { await performDelete() } }
        } message: {
            Text("This removes the item from Homebox permanently.")
        }
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
                }
                .padding(16)
                .padding(.bottom, 60)
            }
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
                    Text("× \(item.quantityInt)").font(.title3.monospacedDigit().weight(.medium))
                        .foregroundStyle(.secondary)
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
        guard let client = store.client else { return }
        isLoading = true; loadError = nil
        do { item = try await client.getItem(id: itemId) }
        catch { loadError = error.localizedDescription }
        isLoading = false
    }

    private func performDelete() async {
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

    var body: some View {
        NavigationStack {
            Form {
                Section("Item") {
                    TextField("Name", text: $name).textInputAutocapitalization(.sentences)
                    Stepper("Quantity: \(quantity)", value: $quantity, in: 1...9999)
                    TextField("Description", text: $description, axis: .vertical).lineLimit(1...4)
                }

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

                Section("Identification") {
                    TextField("Manufacturer", text: $manufacturer).textInputAutocapitalization(.words)
                    TextField("Model number", text: $model).textInputAutocapitalization(.never).autocorrectionDisabled()
                    TextField("Serial number", text: $serial).textInputAutocapitalization(.never).autocorrectionDisabled()
                }

                Section("Notes") {
                    TextField("Notes", text: $notes, axis: .vertical).lineLimit(1...6)
                }

                Section("Add photo") {
                    if let photo {
                        HStack {
                            Image(uiImage: photo).resizable().scaledToFill()
                                .frame(width: 60, height: 60)
                                .clipShape(RoundedRectangle(cornerRadius: 10))
                            Text("New photo will be uploaded on save")
                                .font(.caption).foregroundStyle(.secondary)
                            Spacer()
                            Button(role: .destructive) { self.photo = nil; pickerItem = nil } label: { Image(systemName: "xmark.circle.fill") }
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

                if let errorMsg {
                    Section { Label(errorMsg, systemImage: "exclamationmark.triangle.fill").foregroundStyle(.red).font(.callout) }
                }
            }
            .scrollContentBackground(.hidden)
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

    private func save() async {
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
            if let photo, let data = photo.jpegData(compressionQuality: 0.82) {
                let filename = "photo-\(Int(Date().timeIntervalSince1970)).jpg"
                let setPrimary = (original.attachments ?? []).isEmpty
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

    @State private var image: UIImage?
    @State private var failed = false

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
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
