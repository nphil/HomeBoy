import SwiftUI

// MARK: - Alphabet index bar (iOS Contacts-style)

struct AlphabetIndexBar: View {
    let letters: [String]
    let onSelect: (String) -> Void
    @State private var activeLetter: String? = nil

    var body: some View {
        GeometryReader { geo in
            let count = max(letters.count, 1)
            let itemH = geo.size.height / CGFloat(count)
            ZStack(alignment: .trailing) {
                if let letter = activeLetter {
                    Text(letter)
                        .font(.system(size: 52, weight: .bold, design: .rounded))
                        .foregroundStyle(.primary)
                        .frame(width: 72, height: 72)
                        .background(RoundedRectangle(cornerRadius: 16).fill(.regularMaterial))
                        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.primary.opacity(0.1), lineWidth: 1))
                        .offset(x: -26)
                        .allowsHitTesting(false)
                        .transition(.scale(scale: 0.8).combined(with: .opacity))
                        .animation(.spring(response: 0.2, dampingFraction: 0.7), value: activeLetter)
                }

                VStack(spacing: 0) {
                    ForEach(letters, id: \.self) { letter in
                        Text(letter)
                            .font(.system(size: 11, weight: .bold))
                            .foregroundStyle(activeLetter == letter ? .white : Color.secondary)
                            .frame(width: 20, height: itemH)
                            .background(
                                Circle()
                                    .fill(Color.accentColor)
                                    .frame(width: 18, height: 18)
                                    .opacity(activeLetter == letter ? 1 : 0)
                            )
                    }
                }
                .frame(width: 20)
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { value in
                            let idx = min(max(Int(value.location.y / itemH), 0), count - 1)
                            let letter = letters[idx]
                            if letter != activeLetter {
                                activeLetter = letter
                                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                                onSelect(letter)
                            }
                        }
                        .onEnded { _ in
                            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                                withAnimation(.easeOut(duration: 0.2)) { activeLetter = nil }
                            }
                        }
                )
            }
        }
        .frame(width: 20)
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

