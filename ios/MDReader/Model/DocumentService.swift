import Foundation

/// 读取文档文本、取显示名、Markdown 扩展名校验。
enum DocumentService {

    static func isMarkdownAllowed(_ name: String?) -> Bool {
        guard let n = name?.lowercased() else { return true } // 无法获知名称时不拦截
        return n.hasSuffix(".md") || n.hasSuffix(".markdown")
    }

    static func displayName(for url: URL) -> String {
        url.lastPathComponent
    }

    /// 读取 UTF-8 文本（去 BOM）。会尝试开启安全作用域访问。
    static func readText(at url: URL) -> String? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url) else { return nil }
        var text = String(data: data, encoding: .utf8) ?? String(decoding: data, as: UTF8.self)
        if text.hasPrefix("\u{FEFF}") { text.removeFirst() }
        return text
    }
}
