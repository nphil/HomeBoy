import SwiftUI
import UIKit
import PhotosUI
import FoundationModels

struct AddItemView: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager

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

    // Submission
    @State private var isSubmitting = false
    @State private var justAdded: String? = nil
    @State private var submitError: String?

    @FocusState private var nameFocused: Bool
    @FocusState private var descFocused: Bool
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ZStack {
                theme.current.backgroundColor.ignoresSafeArea()

                if !store.isAuthenticated {
                    notConfiguredView
                } else {
                    ScrollView {
                        addForm
                    }
                    .scrollDismissesKeyboard(.interactively)
                    .scrollIndicators(.hidden)
                    .safeAreaInset(edge: .bottom) {
                        actionButtons
                            .padding(.horizontal, 16)
                            .padding(.vertical, 8)
                            .background(Color.clear)
                    }
                }
            }
            .navigationTitle("New Item")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Cancel") { dismiss() }
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
            .onChange(of: pickerItems) { _, newItems in
                Task {
                    var loaded: [UIImage] = []
                    for item in newItems {
                        if let data = try? await item.loadTransferable(type: Data.self),
                           let img = UIImage(data: data) {
                            loaded.append(downscale(img))
                        }
                    }
                    await MainActor.run { photos = loaded }
                }
            }
            .task { await loadTags() }
            .onChange(of: name) { _, newName in scheduleSuggestion(for: newName) }
            .onAppear {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { nameFocused = true }
            }
        }
    }

    // MARK: - Main form

    private var addForm: some View {
        VStack(alignment: .leading, spacing: 12) {
            nameField
            tagSuggestionRow
            locationRow
            compactOptionals
            descriptionField

            Spacer(minLength: 0)

            if let submitError { errorPill(submitError) }
            if let justAdded   { successPill(justAdded) }

            VStack(spacing: 6) {
                Toggle(isOn: $lockLocation) {
                    Text("Keep location for next item").font(.caption).foregroundStyle(.secondary)
                }
                .toggleStyle(.switch).controlSize(.mini).tint(theme.current.accentColor)

                Toggle(isOn: $lockTags) {
                    Text("Keep tags for next item").font(.caption).foregroundStyle(.secondary)
                }
                .toggleStyle(.switch).controlSize(.mini).tint(theme.current.accentColor)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 12)
        .padding(.bottom, 24)
    }

    // MARK: - Subviews

    private var nameField: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("ITEM").font(.caption.weight(.semibold)).tracking(0.6)
                .foregroundStyle(theme.current.accentColor.opacity(0.75))
                .padding(.horizontal, 14)

            TextField("What is it?", text: $name)
                .font(.title3.weight(.semibold))
                .textInputAutocapitalization(.sentences)
                .focused($nameFocused)
                .submitLabel(.next)
                .onSubmit { descFocused = true }
                .padding(.horizontal, 14).padding(.vertical, 12)
                .background {
                    RoundedRectangle(cornerRadius: 14).fill(.ultraThinMaterial)
                    RoundedRectangle(cornerRadius: 14).fill(theme.current.accentColor.opacity(0.07))
                }
                .overlay(RoundedRectangle(cornerRadius: 14)
                    .stroke(theme.current.accentColor.opacity(name.isEmpty ? 0.35 : 0.2),
                            lineWidth: name.isEmpty ? 1.5 : 1))
        }
    }

    private var locationRow: some View {
        Button { showLocationPicker = true } label: {
            HStack(spacing: 10) {
                Image(systemName: selectedLocationId == nil ? "mappin.circle" : "mappin.and.ellipse")
                    .font(.title3)
                    .foregroundStyle(theme.current.accentColor)
                VStack(alignment: .leading, spacing: 2) {
                    Text("LOCATION").font(.caption.weight(.semibold)).tracking(0.6)
                        .foregroundStyle(theme.current.accentColor.opacity(0.75))
                    if let id = selectedLocationId {
                        Text(store.pathString(forLocationId: id))
                            .font(.body.weight(.medium)).foregroundStyle(.primary).lineLimit(1)
                    } else {
                        Text("Tap to choose — required").foregroundStyle(.secondary).font(.callout)
                    }
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").foregroundStyle(.tertiary)
            }
            .padding(.horizontal, 14).padding(.vertical, 12)
            .frame(maxWidth: .infinity)
            .background {
                RoundedRectangle(cornerRadius: 14).fill(.ultraThinMaterial)
                RoundedRectangle(cornerRadius: 14).fill(theme.current.accentColor.opacity(0.07))
            }
            .overlay(RoundedRectangle(cornerRadius: 14)
                .stroke(selectedLocationId == nil
                        ? theme.current.accentColor.opacity(0.35)
                        : theme.current.accentColor.opacity(0.2),
                        lineWidth: selectedLocationId == nil ? 1.5 : 1))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private var compactOptionals: some View {
        HStack(spacing: 10) {
            VStack(spacing: 4) {
                Text("QTY").font(.caption2.weight(.semibold)).tracking(0.4)
                    .foregroundStyle(theme.current.accentColor.opacity(0.75))
                QuantityControl(value: $quantity)
            }

            Divider().frame(height: 40)

            Button { showTagPicker = true } label: {
                VStack(spacing: 4) {
                    Text("TAGS").font(.caption2.weight(.semibold)).tracking(0.4)
                        .foregroundStyle(theme.current.accentColor.opacity(0.75))
                    HStack(spacing: 4) {
                        Image(systemName: "tag.fill").font(.caption)
                            .foregroundStyle(theme.current.accentColor)
                        Text(selectedTagIds.isEmpty ? "None" : "\(selectedTagIds.count)")
                            .font(.callout.weight(.medium))
                    }
                    .padding(.horizontal, 12).padding(.vertical, 5)
                    .background(Capsule().fill(.ultraThinMaterial))
                    .overlay(Capsule().stroke(theme.current.accentColor.opacity(0.25), lineWidth: 1))
                    .contentShape(Rectangle())
                }
            }
            .buttonStyle(.plain)

            Divider().frame(height: 40)

            VStack(spacing: 8) {
                Text("PHOTOS").font(.caption2.weight(.semibold)).tracking(0.4)
                    .foregroundStyle(theme.current.accentColor.opacity(0.75))
                
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(photos.indices, id: \.self) { idx in
                            ZStack(alignment: .topTrailing) {
                                Image(uiImage: photos[idx]).resizable().scaledToFill()
                                    .frame(width: 50, height: 50).clipShape(RoundedRectangle(cornerRadius: 8))
                                Button {
                                    photos.remove(at: idx)
                                    // Also remove from pickerItems if possible, but pickerItems might be out of sync.
                                    // It's easier to just reset pickerItems when modifying manually.
                                    pickerItems.removeAll()
                                } label: {
                                    Image(systemName: "xmark.circle.fill")
                                        .font(.caption).foregroundStyle(.white)
                                        .background(Circle().fill(Color.black.opacity(0.4)).padding(-2))
                                }
                                .buttonStyle(.plain)
                                .offset(x: 6, y: -6)
                            }
                        }
                        
                        // Add buttons
                        if UIImagePickerController.isSourceTypeAvailable(.camera) {
                            Button { showCamera = true } label: {
                                Image(systemName: "camera.fill").font(.title3)
                                    .foregroundStyle(theme.current.accentColor)
                                    .frame(width: 50, height: 50)
                                    .background(RoundedRectangle(cornerRadius: 8).fill(.ultraThinMaterial))
                            }.buttonStyle(.plain)
                        }
                        PhotosPicker(selection: $pickerItems, matching: .images, photoLibrary: .shared()) {
                            Image(systemName: "photo.on.rectangle").font(.title3)
                                .foregroundStyle(theme.current.accentColor)
                                .frame(width: 50, height: 50)
                                .background(RoundedRectangle(cornerRadius: 8).fill(.ultraThinMaterial))
                        }.buttonStyle(.plain)
                    }
                    .padding(.horizontal, 4).padding(.vertical, 4)
                }
            }
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
        .frame(maxWidth: .infinity)
        .background {
            RoundedRectangle(cornerRadius: 14).fill(.ultraThinMaterial)
            RoundedRectangle(cornerRadius: 14).fill(theme.current.accentColor.opacity(0.05))
        }
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(theme.current.accentColor.opacity(0.18), lineWidth: 1))
    }

    private var descriptionField: some View {
        TextField("Description / notes (optional)", text: $description, axis: .vertical)
            .focused($descFocused)
            .lineLimit(1...3)
            .textInputAutocapitalization(.sentences)
            .padding(.horizontal, 14).padding(.vertical, 10)
            .background {
                RoundedRectangle(cornerRadius: 14).fill(.ultraThinMaterial)
                RoundedRectangle(cornerRadius: 14).fill(theme.current.accentColor.opacity(0.05))
            }
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(theme.current.accentColor.opacity(0.18), lineWidth: 1))
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
                        Image(systemName: "checkmark.circle.fill")
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
            locationId: selectedLocationId, parentId: nil, tagIds: Array(selectedTagIds)
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
                        dismiss()
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
        if !lockLocation { selectedLocationId = nil }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { nameFocused = true }
    }

    private func showSuccessPill(_ text: String) {
        withAnimation(.easeOut(duration: 0.15)) { justAdded = text }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.4) {
            withAnimation(.easeIn(duration: 0.3)) { if justAdded == text { justAdded = nil } }
        }
    }
}
