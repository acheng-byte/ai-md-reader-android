import Foundation

struct HistoryEntry: Codable, Identifiable {
    var id: String { identity }
    let identity: String      // 身份键（通常为文件 URL 的 absoluteString）
    var name: String
    var time: Double          // 时间戳（秒）
    var bookmark: Data?       // 安全作用域书签，用于跨会话重开外部文件
}

/// 打开历史（UserDefaults 持久化）。按最近打开排序、按 identity 去重、限量。
final class HistoryStore {

    private let d = UserDefaults.standard
    private let key = "history_items_v1"
    private let maxItems = 200

    func all() -> [HistoryEntry] {
        guard let data = d.data(forKey: key),
              let items = try? JSONDecoder().decode([HistoryEntry].self, from: data) else { return [] }
        return items
    }

    func add(identity: String, name: String, bookmark: Data?) {
        var items = all()
        let existing = items.first { $0.identity == identity }
        items.removeAll { $0.identity == identity }
        let entry = HistoryEntry(
            identity: identity,
            name: name,
            time: Date().timeIntervalSince1970,
            bookmark: bookmark ?? existing?.bookmark
        )
        items.insert(entry, at: 0)
        if items.count > maxItems { items = Array(items.prefix(maxItems)) }
        save(items)
    }

    func clear() {
        d.removeObject(forKey: key)
    }

    private func save(_ items: [HistoryEntry]) {
        if let data = try? JSONEncoder().encode(items) {
            d.set(data, forKey: key)
        }
    }
}
