import Foundation
import CryptoKit

struct FavoriteItem: Codable, Identifiable {
    var id: String { identity }
    let identity: String      // 身份键（原始文件 URL absoluteString），用于去重与跨表关联
    var name: String
    var file: String          // 收藏目录下的副本文件名
    var time: Double
    var bookmark: Data?       // 原始文件的书签（用于历史里反映“原文件”状态）
}

/// 收藏夹：把文档复制一份到 Documents/favorites/。
/// - 以 identity 去重：同一文档不重复复制；副本文件名由 identity 哈希决定，确保只存一份。
/// - 取消收藏会同步删除副本文件。
final class FavoritesStore {

    private let d = UserDefaults.standard
    private let key = "favorites_items_v1"
    private let dir: URL

    init() {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        dir = docs.appendingPathComponent("favorites", isDirectory: true)
        if !FileManager.default.fileExists(atPath: dir.path) {
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        }
    }

    func all() -> [FavoriteItem] {
        guard let data = d.data(forKey: key),
              let items = try? JSONDecoder().decode([FavoriteItem].self, from: data) else { return [] }
        return items
    }

    func isFavorite(_ identity: String) -> Bool { all().contains { $0.identity == identity } }

    func find(_ identity: String) -> FavoriteItem? { all().first { $0.identity == identity } }

    func fileURL(_ item: FavoriteItem) -> URL { dir.appendingPathComponent(item.file) }

    /// 收藏：已收藏（同一 identity）则直接返回已有项，不重复复制。
    @discardableResult
    func add(identity: String, name: String, content: String, bookmark: Data?) -> FavoriteItem? {
        if let existing = find(identity) { return existing }
        let fileName = fileNameFor(identity: identity, name: name)
        let url = dir.appendingPathComponent(fileName)
        do {
            try content.data(using: .utf8)?.write(to: url, options: .atomic)
        } catch {
            return nil
        }
        let item = FavoriteItem(
            identity: identity, name: name, file: fileName,
            time: Date().timeIntervalSince1970, bookmark: bookmark
        )
        var items = all()
        items.insert(item, at: 0)
        save(items)
        return item
    }

    /// 取消收藏：删除条目并同步删除副本文件。
    func remove(_ identity: String) {
        var items = all()
        if let idx = items.firstIndex(where: { $0.identity == identity }) {
            let item = items.remove(at: idx)
            try? FileManager.default.removeItem(at: dir.appendingPathComponent(item.file))
            save(items)
        }
    }

    private func fileNameFor(identity: String, name: String) -> String {
        let ext = name.lowercased().hasSuffix(".markdown") ? "markdown" : "md"
        return sha1(identity) + "." + ext
    }

    private func sha1(_ s: String) -> String {
        let digest = Insecure.SHA1.hash(data: Data(s.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    private func save(_ items: [FavoriteItem]) {
        if let data = try? JSONEncoder().encode(items) {
            d.set(data, forKey: key)
        }
    }
}
