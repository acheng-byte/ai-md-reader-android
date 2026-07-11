import Foundation
import SwiftUI

/// 阅读显示设置（字号 / 行间距 / 段间距 / 主题 / 护眼 / 字体 / 视图模式等），持久化到 UserDefaults。
/// 与 Android 端 Prefs 对齐。作为 ObservableObject 供设置面板绑定。
@MainActor
final class Prefs: ObservableObject {

    static let defaultFont: Double = 16
    static let defaultLine: Double = 1.7
    static let defaultPara: Double = 1.0
    static let fontRange: ClosedRange<Double> = 12...30
    static let lineRange: ClosedRange<Double> = 1.0...2.4
    static let paraRange: ClosedRange<Double> = 0.0...2.0

    private let d = UserDefaults.standard

    @Published var fontSize: Double { didSet { d.set(fontSize, forKey: K.font) } }
    @Published var lineHeight: Double { didSet { d.set(lineHeight, forKey: K.line) } }
    @Published var paraGap: Double { didSet { d.set(paraGap, forKey: K.para) } }
    /// 0 跟随系统 / 1 浅色 / 2 深色
    @Published var themeMode: Int { didSet { d.set(themeMode, forKey: K.theme) } }
    /// "preview" / "code"
    @Published var viewMode: String { didSet { d.set(viewMode, forKey: K.mode) } }
    /// 护眼模式
    @Published var eyeProtection: Bool { didSet { d.set(eyeProtection, forKey: K.eyeProtection) } }
    /// 字体: "default" / "serif" / "mono"
    @Published var fontFamily: String { didSet { d.set(fontFamily, forKey: K.fontFamily) } }
    /// 显示 Frontmatter
    @Published var showFrontmatter: Bool { didSet { d.set(showFrontmatter, forKey: K.showFrontmatter) } }
    /// 显示引用块样式
    @Published var showCitations: Bool { didSet { d.set(showCitations, forKey: K.showCitations) } }
    /// 隐藏文件名一级标题
    @Published var hideTitleHeading: Bool { didSet { d.set(hideTitleHeading, forKey: K.hideTitleHeading) } }

    init() {
        fontSize = d.object(forKey: K.font) as? Double ?? Self.defaultFont
        lineHeight = d.object(forKey: K.line) as? Double ?? Self.defaultLine
        paraGap = d.object(forKey: K.para) as? Double ?? Self.defaultPara
        themeMode = d.object(forKey: K.theme) as? Int ?? 0
        viewMode = d.string(forKey: K.mode) ?? "preview"
        eyeProtection = d.object(forKey: K.eyeProtection) as? Bool ?? false
        fontFamily = d.string(forKey: K.fontFamily) ?? "default"
        showFrontmatter = d.object(forKey: K.showFrontmatter) as? Bool ?? true
        showCitations = d.object(forKey: K.showCitations) as? Bool ?? true
        hideTitleHeading = d.object(forKey: K.hideTitleHeading) as? Bool ?? true
    }

    func isDark(systemDark: Bool) -> Bool {
        switch themeMode {
        case 1: return false
        case 2: return true
        default: return systemDark
        }
    }

    /// 供 evaluateJavaScript("appApplySettings(<json>)") 使用，数值已规整。
    func settingsJSON(systemDark: Bool) -> String {
        let dark = isDark(systemDark: systemDark)
        let f = Int(fontSize.rounded())
        let lh = (lineHeight * 10).rounded() / 10
        let pg = (paraGap * 10).rounded() / 10
        return String(
            format: "{\"fontSize\":%d,\"lineHeight\":%.1f,\"paraGap\":%.1f,\"dark\":%@,\"eyeProtection\":%@,\"fontFamily\":\"%@\",\"showFrontmatter\":%@,\"showCitations\":%@,\"hideTitleHeading\":%@}",
            f, lh, pg,
            dark ? "true" : "false",
            eyeProtection ? "true" : "false",
            fontFamily,
            showFrontmatter ? "true" : "false",
            showCitations ? "true" : "false",
            hideTitleHeading ? "true" : "false"
        )
    }

    func resetTypography() {
        fontSize = Self.defaultFont
        lineHeight = Self.defaultLine
        paraGap = Self.defaultPara
    }

    func resetAll() {
        resetTypography()
        themeMode = 0
        eyeProtection = false
        fontFamily = "default"
        showFrontmatter = true
        showCitations = true
        hideTitleHeading = true
    }

    private enum K {
        static let font = "font_size"
        static let line = "line_height"
        static let para = "para_gap"
        static let theme = "theme_mode"
        static let mode = "view_mode"
        static let eyeProtection = "eye_protection"
        static let fontFamily = "font_family"
        static let showFrontmatter = "show_frontmatter"
        static let showCitations = "show_citations"
        static let hideTitleHeading = "hide_title_heading"
    }
}
