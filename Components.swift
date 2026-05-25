import SwiftUI

// MARK: - Floating Card Modal

struct FloatingCardContainer<Content: View>: View {
    @Binding var isPresented: Bool
    var horizontalInset: CGFloat = 4
    @ViewBuilder let content: () -> Content

    @EnvironmentObject private var theme: ThemeManager

    var body: some View {
        let accent = theme.current.accentColor
        content()
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(.horizontal, horizontalInset)
            .presentationDetents([.fraction(0.85)])
            .presentationDragIndicator(.hidden)
            .presentationBackground {
                // Full-width Rectangle so there are no visible side edges.
                // presentationCornerRadius(28) handles top rounding natively.
                // The system sheet container extends to the physical screen bottom,
                // so the material fill is seamless with no home-indicator gap.
                ZStack {
                    Rectangle().fill(.ultraThinMaterial)
                    Rectangle().fill(accent.opacity(0.06))
                }
            }
            .presentationCornerRadius(28)
    }
}

extension View {
    func floatingCardCover<Content: View>(
        isPresented: Binding<Bool>,
        horizontalInset: CGFloat = 4,
        onDismiss: (() -> Void)? = nil,
        @ViewBuilder content: @escaping () -> Content
    ) -> some View {
        sheet(isPresented: isPresented, onDismiss: onDismiss) {
            FloatingCardContainer(
                isPresented: isPresented,
                horizontalInset: horizontalInset,
                content: content
            )
        }
    }
}

// MARK: - Description Editor Sheet

/// Full-screen editor used by Add/Create forms whose description fields
/// would otherwise force the parent form to scroll when the keyboard opens.
struct DescriptionEditorSheet: View {
    @EnvironmentObject var theme: ThemeManager
    @Environment(\.dismiss) private var dismiss
    @Binding var text: String
    let title: String
    let placeholder: String

    @FocusState private var focused: Bool
    @State private var draft: String = ""

    init(text: Binding<String>, title: String = "Notes", placeholder: String = "Add notes\u{2026}") {
        self._text = text
        self.title = title
        self.placeholder = placeholder
    }

    var body: some View {
        NavigationStack {
            ZStack(alignment: .topLeading) {
                theme.current.backgroundColor.ignoresSafeArea()
                TextEditor(text: $draft)
                    .focused($focused)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .scrollContentBackground(.hidden)
                    .scrollIndicators(.hidden)
                    .background(Color.clear)

                if draft.isEmpty {
                    Text(placeholder)
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 17)
                        .padding(.vertical, 16)
                        .allowsHitTesting(false)
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        text = draft
                        dismiss()
                    }
                    .bold()
                }
            }
            .onAppear {
                draft = text
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { focused = true }
            }
        }
    }
}

/// Compact tappable row that opens `DescriptionEditorSheet` instead of
/// activating an inline keyboard. Use for description / notes fields in
/// add/create forms that need to fit on screen without scrolling.
struct DescriptionField: View {
    @EnvironmentObject var theme: ThemeManager
    @Binding var text: String
    let placeholder: String
    let title: String

    @State private var showEditor = false

    init(text: Binding<String>, placeholder: String = "Add notes\u{2026}", title: String = "Notes") {
        self._text = text
        self.placeholder = placeholder
        self.title = title
    }

    var body: some View {
        Button { showEditor = true } label: {
            HStack(alignment: .center, spacing: 10) {
                Image(systemName: text.isEmpty ? "note.text" : "note.text.badge.plus")
                    .font(.body)
                    .foregroundStyle(theme.current.accentColor)
                Text(text.isEmpty ? placeholder : text)
                    .font(.callout)
                    .foregroundStyle(text.isEmpty ? .secondary : .primary)
                    .lineLimit(1)
                    .truncationMode(.tail)
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").font(.caption)
            }
            .padding(.horizontal, 14)
            .frame(maxWidth: .infinity)
            .frame(height: 44)
        }
        .buttonStyle(.glass)
        .sheet(isPresented: $showEditor) {
            DescriptionEditorSheet(text: $text, title: title, placeholder: placeholder)
                .environmentObject(theme)
        }
    }
}

// MARK: - Search Modifier

struct ConditionalSearchable: ViewModifier {
    @Binding var text: String
    @Binding var isPresented: Bool
    let prompt: String

    func body(content: Content) -> some View {
        if isPresented || !text.isEmpty {
            content.searchable(text: $text, isPresented: $isPresented, prompt: prompt)
        } else {
            content
        }
    }
}

// MARK: - Alphabet index bar (iOS Contacts-style)

