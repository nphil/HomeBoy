import SwiftUI
import UIKit

struct AddItemView: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager

    @State private var name = ""
    @State private var quantity = 1
    @State private var description = ""
    @State private var selectedLocationId: String?
    @State private var lockLocation = false
    @State private var showLocationPicker = false
    @State private var isSubmitting = false
    @State private var justAdded: String? = nil
    @State private var submitError: String?

    enum Field: Hashable { case name, description }
    @FocusState private var focused: Field?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    if !store.isAuthenticated {
                        notConfiguredCard
                    } else {
                        nameAndQuantityCard
                        locationCard
                        descriptionCard
                        addButton
                        if let submitError {
                            errorPill(submitError)
                        }
                        if let justAdded {
                            successPill(justAdded)
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 80)
            }
            .background(theme.current.backgroundColor.ignoresSafeArea())
            .scrollDismissesKeyboard(.interactively)
            .navigationTitle("")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) { BrandMark() }
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button("Done") { focused = nil }
                        .font(.callout.weight(.semibold))
                }
            }
            .sheet(isPresented: $showLocationPicker) {
                LocationPickerSheet(selectedId: $selectedLocationId)
                    .environmentObject(store)
                    .environmentObject(theme)
            }
            .onAppear {
                if store.isAuthenticated, focused == nil {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                        focused = .name
                    }
                }
            }
        }
    }

    // MARK: - Cards

    private var notConfiguredCard: some View {
        GlassCard {
            VStack(spacing: 10) {
                Image(systemName: "link.circle")
                    .font(.system(size: 32))
                    .foregroundStyle(theme.current.accentColor)
                Text("Connect to Homebox")
                    .font(.title3.weight(.semibold))
                Text("Open the Settings tab to enter your server URL and sign in. New items will save directly to your Homebox.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
        }
    }

    private var nameAndQuantityCard: some View {
        GlassCard(title: "Item") {
            VStack(alignment: .leading, spacing: 14) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("NAME")
                        .font(.caption.weight(.semibold))
                        .tracking(0.6)
                        .foregroundStyle(theme.current.accentColor.opacity(0.75))
                    TextField("e.g. Cordless drill", text: $name)
                        .textFieldStyle(.plain)
                        .focused($focused, equals: .name)
                        .submitLabel(.next)
                        .onSubmit { focused = .description }
                        .font(.title3)
                        .textInputAutocapitalization(.sentences)
                }
                HStack(spacing: 12) {
                    Text("QUANTITY")
                        .font(.caption.weight(.semibold))
                        .tracking(0.6)
                        .foregroundStyle(theme.current.accentColor.opacity(0.75))
                    Spacer(minLength: 0)
                    QuantityControl(value: $quantity)
                }
            }
        }
    }

    private var locationCard: some View {
        GlassCard(title: "Location") {
            VStack(alignment: .leading, spacing: 10) {
                Button {
                    showLocationPicker = true
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: selectedLocationId == nil ? "mappin.circle" : "mappin.and.ellipse")
                            .font(.title3)
                            .foregroundStyle(theme.current.accentColor)
                        VStack(alignment: .leading, spacing: 2) {
                            if let id = selectedLocationId {
                                Text(store.pathString(forLocationId: id))
                                    .font(.body.weight(.medium))
                                    .foregroundStyle(.primary)
                                    .lineLimit(2)
                            } else {
                                Text("Tap to choose location")
                                    .foregroundStyle(.secondary)
                            }
                        }
                        Spacer(minLength: 0)
                        Image(systemName: "chevron.right")
                            .foregroundStyle(.tertiary)
                    }
                    .padding(.vertical, 10)
                    .padding(.horizontal, 12)
                    .frame(maxWidth: .infinity)
                    .background {
                        RoundedRectangle(cornerRadius: 12).fill(.ultraThinMaterial)
                    }
                    .overlay(
                        RoundedRectangle(cornerRadius: 12).stroke(theme.current.accentColor.opacity(0.25), lineWidth: 1)
                    )
                }
                .buttonStyle(.plain)

                HStack(spacing: 8) {
                    Toggle(isOn: $lockLocation) { Text("Keep location after adding").font(.caption) }
                        .toggleStyle(.switch)
                        .controlSize(.mini)
                        .tint(theme.current.accentColor)
                }
            }
        }
    }

    private var descriptionCard: some View {
        GlassCard(title: "Description (optional)") {
            TextField("Notes about the item", text: $description, axis: .vertical)
                .focused($focused, equals: .description)
                .lineLimit(1...4)
                .textInputAutocapitalization(.sentences)
        }
    }

    private var addButton: some View {
        Button {
            submit()
        } label: {
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
        !name.trimmingCharacters(in: .whitespaces).isEmpty &&
        selectedLocationId != nil
    }

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

    // MARK: - Submit

    private func submit() {
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedName.isEmpty else { focused = .name; return }
        guard let client = store.client else {
            submitError = "Not signed in. Open Settings to log in."
            return
        }
        guard let locId = selectedLocationId else {
            submitError = "Pick a location first."
            return
        }
        submitError = nil
        isSubmitting = true
        let payload = HBItemCreate(
            name: trimmedName,
            quantity: Double(quantity),
            description: description,
            locationId: locId,
            parentId: nil,
            tagIds: []
        )
        Task {
            do {
                try await client.createItem(payload)
                await MainActor.run {
                    UINotificationFeedbackGenerator().notificationOccurred(.success)
                    showSuccessPill("\"\(trimmedName)\"")
                    resetForm(trimmedName: trimmedName)
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

    private func resetForm(trimmedName: String) {
        name = ""
        quantity = 1
        description = ""
        if !lockLocation { selectedLocationId = nil }
        focused = .name
    }

    private func showSuccessPill(_ text: String) {
        withAnimation(.easeOut(duration: 0.15)) { justAdded = text }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.4) {
            withAnimation(.easeIn(duration: 0.3)) {
                if justAdded == text { justAdded = nil }
            }
        }
    }
}
