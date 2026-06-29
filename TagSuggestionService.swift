import Foundation
import FoundationModels

/// Result of an AI tag suggestion: existing tags to offer for quick-select, plus
/// novel tag names (title-cased) that aren't in the library yet.
struct TagSuggestions: Sendable {
    var matchedIds: [String]
    var novel: [String]

    var isEmpty: Bool { matchedIds.isEmpty && novel.isEmpty }
}

/// Provider-aware tag suggestion.
/// - `apple` — Apple Foundation Models (`SystemLanguageModel`, Neural Engine). Default.
/// - `gguf` — llama.cpp GGUF generation models. Wired in Phase 2.
///
/// Suggestion post-processing (reconciliation, plural folding, title-casing, thinking
/// stripping) is ported from the Android `TagSuggestionService` so both platforms
/// behave the same. See the `llm-integration-patterns` memory.
@MainActor
final class TagSuggestionService {

    /// Returns suggestions, or `nil` if the chosen provider is unavailable.
    func suggest(name: String,
                 description: String,
                 existing: [HBTag],
                 provider: LLMProvider) async -> TagSuggestions? {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count >= 3 else { return nil }
        switch provider {
        case .apple:
            return await suggestApple(name: trimmed, description: description, existing: existing)
        case .gguf:
            return await suggestGGUF(name: trimmed, description: description, existing: existing)
        }
    }

    // MARK: - Apple Foundation Models

    private func suggestApple(name: String, description: String, existing: [HBTag]) async -> TagSuggestions? {
        guard SystemLanguageModel.default.isAvailable else { return nil }
        do {
            let session = LanguageModelSession()
            let response = try await session.respond(
                to: tagInstructions(noThink: false) + "\n\n" + tagUser(name: name, description: description))
            return reconcile(raw: "\(response.content)", existing: existing)
        } catch {
            return nil
        }
    }

    // MARK: - GGUF (llama.cpp) via the shared GenerationEngine

    private func suggestGGUF(name: String, description: String, existing: [HBTag]) async -> TagSuggestions? {
        // /no_think suppresses Qwen3 reasoning; reconcile() also strips any <think> that leaks.
        guard let raw = await GenerationEngine.shared.generate(
            system: tagInstructions(noThink: true),
            user: tagUser(name: name, description: description)) else { return nil }
        return reconcile(raw: raw, existing: existing)
    }

    // We deliberately do NOT feed the existing tag list as the choice set — the model
    // would force-fit unrelated tags. It suggests freely; we reconcile in code.
    private func tagInstructions(noThink: Bool) -> String {
        let base = """
        You label home-inventory items with short tags. Reply with ONLY a comma-separated \
        list of 3 to 6 tags, each 1 or 2 words, and nothing else. Pick tags that genuinely \
        describe the item — its kind, category, use, or where it is kept. \
        Example: for "cordless drill" reply: Tools, Power Tools, Garage, Hardware, DIY
        """
        return noThink ? "/no_think\n" + base : base
    }

    private func tagUser(name: String, description: String) -> String {
        var user = "Item: \(name)"
        let desc = description.trimmingCharacters(in: .whitespacesAndNewlines)
        if !desc.isEmpty { user += "\nDescription: \(desc)" }
        user += "\nTags:"
        return user
    }

    // MARK: - Reconciliation (ported from Android)

    private func reconcile(raw: String, existing: [HBTag]) -> TagSuggestions {
        var existingBySingular: [String: HBTag] = [:]
        for t in existing { existingBySingular[singular(t.name)] = t }

        var matched: [String] = []
        var novel: [String] = []
        var seen = Set<String>()

        for tag in parse(raw) {
            let key = singular(tag)
            guard !key.isEmpty, !seen.contains(key) else { continue }
            seen.insert(key)
            if let hit = existingBySingular[key] {
                if !matched.contains(hit.id) { matched.append(hit.id) }
            } else {
                novel.append(titleCase(tag))
            }
        }
        return TagSuggestions(matchedIds: matched, novel: novel)
    }

    private func parse(_ raw: String) -> [String] {
        var text = stripThinking(raw)
        if let r = text.range(of: "Tags:", options: .caseInsensitive) {
            text = String(text[r.upperBound...])
        }
        let line = text.split(whereSeparator: { $0.isNewline }).first.map(String.init) ?? text
        let trimChars = CharacterSet(charactersIn: " -*.\"'#\t")
        var result: [String] = []
        for piece in line.split(whereSeparator: { $0 == "," || $0 == ";" }) {
            let t = piece.trimmingCharacters(in: trimChars)
            let words = t.split(separator: " ")
            guard t.count >= 2, t.count <= 30, words.count <= 3 else { continue }
            if result.contains(where: { $0.caseInsensitiveCompare(t) == .orderedSame }) { continue }
            result.append(t)
            if result.count >= 6 { break }
        }
        return result
    }

    /// Strip `<think>...</think>` blocks (Qwen3 may leak them even with /no_think).
    private func stripThinking(_ raw: String) -> String {
        var s = raw
        while let start = s.range(of: "<think>") {
            if let end = s.range(of: "</think>", range: start.upperBound..<s.endIndex) {
                s.removeSubrange(start.lowerBound..<end.upperBound)
            } else {
                s.removeSubrange(start.lowerBound..<s.endIndex)
                break
            }
        }
        return s.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Lowercase + collapse spaces + fold simple plurals so "Spices" matches "spice".
    private func singular(_ s: String) -> String {
        var w = s.lowercased().trimmingCharacters(in: .whitespaces)
        w = w.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
        if w.hasSuffix("ies"), w.count > 3 { return String(w.dropLast(3)) + "y" }
        // Skip sibilant -es (boxes, glasses) — rare in inventory tags.
        if w.hasSuffix("s"), !w.hasSuffix("ss"), !w.hasSuffix("es"), w.count > 1 {
            return String(w.dropLast())
        }
        return w
    }

    /// Title-case only all-lowercase words, preserving deliberate casing (USB, iPhone).
    private func titleCase(_ s: String) -> String {
        s.split(separator: " ").map { word -> String in
            let str = String(word)
            if !str.isEmpty, !str.contains(where: { $0.isUppercase }) {
                return str.prefix(1).uppercased() + String(str.dropFirst())
            }
            return str
        }
        .joined(separator: " ")
    }
}