/// Invisible right-edge touch strip. On press+drag, fires onSelect with the letter under the finger.
/// Pair it with `LetterPopupBox` shown as a centered overlay driven by the same binding.
struct AlphabetIndexBar: View {
    let letters: [String]
    @Binding var currentLetter: String?
    let onSelect: (String) -> Void

    var body: some View {
        GeometryReader { geo in
            let count = max(letters.count, 1)
            let itemH = geo.size.height / CGFloat(count)
            Color.clear
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { value in
                            let idx = min(max(Int(value.location.y / itemH), 0), count - 1)
                            let letter = letters[idx]
                            if letter != currentLetter {
                                currentLetter = letter
                                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                                onSelect(letter)
                            }
                        }
                        .onEnded { _ in
                            DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
                                withAnimation(.easeOut(duration: 0.2)) { currentLetter = nil }
                            }
                        }
                )
        }
        .frame(width: 32)
    }
}

/// Themed translucent letter card. Render as a centered overlay when `currentLetter` is set.
struct LetterPopupBox: View {
    let letter: String
    let accent: Color

    var body: some View {
        Text(letter)
            .font(.system(size: 80, weight: .bold, design: .rounded))
            .foregroundStyle(.white)
            .frame(width: 140, height: 140)
            .background(
                ZStack {
                    RoundedRectangle(cornerRadius: 28).fill(.ultraThinMaterial)
                    RoundedRectangle(cornerRadius: 28).fill(accent.opacity(0.78))
                }
            )
            .overlay(RoundedRectangle(cornerRadius: 28).stroke(.white.opacity(0.3), lineWidth: 1.5))
            .shadow(color: .black.opacity(0.3), radius: 16, x: 0, y: 6)
    }
}

// MARK: - Glass card

/// A common card shape — Liquid Glass background with theme tint.
struct GlassCard<Content: View>: View {
    @EnvironmentObject var theme: ThemeManager
    var title: String?
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let title {
                Text(title.uppercased())
                    .font(.caption.weight(.semibold))
                    .tracking(0.6)
                    .foregroundStyle(theme.current.accentColor.opacity(0.75))
            }
            content
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background {
            RoundedRectangle(cornerRadius: 18).fill(.ultraThinMaterial)
            RoundedRectangle(cornerRadius: 18).fill(theme.current.accentColor.opacity(0.08))
        }
        .overlay(
            RoundedRectangle(cornerRadius: 18).stroke(theme.current.accentColor.opacity(0.25), lineWidth: 1)
        )
    }
}

/// Custom -/+ quantity pill. Avoids `Stepper(...).labelsHidden()` which
/// reserves space for the hidden label on iOS 26 and blows out the row.
struct QuantityControl: View {
    @EnvironmentObject var theme: ThemeManager
    @Binding var value: Int
    var range: ClosedRange<Int> = 1...9999

    var body: some View {
        HStack(spacing: 0) {
            stepperButton(systemImage: "minus") {
                if value > range.lowerBound { value -= 1 }
            }
            Text("\(value)")
                .font(.title3.monospacedDigit().weight(.semibold))
                .frame(minWidth: 40)
                .contentTransition(.numericText())
            stepperButton(systemImage: "plus") {
                if value < range.upperBound { value += 1 }
            }
        }
        .background { Capsule().fill(.ultraThinMaterial) }
        .overlay(Capsule().stroke(theme.current.accentColor.opacity(0.30), lineWidth: 1))
        .fixedSize()
    }

