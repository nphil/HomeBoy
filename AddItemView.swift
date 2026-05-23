import SwiftUI
import UIKit

struct AddItemView: View {
    @EnvironmentObject var store: CatalogStore
    @EnvironmentObject var theme: ThemeManager

    @State private var name = ""
    @State private var quantity = 1
    @State private var loc1 = ""
    @State private var loc2 = ""
    @State private var loc3 = ""
    @State private var details = ""
    @State private var labels = ""
    @State private var showOptional = false
    @State private var justAdded: String? = nil

    enum Field: Hashable { case name, qty, loc1, loc2, loc3, details, labels }
    @FocusState private var focused: Field?

    var body: some View {
        NavigationStack {
            ZStack {
                ThemeBackground()
                ScrollView {
                    VStack(spacing: 16) {
                        countChip
                        nameAndQuantityCard
                        locationCard
                        optionalCard
                        addButton
                        if let justAdded {
                            successPill(justAdded)
                        }
                    }
                    .padding(16)
                    .padding(.bottom, 60)
                }
                .scrollDismissesKeyboard(.interactively)
            }
            .navigationTitle("")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) { BrandMark() }
                ToolbarItemGroup(placement: .keyboard) {
                    recentChipsForFocused
                    Spacer()
                    Button("Done") { focused = nil }
                        .font(.callout.weight(.semibold))
                }
            }
            .onAppear {
                // Auto-focus name on first appear so you can start typing immediately.
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                    if focused == nil { focused = .name }
                }
            }
        }
    }

    // MARK: - Subviews

    private var countChip: some View {
        HStack {
            Image(systemName: "tray.full.fill")
                .font(.caption)
            Text("\(store.items.count) \(store.items.count == 1 ? "item" : "items") in queue")
                .font(.callout)
            Spacer()
        }
        .foregroundStyle(.secondary)
        .padding(.horizontal, 4)
    }

    private var nameAndQuantityCard: some View {
        GlassCard(title: "Item") {
            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("NAME")
                        .font(.caption.weight(.semibold))
                        .tracking(0.6)
                        .foregroundStyle(theme.current.accentColor.opacity(0.75))
                    TextField("e.g. Cordless drill", text: $name)
                        .textFieldStyle(.plain)
                        .focused($focused, equals: .name)
                        .submitLabel(.next)
                        .onSubmit { focused = .loc1 }
                        .font(.title3)
                        .autocorrectionDisabled(false)
                        .textInputAutocapitalization(.sentences)
                }
                VStack(alignment: .leading, spacing: 6) {
                    Text("QTY")
                        .font(.caption.weight(.semibold))
                        .tracking(0.6)
                        .foregroundStyle(theme.current.accentColor.opacity(0.75))
                    Stepper(value: $quantity, in: 1...9999) {
                        Text("\(quantity)")
                            .font(.title3.monospacedDigit())
                            .frame(minWidth: 30, alignment: .leading)
                    }
                    .labelsHidden()
                }
            }
        }
    }

    private var locationCard: some View {
        GlassCard(title: "Location") {
            VStack(spacing: 12) {
                locationField(level: 0, value: $loc1, placeholder: "e.g. Garage", field: .loc1, next: .loc2, label: "Location")
                locationField(level: 1, value: $loc2, placeholder: "Sublocation (optional)", field: .loc2, next: .loc3, label: "Sublocation")
                locationField(level: 2, value: $loc3, placeholder: "Sub-sublocation (optional)", field: .loc3, next: nil, label: "Sub-sublocation")
            }
        }
    }

    private func locationField(level: Int, value: Binding<String>, placeholder: String, field: Field, next: Field?, label: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                LockToggle(locked: $store.locks[level], label: label)
                Spacer()
            }
            TextField(placeholder, text: value)
                .textFieldStyle(.plain)
                .focused($focused, equals: field)
                .submitLabel(next == nil ? .done : .next)
                .onSubmit {
                    if let next {
                        focused = next
                    } else {
                        submit()
                    }
                }
                .textInputAutocapitalization(.words)
                .autocorrectionDisabled(false)
                .padding(.vertical, 6)
                .overlay(alignment: .bottom) {
                    Rectangle()
                        .fill(theme.current.accentColor.opacity(focused == field ? 0.6 : 0.18))
                        .frame(height: focused == field ? 2 : 1)
                }
        }
    }

    private var optionalCard: some View {
        GlassCard {
            DisclosureGroup(isExpanded: $showOptional) {
                VStack(alignment: .leading, spacing: 14) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("DESCRIPTION")
                            .font(.caption.weight(.semibold))
                            .tracking(0.6)
                            .foregroundStyle(theme.current.accentColor.opacity(0.75))
                        TextField("Notes about the item", text: $details, axis: .vertical)
                            .focused($focused, equals: .details)
                            .lineLimit(1...4)
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        Text("LABELS  ·  semicolon-separated")
                            .font(.caption.weight(.semibold))
                            .tracking(0.6)
                            .foregroundStyle(theme.current.accentColor.opacity(0.75))
                        TextField("tools; power; cordless", text: $labels)
                            .focused($focused, equals: .labels)
                            .autocorrectionDisabled(true)
                            .textInputAutocapitalization(.never)
                    }
                }
                .padding(.top, 8)
            } label: {
                HStack {
                    Image(systemName: "text.alignleft")
                    Text("More details")
                        .font(.callout.weight(.medium))
                    Spacer()
                }
                .foregroundStyle(.primary)
            }
        }
    }

    private var addButton: some View {
        Button {
            submit()
        } label: {
            Label("Add to queue", systemImage: "plus.circle.fill")
                .font(.title3.weight(.semibold))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 6)
        }
        .buttonStyle(.glassProminent)
        .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
    }

    @ViewBuilder
    private func successPill(_ text: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "checkmark.circle.fill")
            Text("Added \(text)")
                .font(.callout)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(Capsule().fill(.ultraThinMaterial))
        .overlay(Capsule().stroke(Color.green.opacity(0.5), lineWidth: 1))
        .foregroundStyle(.primary)
        .transition(.opacity.combined(with: .scale))
    }

    @ViewBuilder
    private var recentChipsForFocused: some View {
        let level: Int? = {
            switch focused {
            case .loc1: return 0
            case .loc2: return 1
            case .loc3: return 2
            default: return nil
            }
        }()
        if let level {
            let recents = store.recentLocations(level: level)
            if !recents.isEmpty {
                RecentChips(values: recents) { v in
                    switch level {
                    case 0: loc1 = v
                    case 1: loc2 = v
                    case 2: loc3 = v
                    default: break
                    }
                }
            }
        }
    }

    // MARK: - Actions

    private func submit() {
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedName.isEmpty else {
            focused = .name
            return
        }
        let item = CatalogItem(
            name: trimmedName,
            quantity: max(1, quantity),
            location1: loc1.trimmingCharacters(in: .whitespaces),
            location2: loc2.trimmingCharacters(in: .whitespaces),
            location3: loc3.trimmingCharacters(in: .whitespaces),
            details: details.trimmingCharacters(in: .whitespacesAndNewlines),
            labels: labels.trimmingCharacters(in: .whitespacesAndNewlines)
        )
        store.add(item)
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        showSuccessPill("\"\(trimmedName)\"")
        resetForm()
    }

    private func resetForm() {
        name = ""
        quantity = 1
        details = ""
        labels = ""
        if !store.locks[0] { loc1 = "" }
        if !store.locks[1] { loc2 = "" }
        if !store.locks[2] { loc3 = "" }
        focused = .name
    }

    private func showSuccessPill(_ text: String) {
        withAnimation(.easeOut(duration: 0.15)) {
            justAdded = text
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.4) {
            withAnimation(.easeIn(duration: 0.3)) {
                if justAdded == text { justAdded = nil }
            }
        }
    }
}
