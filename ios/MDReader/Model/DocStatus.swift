import Foundation

/// 文档可访问状态（与 Android 端 DocStatus 对齐）：
/// - available：可正常打开
/// - expired：无法获得访问权限（安全作用域书签无法解析，如从其他来源打开后授权失效）
/// - deleted：能定位但文件已不存在（被物理删除）
enum DocStatus {
    case available
    case expired
    case deleted
}

/// 基于安全作用域书签的可访问性判定与解析。
/// iOS 没有像“持久化 URI 权限”那样清晰的过期/删除区分，这里采用最佳启发式：
/// 书签无法解析 → 视为授权过期；可解析但文件不存在 → 视为已删除。
enum DocAccess {

    static func makeBookmark(for url: URL) -> Data? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        return try? url.bookmarkData()
    }

    /// 解析书签得到 URL（调用方负责 start/stopAccessingSecurityScopedResource）。
    static func resolve(bookmark: Data?) -> URL? {
        guard let bm = bookmark else { return nil }
        var stale = false
        return try? URL(
            resolvingBookmarkData: bm, options: [], relativeTo: nil, bookmarkDataIsStale: &stale
        )
    }

    /// 判断状态。identity 作为无书签时的兜底（应用容器内文件用其路径判断存在性）。
    static func status(bookmark: Data?, identity: String) -> DocStatus {
        if let url = resolve(bookmark: bookmark) {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            return FileManager.default.fileExists(atPath: url.path) ? .available : .deleted
        }
        // 无书签：identity 可能是应用容器内（如 Inbox 拷贝）的文件 URL
        if let url = URL(string: identity), url.isFileURL {
            return FileManager.default.fileExists(atPath: url.path) ? .available : .deleted
        }
        return .expired
    }
}
