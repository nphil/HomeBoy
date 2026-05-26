import SwiftUI
import UIKit
import PhotosUI
import FoundationModels

struct AddItemView: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager

    // Set when opening as a sub-item (from ItemDetailView "Add component")
    var parentId: String? = nil
    var parentName: String? = nil
    var parentLocationId: String? = nil
    var onDismiss: () -> Void = {}

    private var isComponent: Bool { parentId != nil }

    // Fields
    @State private var name = ""
    @State private var quantity = 1
    @State private var description = ""
    @State private var selectedLocationId: String?
    @State private var lockLocation = false
    @State private var lockTags = false
    @State private var selectedTagIds: Set<String> = []
    @State private var photos: [UIImage] = []
    @State private var pickerItems: [PhotosPickerItem] = []

    // Tag suggestions
    @State private var availableTags: [HBTag] = []
    @State private var suggestedTagIds: [String] = []
    @State private var isSuggestingTags = false
    @State private var suggestionTask: Task<Void, Never>? = nil

    // Sheet flags
    @State private var showLocationPicker = false
    @State private var showTagPicker = false
    @State private var showCamera = false
    @State private var showPhotoOptions = false
    @State private var showPhotoPicker = false
    @State private var showBarcodeScanner = false
    @State private var showProductMatch = false
    @State private var pendingProducts: [HBBarcodeProduct] = []

    // Submission
    @State private var isSubmitting = false
    @State private var justAdded: String? = nil
    @State private var submitError: String?

    @FocusState private var nameFocused: Bool
    @FocusState private var notesFocused: Bool
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            if !store.isAuthenticated {
                notConfiguredView
                    .padding(24)
            } else {
                VStack(spacing: 0) {
                    // Grabber indicator
                    Capsule()
                        .fill(Color.secondary.opacity(0.5))
                        .frame(width: 36, height: 5)
                        .padding(.top, 8)
                        .padding(.bottom, 4)

                    VStack(spacing: 0) {
                        // Header
                        HStack {
                            HStack(spacing: 8) {
                                Image(systemName: parentName != nil ? "plus.rectangle.on.folder" : "plus.square")
                                    .foregroundStyle(theme.current.accentColor)
                                    .font(.headline)
                                Text(parentName != nil ? "New Component" : "New Item")
                                    .font(.headline.weight(.semibold))
                            }
                            Spacer()
                            Button {
                                onDismiss()
                            } label: {
                                Image(systemName: "xmark.circle.fill")
                                    .font(.title3)
                                    .foregroundStyle(.secondary)
                            }
                            .buttonStyle(.plain)
                        }
                        .padding(.horizontal, 20)
                        .padding(.top, 14)
                        .padding(.bottom, 12)

                        VStack(spacing: 0) {
                            addForm
                                .padding(.horizontal, 20)
                        }

                        actionButtons
                            .padding(.horizontal, 20)
                            .padding(.top, 12)
                            .padding(.bottom, 16)
                    }
                }
            }
        }
        .sheet(isPresented: $showLocationPicker) {
            LocationPickerSheet(selectedId: $selectedLocationId)
                .environmentObject(store).environmentObject(theme)
        }
        .sheet(isPresented: $showTagPicker) {
            TagPickerSheet(selectedIds: $selectedTagIds)
                .environmentObject(store).environmentObject(theme)
        }
        .sheet(isPresented: $showCamera) {
            CameraSheet { img in photos.append(downscale(img)) }.ignoresSafeArea()
        }
        .photosPicker(isPresented: $showPhotoPicker, selection: $pickerItems, matching: .images, photoLibrary: .shared())
        .sheet(isPresented: $showBarcodeScanner) {
            BarcodeScannerSheet(mode: .barcode) { code in
                showBarcodeScanner = false
                Task { await lookupBarcode(code) }
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
        .onChange(of: pickerItems) { _, newItems in
            guard !newItems.isEmpty else { return }
            Task {
                var loaded: [UIImage] = []
                for item in newItems {
                    if let data = try? await item.loadTransferable(type: Data.self),
                       let img = UIImage(data: data) {
                        loaded.append(downscale(img))
                    }
                }
                await MainActor.run {
                    photos.append(contentsOf: loaded)
                    pickerItems = []
                }
            }
        }
        .task { await loadTags() }
        .onChange(of: name) { _, newName in scheduleSuggestion(for: newName) }
        .onAppear {
            if isComponent, selectedLocationId == nil {
                selectedLocationId = parentLocationId
            }
        }
    }

    // MARK: - Main form

    @ViewBuilder private var parentRow: some View {
        if let parentName {
            HStack(spacing: 8) {
                Image(systemName: "arrow.up.square")
                    .font(.body)
                    .foregroundStyle(theme.current.accentColor)
                Text("Component of")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(theme.current.accentColor.opacity(0.85))
                Text(parentName)
                    .font(.callout.weight(.medium)).foregroundStyle(.primary).lineLimit(1)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12).padding(.vertical, 6)
            .frame(maxWidth: .infinity)
            .background(Capsule().fill(theme.current.accentColor.opacity(0.10)))
            .overlay(Capsule().stroke(theme.current.accentColor.opacity(0.25), lineWidth: 1))
        }
    }

    private var addForm: some View {
        VStack(alignment: .leading, spacing: 14) {
            parentRow
            heroNameField
            detailsCard
            tagSuggestionRow
            photosSection
            notesSection
                .frame(maxHeight: .infinity)

            if let submitError { errorPill(submitError) }
            if let justAdded   { successPill(justAdded) }
        }
        .padding(.horizontal, 0)
        .padding(.top, 4)
        .padding(.bottom, 4)
    }

    // MARK: - Subviews

    // MARK: Hero name field

    private var heroNameField: some View {
        HStack(spacing: 12) {
            TextField("Item name", text: $name)
                .font(.title3.weight(.semibold))
                .textInputAutocapitalization(.sentences)
                .focused($nameFocused)
                .submitLabel(.done)
            Button { showBarcodeScanner = true } label: {
                Image(systemName: "barcode.viewfinder")
                    .font(.title3)
                    .foregroundStyle(theme.current.accentColor)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 18)
        .frame(height: 56)
        .glassEffect(in: RoundedRectangle(cornerRadius: 16))
    }

    // MARK: Grouped details card (Location, Tags, Quantity)

    private var detailsCard: some View {
        VStack(spacing: 0) {
            if !isComponent {
                locationDetailRow
                rowDivider
            }
            tagsDetailRow
            rowDivider
            quantityDetailRow
        }
        .glassEffect(in: RoundedRectangle(cornerRadius: 16))
    }

    private var rowDivider: some View {
        Divider().padding(.leading, 50)
    }

    private var locationDetailRow: some View {
        Button { showLocationPicker = true } label: {
            HStack(spacing: 12) {
                rowIcon(selectedLocationId == nil ? "mappin.circle" : "mappin.and.ellipse")
                Text("Location")
                    .font(.callout)
                    .foregroundStyle(.primary)
                Spacer(minLength: 8)
                Group {
                    if let id = selectedLocationId {
                        Text(store.pathString(forLocationId: id))
                            .lineLimit(1)
                            .truncationMode(.middle)
                            .foregroundStyle(.secondary)
                    } else {
                        Text("Required")
                            .foregroundStyle(theme.current.accentColor.opacity(0.85))
                    }
                }
                .font(.callout)
                inlinePinButton(isOn: $lockLocation)
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .padding(.horizontal, 14)
            .frame(height: 50)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private var tagsDetailRow: some View {
        Button { showTagPicker = true } label: {
            HStack(spacing: 12) {
                rowIcon("tag.fill")
                Text("Tags")
                    .font(.callout)
                    .foregroundStyle(.primary)
                Spacer(minLength: 8)
                Text(selectedTagIds.isEmpty ? "None" : "\(selectedTagIds.count) selected")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                inlinePinButton(isOn: $lockTags)
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .padding(.horizontal, 14)
            .frame(height: 50)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private var quantityDetailRow: some View {
        HStack(spacing: 12) {
            rowIcon("number.square")
            Text("Quantity")
                .font(.callout)
                .foregroundStyle(.primary)
            Spacer(minLength: 8)
            HStack(spacing: 14) {
                Button {
                    if quantity > 1 {
                        quantity -= 1
                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    }
                } label: {
                    Image(systemName: "minus.circle.fill")
                        .font(.title3)
                        .foregroundStyle(quantity > 1 ? theme.current.accentColor : .tertiary)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(quantity <= 1)

                Text("\(quantity)")
                    .font(.body.monospacedDigit().weight(.semibold))
                    .frame(minWidth: 22)
                    .contentTransition(.numericText())

                Button {
                    quantity += 1
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                } label: {
                    Image(systemName: "plus.circle.fill")
                        .font(.title3)
                        .foregroundStyle(theme.current.accentColor)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 14)
        .frame(height: 50)
    }

    private func rowIcon(_ name: String) -> some View {
        Image(systemName: name)
            .font(.body)
            .foregroundStyle(theme.current.accentColor)
            .frame(width: 24, alignment: .center)
    }

    private func inlinePinButton(isOn: Binding<Bool>) -> some View {
        Button {
            isOn.wrappedValue.toggle()
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        } label: {
            Image(systemName: isOn.wrappedValue ? "pin.fill" : "pin")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(isOn.wrappedValue ? theme.current.accentColor : .secondary)
                .frame(width: 28, height: 28)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // MARK: Photos & Notes sections

    private var photosSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            sectionHeader("Photos")
            photosTile
        }
    }

    private var notesSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            sectionHeader("Notes")
            ZStack(alignment: .topLeading) {
                TextEditor(text: $description)
                    .font(.callout)
                    .focused($notesFocused)
                    .scrollContentBackground(.hidden)
                    .scrollIndicators(.hidden)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                if description.isEmpty && !notesFocused {
                    Text("Optional details…")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 15)
                        .padding(.vertical, 14)
                        .allowsHitTesting(false)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .glassEffect(in: RoundedRectangle(cornerRadius: 14))
        }
    }

    private func sectionHeader(_ text: String) -> some View {
        Text(text.uppercased())
            .font(.caption2.weight(.semibold))
            .foregroundStyle(.secondary)
            .padding(.horizontal, 4)
    }

    @ViewBuilder private var photosTile: some View {
        let accentColor = theme.current.accentColor
        HStack(spacing: 6) {
            // Always-visible add button — shows camera/library action sheet
            Button { showPhotoOptions = true } label: {
                Image(systemName: "camera.fill")
                    .font(.body)
                    .foregroundStyle(accentColor)
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(.glass)
            .confirmationDialog("", isPresented: $showPhotoOptions, titleVisibility: .hidden) {
                if UIImagePickerController.isSourceTypeAvailable(.camera) {
                    Button("Take Photo") { showCamera = true }
                }
                Button("Choose from Library") { showPhotoPicker = true }
                Button("Cancel", role: .cancel) {}
            }

            // Scrollable photo strip — shown only when photos exist
            if !photos.isEmpty {
                ScrollView(.horizontal) {
                    HStack(spacing: 4) {
                        ForEach(Array(photos.enumerated()), id: \.offset) { idx, photo in
                            ZStack(alignment: .topTrailing) {
                                Image(uiImage: photo).resizable().scaledToFill()
                                    .frame(width: 44, height: 44)
                                    .clipShape(RoundedRectangle(cornerRadius: 10))
                                Button {
                                    photos.remove(at: idx)
                                } label: {
                                    Image(systemName: "xmark.circle.fill")
                                        .font(.caption2).foregroundStyle(.white)
                                        .background(Circle().fill(Color.black.opacity(0.4)).padding(-2))
                                }
                                .buttonStyle(.plain)
                                .offset(x: 4, y: -4)
                            }
                        }
                    }
                    .padding(.vertical, 4)
                }
                .scrollIndicators(.hidden)
            }
        }
    }

    private var actionButtons: some View {
        HStack(spacing: 12) {
            // Add Another button
            Button {
                submit(andDismiss: false)
            } label: {
                HStack {
                    if isSubmitting {
                        ProgressView().controlSize(.small)
                    } else {
                        Image(systemName: "plus.square.on.square")
                            .font(.body.weight(.semibold))
                    }
                    Text("Add Another")
                        .font(.body.weight(.semibold))
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
            }
            .buttonStyle(.glass)
            .disabled(isSubmitting || !canSubmit)

            // Add button
            Button {
                submit(andDismiss: true)
            } label: {
                HStack {
                    if isSubmitting {
                        ProgressView().controlSize(.small)
                    } else {
                        Image(systemName: "plus.square.fill")
                            .font(.body.weight(.semibold))
                    }
                    Text("Add")
                        .font(.body.weight(.semibold))
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
            }
            .buttonStyle(.glassProminent)
            .disabled(isSubmitting || !canSubmit)
        }
    }

    private var canSubmit: Bool {
        !name.trimmingCharacters(in: .whitespaces).isEmpty && selectedLocationId != nil
    }

    // MARK: - Feedback pills

    @ViewBuilder
    private func successPill(_ text: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "checkmark.circle.fill")
            Text("Added \(text)").font(.callout)
        }
        .padding(.horizontal, 14).padding(.vertical, 8)
        .background(Capsule().fill(.ultraThinMaterial))
        .overlay(Capsule().stroke(Color.green.opacity(0.5), lineWidth: 1))
        .foregroundStyle(.primary)
        .transition(.opacity.combined(with: .scale))
        .frame(maxWidth: .infinity, alignment: .center)
    }

    @ViewBuilder
    private func errorPill(_ text: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "exclamationmark.triangle.fill")
            Text(text).font(.callout).multilineTextAlignment(.leading)
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 12).fill(.ultraThinMaterial))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.red.opacity(0.5), lineWidth: 1))
        .foregroundStyle(.primary)
    }

    // MARK: - Not configured

    private var notConfiguredView: some View {
        VStack(spacing: 16) {
            Image(systemName: "link.circle").font(.system(size: 52)).foregroundStyle(.secondary)
            Text("Connect to Homebox").font(.title3.weight(.semibold))
            Text("Open Settings to enter your server URL and sign in.")
                .font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center).padding(.horizontal, 32)
        }
    }

    // MARK: - Submit

    private func submit(andDismiss: Bool) {
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedName.isEmpty, selectedLocationId != nil, let client = store.client else { return }
        submitError = nil; isSubmitting = true
        let payload = HBItemCreate(
            name: trimmedName, quantity: Double(quantity), description: description,
            locationId: selectedLocationId, parentId: parentId, tagIds: Array(selectedTagIds)
        )
        let photosToUpload = photos
        Task {
            do {
                let newId = try await client.createItem(payload)
                if !photosToUpload.isEmpty {
                    await withTaskGroup(of: Void.self) { group in
                        for (index, photo) in photosToUpload.enumerated() {
                            group.addTask {
                                if let data = photo.jpegData(compressionQuality: 0.82) {
                                    let filename = "photo-\(Int(Date().timeIntervalSince1970))-\(index).jpg"
                                    try? await client.uploadAttachment(itemId: newId, fileData: data, filename: filename, primary: index == 0)
                                }
                            }
                        }
                    }
                }
                await MainActor.run {
                    UINotificationFeedbackGenerator().notificationOccurred(.success)
                    showSuccessPill("\"\(trimmedName)\"")
                    resetForm()
                    if andDismiss {
                        onDismiss()
                    }
                }
            } catch {
                await MainActor.run {
                    submitError = error.localizedDescription
                    UINotificationFeedbackGenerator().notificationOccurred(.error)
                }
            }
            await MainActor.run { isSubmitting = false }
        }
    }

    // MARK: - Tag suggestions

    @ViewBuilder
    private var tagSuggestionRow: some View {
        let chips = availableTags.filter { suggestedTagIds.contains($0.id) && !selectedTagIds.contains($0.id) }
        if isSuggestingTags || !chips.isEmpty {
            HStack(spacing: 8) {
                Image(systemName: "sparkles")
                    .font(.caption2).foregroundStyle(theme.current.accentColor)
                if isSuggestingTags {
                    ProgressView().controlSize(.mini)
                    Text("Suggesting tags…").font(.caption).foregroundStyle(.secondary)
                } else {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            ForEach(chips) { tag in
                                Button {
                                    selectedTagIds.insert(tag.id)
                                    suggestedTagIds.removeAll { $0 == tag.id }
                                } label: {
                                    HStack(spacing: 4) {
                                        Circle().fill(Color(hex: tag.color ?? "")).frame(width: 8, height: 8)
                                        Text(tag.name).font(.caption.weight(.medium))
                                    }
                                    .padding(.horizontal, 10).padding(.vertical, 5)
                                    .background(Capsule().fill(.ultraThinMaterial))
                                    .overlay(Capsule().stroke(theme.current.accentColor.opacity(0.35), lineWidth: 1))
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, 14).padding(.vertical, 8)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background {
                RoundedRectangle(cornerRadius: 12).fill(.ultraThinMaterial)
                RoundedRectangle(cornerRadius: 12).fill(theme.current.accentColor.opacity(0.05))
            }
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(theme.current.accentColor.opacity(0.18), lineWidth: 1))
            .transition(.opacity.combined(with: .scale(scale: 0.97)))
        }
    }

    private func loadTags() async {
        guard let client = store.client else { return }
        if let tags = try? await client.listTags() { availableTags = tags }
    }

    private func scheduleSuggestion(for itemName: String) {
        suggestionTask?.cancel()
        suggestedTagIds = []
        guard itemName.count >= 4, !availableTags.isEmpty else { return }
        suggestionTask = Task {
            try? await Task.sleep(nanoseconds: 800_000_000)
            guard !Task.isCancelled else { return }
            await suggestTags(for: itemName)
        }
    }

    private func suggestTags(for itemName: String) async {
        guard SystemLanguageModel.default.isAvailable else { return }
        let tagList = availableTags.map(\.name).joined(separator: ", ")
        let prompt = "Home inventory item: '\(itemName)'. Available tags: \(tagList). List the 1–3 most relevant tag names, comma-separated. Reply with only tag names or 'none'."
        await MainActor.run { isSuggestingTags = true }
        do {
            let session = LanguageModelSession()
            let response = try await session.respond(to: prompt)
            let names = "\(response.content)"
                .split(separator: ",")
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }
                .filter { $0 != "none" }
            let matched = availableTags.filter { names.contains($0.name.lowercased()) }.map(\.id)
            await MainActor.run { suggestedTagIds = matched; isSuggestingTags = false }
        } catch {
            await MainActor.run { isSuggestingTags = false }
        }
    }

    private func resetForm() {
        name = ""; quantity = 1; description = ""
        photos = []; pickerItems = []
        suggestedTagIds = []; suggestionTask?.cancel()
        if !lockTags { selectedTagIds = [] }
        if !lockLocation && !isComponent { selectedLocationId = nil }
        if isComponent { selectedLocationId = parentLocationId }
    }

    private func lookupBarcode(_ code: String) async {
        guard let client = store.client else { return }

        // 1. Try Homebox's own product database first.
        var products = (try? await client.searchFromBarcode(data: code)) ?? []

        // 2. Fall back to Open Food Facts (food/grocery/household).
        if products.isEmpty, let p = await HomeboxClient.lookupOpenFoodFacts(barcode: code) {
            products = [p]
        }

        // 3. Fall back to UPC Item DB (general products, 100 lookups/day free).
        if products.isEmpty, let p = await HomeboxClient.lookupUPCItemDB(barcode: code) {
            products = [p]
        }

        await MainActor.run {
            if products.isEmpty {
                NotificationCenter.default.post(name: .showToast, object: nil,
                                                userInfo: ["message": "No product found for that barcode"])
            } else {
                pendingProducts = products
                showProductMatch = true
            }
        }
    }

    private func applyProduct(_ product: HBBarcodeProduct) {
        if let n = product.item?.name, !n.isEmpty { name = n }
        if let d = product.item?.description, !d.isEmpty { description = d }
    }

    private func showSuccessPill(_ text: String) {
        withAnimation(.easeOut(duration: 0.15)) { justAdded = text }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.4) {
            withAnimation(.easeIn(duration: 0.3)) { if justAdded == text { justAdded = nil } }
        }
    }
}

