import Foundation
import CoreFoundation

/// 读取文档文本、取显示名、Markdown 扩展名校验。
enum DocumentService {

    static func isMarkdownAllowed(_ name: String?) -> Bool {
        guard let n = name?.lowercased() else { return true } // 无法获知名称时不拦截
        return n.hasSuffix(".md") || n.hasSuffix(".markdown")
    }

    static func displayName(for url: URL) -> String {
        url.lastPathComponent
    }

    /// 读取文本（自动检测编码：UTF-8 / UTF-16 / GB18030）。会尝试开启安全作用域访问。
    static func readText(at url: URL) -> String? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url) else { return nil }
        var text = decodeText(data)
        // 换行规范化：\r\n → \n，孤立 \r → \n
        text = text.replacingOccurrences(of: "\r\n", with: "\n")
                   .replacingOccurrences(of: "\r", with: "\n")
        return text
    }

    /// 编码检测链：BOM → UTF-8 → GB18030
    private static func decodeText(_ data: Data) -> String {
        let bytes = [UInt8](data)

        // 1. BOM-based detection
        if bytes.count >= 2 {
            // UTF-16LE: FF FE
            if bytes[0] == 0xFF && bytes[1] == 0xFE {
                if let s = String(data: data, encoding: .utf16LittleEndian) {
                    return s.replacingOccurrences(of: "\u{FEFF}", with: "")
                }
            }
            // UTF-16BE: FE FF
            if bytes[0] == 0xFE && bytes[1] == 0xFF {
                if let s = String(data: data, encoding: .utf16BigEndian) {
                    return s.replacingOccurrences(of: "\u{FEFF}", with: "")
                }
            }
            // UTF-8 BOM: EF BB BF
            if bytes.count >= 3 && bytes[0] == 0xEF && bytes[1] == 0xBB && bytes[2] == 0xBF {
                if let s = String(data: data, encoding: .utf8) {
                    return s.hasPrefix("\u{FEFF}") ? String(s.dropFirst()) : s
                }
            }
        }

        // 2. Try UTF-8, count replacement characters
        let utf8Text = String(decoding: data, as: UTF8.self)
        let utf8Repl = utf8Text.unicodeScalars.filter { $0.value == 0xFFFD }.count

        if utf8Repl == 0 || Double(utf8Repl) / Double(max(utf8Text.count, 1)) <= 0.001 {
            // UTF-8 基本无替换字符，直接使用
            return utf8Text.hasPrefix("\u{FEFF}") ? String(utf8Text.dropFirst()) : utf8Text
        }

        // 3. UTF-8 有较多替换字符，尝试 GB18030 并比较
        let gb18030Enc = CFStringConvertEncodingToNSStringEncoding(
            CFStringEncoding(CFStringEncodings.GB_18030_2000.rawValue)
        )
        if let gbText = String(data: data, encoding: String.Encoding(rawValue: gb18030Enc)) {
            let gbRepl = gbText.unicodeScalars.filter { $0.value == 0xFFFD }.count
            // 只有 GB18030 确实比 UTF-8 更好时才采用
            if gbRepl < utf8Repl {
                return gbText
            }
        }

        // 4. GB18030 没有更好，保留 UTF-8（去除 BOM）
        return utf8Text.hasPrefix("\u{FEFF}") ? String(utf8Text.dropFirst()) : utf8Text
    }
}
