import SwiftUI
import UIKit
import PhotosUI

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
    @State private var photo: UIImage?
    @State private var pickerItem: PhotosPickerItem?

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

    var body: some View {
        NavigationStack {
            ZStack {
                theme.current.backgroundColor.ignoresSafeArea()

                if !store.isAuthenticated {
                    notConfiguredView
                } else {
                    addForm
                }
            }
            .navigationTitle("")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) { BrandMark() }
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button("Done") { nameFocused = false; descFocused = false }
                        .font(.callout.weight(.semibold))
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
            .onAppear {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { nameFocused = true }
            }
        }
    }

    // MARK: - Main form

    private var addForm: some View {
        VStack(alignment: .leading, spacing: 12) {
            nameField
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

                addButton.padding(.top, 4)
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

            VStack(spacing: 4) {
                Text("PHOTO").font(.caption2.weight(.semibold)).tracking(0.4)
                    .foregroundStyle(theme.current.accentColor.opacity(0.75))
                if let photo {
                    ZStack(alignment: .topTrailing) {
                        Image(uiImage: photo).resizable().scaledToFill()
                            .frame(width: 44, height: 34).clipShape(RoundedRectangle(cornerRadius: 6))
                        Button {
                            self.photo = nil; pickerItem = nil
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .font(.caption).foregroundStyle(.white)
                                .background(Circle().fill(Color.black.opacity(0.4)).padding(-1))
                        }
                        .buttonStyle(.plain)
                        .offset(x: 4, y: -4)
                    }
                } else {
                    HStack(spacing: 6) {
                        if UIImagePickerController.isSourceTypeAvailable(.camera) {
                            Button { showCamera = true } label: {
                                Image(systemName: "camera.fill").font(.callout)
                                    .foregroundStyle(theme.current.accentColor)
                                    .frame(width: 36, height: 34)
                                    .background(RoundedRectangle(cornerRadius: 8).fill(.ultraThinMaterial))
                            }.buttonStyle(.plain)
                        }
                        PhotosPicker(selection: $pickerItem, matching: .images, photoLibrary: .shared()) {
                            Image(systemName: "photo.on.rectangle").font(.callout)
                                .foregroundStyle(theme.current.accentColor)
                                .frame(width: 36, height: 34)
                                .background(RoundedRectangle(cornerRadius: 8).fill(.ultraThinMaterial))
                        }.buttonStyle(.plain)
                    }
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

    private var addButton: some View {
        Button { submit() } label: {
            HStack {
                if isSubmitting { ProgressView().controlSize(.small) }
                Label(isSubmitting ? "Adding…" : "Add to Homebox", systemImage: "plus.circle.fill")
                    .font(.title3.weight(.semibold))
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 6)
        }
        .buttonStyle(.glassProminent)
        .disabled(isSubmitting || !canSubmit)
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

    private func submit() {
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedName.isEmpty, let client = store.client, let locId = selectedLocationId else { return }
        submitError = nil; isSubmitting = true
        let payload = HBItemCreate(
            name: trimmedName, quantity: Double(quantity), description: description,
            locationId: locId, parentId: nil, tagIds: Array(selectedTagIds)
        )
        let photoToUpload = photo
        Task {
            do {
                let newId = try await client.createItem(payload)
                if let photoToUpload, let data = photoToUpload.jpegData(compressionQuality: 0.82) {
                    let filename = "photo-\(Int(Date().timeIntervalSince1970)).jpg"
                    try await client.uploadAttachment(itemId: newId, fileData: data, filename: filename, primary: true)
                }
                await MainActor.run {
                    UINotificationFeedbackGenerator().notificationOccurred(.success)
                    showSuccessPill("\"\(trimmedName)\"")
                    resetForm()
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

    private func resetForm() {
        name = ""; quantity = 1; description = ""
        photo = nil; pickerItem = nil
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
