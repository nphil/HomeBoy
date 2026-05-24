import SwiftUI

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
            Text("×\(item.quantityInt)").font(.caption.monospacedDigit().weight(.medium)).foregroundStyle(.secondary)
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
