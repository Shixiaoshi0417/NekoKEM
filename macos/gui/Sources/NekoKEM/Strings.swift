import Foundation

/// The shared desktop catalog (desktop-compose/src/main/resources/strings.json):
/// the app bundle carries a copy in Resources.
enum Strings {
    /// Catalog order of each message's translations.
    static let languages = ["en", "zh-CN", "zh-TW", "ja", "ko"]
    private static let root: [String: Any] = {
        let candidates = [
            Bundle.main.url(forResource: "strings", withExtension: "json"),
            ProcessInfo.processInfo.environment["NEKOKEM_STRINGS"].map { URL(fileURLWithPath: $0) },
        ]
        for case let url? in candidates {
            if let data = try? Data(contentsOf: url), let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] { return object }
        }
        return [:]
    }()
    private static let messages = root["messages"] as? [String: [String]] ?? [:]
    /// The language menu: "system" first, then each language in its own name.
    static let choices: [(tag: String, name: String)] = {
        let names = root["names"] as? [String: String] ?? [:]
        return (root["languages"] as? [String] ?? ["system"]).map { ($0, names[$0] ?? $0) }
    }()

    static func has(_ key: String) -> Bool { messages[key] != nil }
    static func get(_ key: String, _ language: String) -> String {
        guard let values = messages[key], !values.isEmpty else { return key }
        let index = languages.firstIndex(of: language) ?? 0
        return index < values.count ? values[index] : values[0]
    }
}
