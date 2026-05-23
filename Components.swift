import SwiftUI

// MARK: - Alphabet index bar (iOS Contacts-style)

struct AlphabetIndexBar: View {
    let letters: [String]
    let onSelect: (String) -> Void

    @State private var touchY: CGFloat? = nil
    @State private var activeLetter: String? = nil
    @State private var isShowing = false

    private let hitWidth: CGFloat = 32

    var body: some View {
        GeometryReader { geo in
            let count = max(letters.count, 1)
            let itemH = geo.size.height / CGFloat(count)

            ZStack(alignment: .topTrailing) {
                ForEach(0..<letters.count, id: \.self) { idx in
                    letterView(letter: letters[idx], idx: idx, itemH: itemH)
                }
            }
            .frame(width: hitWidth, height: geo.size.height, alignment: .trailing)
            .opacity(isShowing ? 1 : 0)
            .animation(.easeOut(duration: 0.15), value: isShowing)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        if !isShowing { isShowing = true }
                        touchY = value.location.y
                        let idx = min(max(Int(value.location.y / itemH), 0), count - 1)
                        let letter = letters[idx]
                        if letter != activeLetter {
                            activeLetter = letter
                            UIImpactFeedbackGenerator(style: .light).impactOccurred()
                            onSelect(letter)
                        }
                    }
                    .onEnded { _ in
                        touchY = nil
                        activeLetter = nil
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                            isShowing = false
                        }
                    }
            )
        }
        .frame(width: hitWidth)
    }

    @ViewBuilder
    private func letterView(letter: String, idx: Int, itemH: CGFloat) -> some View {
        let letterCenterY = (CGFloat(idx) + 0.5) * itemH
        let dist: CGFloat = touchY.map { abs($0 - letterCenterY) } ?? .infinity
        let normDist = min(dist / (itemH * 4), 1.0)
        let scale: CGFloat = touchY == nil ? 1.0 : (1.0 + (1.0 - normDist) * 2.8)

        Text(letter)
            .font(.system(size: 11, weight: .bold))
            .foregroundStyle(Color.accentColor)
            .padding(.trailing, 6)
            .frame(height: itemH, alignment: .center)
            .offset(y: CGFloat(idx) * itemH)
            .scaleEffect(scale, anchor: .trailing)
            .zIndex(scale)
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