    private func stepperButton(systemImage: String, action: @escaping () -> Void) -> some View {
        Button {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            action()
        } label: {
            Image(systemName: systemImage)
                .font(.body.weight(.semibold))
                .frame(width: 40, height: 34)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Thumbnail cache (plain class — rows update via local @State, not @Published)

@MainActor
class ThumbnailStore {
    private var cache: [String: String] = [:]   // itemId → attId or "" (no thumb)
    private var inFlight: [String: Task<String?, Never>] = [:]

    func load(itemId: String, client: HomeboxClient) async -> String? {
        if let cached = cache[itemId] { return cached.isEmpty ? nil : cached }
        if let task = inFlight[itemId] { return await task.value }
        let task = Task<String?, Never> {
            if let detail = try? await client.getItem(id: itemId),
               let att = (detail.attachments ?? []).first(where: { $0.primary == true })
                       ?? (detail.attachments ?? []).first(where: { $0.type.lowercased() == "photo" }) {
                return att.id
            }
            return nil
        }
        inFlight[itemId] = task
        let result = await task.value
        cache[itemId] = result ?? ""
        inFlight[itemId] = nil
        return result
    }
}

// MARK: - Reusable Item Row
struct ItemListRowContent: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager
    let item: HBItem
    let thumbStore: ThumbnailStore

    @State private var thumbAttId: String? = nil
    @State private var thumbLoaded = false

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            thumbnailView
                .frame(width: 48, height: 48)
                .clipShape(RoundedRectangle(cornerRadius: 9))

            VStack(alignment: .leading, spacing: 2) {
                Text(item.name).font(.body.weight(.medium)).lineLimit(1)
                if let path = breadcrumb {
                    HStack(spacing: 3) {
                        Image(systemName: "mappin.and.ellipse").font(.caption2)
                        Text(path).font(.caption).monospaced().lineLimit(1)
                    }
                    .foregroundStyle(.secondary)
                }
                if let d = item.description, !d.isEmpty {
                    Text(d).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            Text("\u{00d7}\(item.quantityInt)").font(.caption.monospacedDigit().weight(.medium)).foregroundStyle(.secondary)
        }
        .padding(.horizontal, 10).padding(.vertical, 8)
        .task(id: item.id) {
            guard let client = store.client else { return }
            let attId = await thumbStore.load(itemId: item.id, client: client)
            thumbAttId = attId
            thumbLoaded = true
        }
    }

    @ViewBuilder
    private var thumbnailView: some View {
        if !thumbLoaded {
            ZStack {
                RoundedRectangle(cornerRadius: 9).fill(theme.current.accentColor.opacity(0.10))
                ProgressView().controlSize(.small)
            }
        } else if let attId = thumbAttId {
            AuthImage(itemId: item.id, attachmentId: attId, allowsFullScreen: false).scaledToFill()
        } else {
            ZStack {
                RoundedRectangle(cornerRadius: 9).fill(theme.current.accentColor.opacity(0.12))
                Text("\(item.quantityInt)").font(.callout.weight(.semibold).monospacedDigit())
                    .foregroundStyle(theme.current.accentColor)
            }
        }
    }

    private var breadcrumb: String? {
        if let id = item.location?.id { let p = store.pathString(forLocationId: id); if !p.isEmpty { return p } }
        return item.location?.name
    }
}

// MARK: - Swipe-to-reveal row

/// Wraps content with a horizontal drag gesture that reveals a colored action button on the trailing edge.
/// Haptic fires when the swipe passes threshold. Works inside ScrollView — only activates on left-dominant drags.
struct SwipeRevealRow<Content: View>: View {
    let buttonLabel: String
    let buttonIcon: String
    let buttonColor: Color
    let disabled: Bool
    let action: () -> Void
    let content: Content

    @State private var offset: CGFloat = 0
    @State private var hapticFired = false

    private let revealWidth: CGFloat = 82
    private let threshold: CGFloat = 52

    init(buttonLabel: String, buttonIcon: String, buttonColor: Color = .orange,
         disabled: Bool = false, action: @escaping () -> Void,
         @ViewBuilder content: () -> Content) {
        self.buttonLabel = buttonLabel
        self.buttonIcon = buttonIcon
        self.buttonColor = buttonColor
        self.disabled = disabled
        self.action = action
        self.content = content()
    }

    var body: some View {
        ZStack(alignment: .trailing) {
            Button {
                withAnimation(.spring(duration: 0.2)) { offset = 0; hapticFired = false }
                action()
            } label: {
                VStack(spacing: 4) {
                    Image(systemName: buttonIcon).font(.system(size: 18, weight: .semibold))
                    Text(buttonLabel).font(.caption2.weight(.medium))
                }
                .foregroundStyle(.white)
                .frame(width: revealWidth)
                .frame(maxHeight: .infinity)
            }
            .background(buttonColor)
            .clipShape(RoundedRectangle(cornerRadius: 14))
            .opacity(offset < -8 ? 1 : 0)

            content
                .offset(x: offset)
        }
        .clipped()
        .gesture(
            DragGesture(minimumDistance: 15, coordinateSpace: .local)
                .onChanged { value in
                    guard !disabled else { return }
                    let dx = value.translation.width
                    let dy = abs(value.translation.height)
                    guard dx < 0, abs(dx) > dy else {
                        if offset < 0 { withAnimation(.spring(duration: 0.2)) { offset = 0; hapticFired = false } }
                        return
                    }
                    offset = max(-revealWidth, dx)
                    if offset <= -threshold && !hapticFired {
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        hapticFired = true
                    }
                }
                .onEnded { value in
                    guard !disabled else { return }
                    if value.translation.width > -(threshold / 2) {
                        withAnimation(.spring(duration: 0.2)) { offset = 0; hapticFired = false }
                    } else {
                        withAnimation(.spring(duration: 0.2)) { offset = -revealWidth }
                    }
                }
        )
        .onChange(of: disabled) { _, isDisabled in
            if isDisabled { withAnimation { offset = 0; hapticFired = false } }
        }
    }
}


