import SwiftUI
import UIKit

struct TocItem: Codable, Identifiable {
    let level: Int
    let text: String
    let id: String
}

/// 阅读器主状态与逻辑（单一数据源）。驱动 WebEngine 渲染，管理打开/历史/收藏/设置/目录。
@MainActor
final class ReaderModel: ObservableObject {

    let prefs = Prefs()
    let engine = WebEngine()
    private let history = HistoryStore()
    private let favorites = FavoritesStore()

    @Published var currentTitle: String = "MD阅读器"
    @Published var currentMode: String = "preview"      // preview / code
    @Published var isCurrentFavorite: Bool = false

    @Published var showSettings = false
    @Published var showOutline = false
    @Published var showHistory = false
    @Published var showFavorites = false
    @Published var toc: [TocItem] = []
    @Published var toastText: String?

    /// 源码模式编辑内容
    @Published var sourceText: String = ""

    var systemDark: Bool = false

    private var currentMarkdown: String = ""
    private var currentIdentity: String?
    private var currentBookmark: Data?
    private var pageReady = false
    private var sourceSaveWork: DispatchWorkItem?

    init() {
        currentMode = prefs.viewMode
        engine.onReady = { [weak self] in self?.onWebReady() }
        engine.onMessage = { [weak self] body in self?.handleWebMessage(body) }
        // 初始为欢迎内容；打开文件后替换
        currentMarkdown = Self.welcomeMarkdown
        currentTitle = "MD阅读器"
    }

    // MARK: - 渲染就绪

    private func onWebReady() {
        pageReady = true
        engine.setTitle(currentTitle)
        engine.applySettings(prefs.settingsJSON(systemDark: systemDark))
        engine.setMode(currentMode)
        engine.setScrollRatio(0)
        engine.setContent(currentMarkdown)
    }

    func applySettingsToWeb() {
        engine.applySettings(prefs.settingsJSON(systemDark: systemDark))
    }

    func updateSystemDark(_ dark: Bool) {
        systemDark = dark
        if prefs.themeMode == 0 { applySettingsToWeb() }   // 跟随系统时同步
    }

    // MARK: - 打开文档

    /// 应用内文件选择器打开（支持 md/markdown/txt/doc/docx/pdf）
    func openPicked(_ url: URL) { openExternal(url, enforceMarkdown: false) }

    /// 其他应用"用其他应用打开 / 拷贝到"传入
    func openIncoming(_ url: URL) { openExternal(url, enforceMarkdown: false) }

    private func openExternal(_ url: URL, enforceMarkdown: Bool) {
        let name = DocumentService.displayName(for: url)
        guard let text = DocumentService.readText(at: url) else {
            showToast("打开失败：无法读取文件")
            return
        }
        let bookmark = DocAccess.makeBookmark(for: url)
        display(markdown: text, title: name, identity: url.absoluteString, bookmark: bookmark)
    }

    private func display(markdown: String, title: String, identity: String?, bookmark: Data?) {
        // 退出源码模式
        if currentMode == "code" {
            currentMode = "preview"
            prefs.viewMode = currentMode
        }
        currentMarkdown = markdown
        currentTitle = title
        currentIdentity = identity
        currentBookmark = bookmark
        isCurrentFavorite = identity.map { favorites.isFavorite($0) } ?? false
        if let id = identity {
            history.add(identity: id, name: title, bookmark: bookmark)
        }
        engine.setTitle(title)
        engine.setContent(markdown)   // 未就绪时由 onWebReady 统一推送 currentMarkdown
    }

    // MARK: - 历史

    func historyEntries() -> [HistoryEntry] { history.all() }

    func status(of entry: HistoryEntry) -> DocStatus {
        DocAccess.status(bookmark: entry.bookmark, identity: entry.identity)
    }

    func isFavorited(_ identity: String) -> Bool { favorites.isFavorite(identity) }

    func openFromHistory(_ entry: HistoryEntry) {
        let st = DocAccess.status(bookmark: entry.bookmark, identity: entry.identity)
        if st == .available {
            let url = DocAccess.resolve(bookmark: entry.bookmark) ?? URL(string: entry.identity)
            if let url, let text = DocumentService.readText(at: url) {
                display(markdown: text, title: entry.name, identity: entry.identity, bookmark: entry.bookmark)
                return
            }
        }
        // 不可用：因已收藏则从收藏副本打开，否则按状态友好提示
        if let fav = favorites.find(entry.identity) {
            showToast("原文件已不可用，已从收藏夹打开")
            openFavorite(fav)
        } else {
            showToast(st == .expired ? "打开授权已过期，请重新从来源打开" : "原文件已被删除，无法打开")
        }
    }

    func clearHistory() { history.clear() }

    // MARK: - 收藏

    func favoriteItems() -> [FavoriteItem] { favorites.all() }

    func openFavorite(_ item: FavoriteItem) {
        let url = favorites.fileURL(item)
        guard let text = DocumentService.readText(at: url) else {
            showToast("打开失败：收藏副本不存在")
            return
        }
        display(markdown: text, title: item.name, identity: item.identity, bookmark: item.bookmark)
    }

    func toggleFavorite() {
        guard let id = currentIdentity else {
            showToast("请先打开一个文档再收藏")
            return
        }
        if favorites.isFavorite(id) {
            favorites.remove(id)
            isCurrentFavorite = false
            showToast("已取消收藏")
        } else {
            let ok = favorites.add(identity: id, name: currentTitle,
                                   content: currentMarkdown, bookmark: currentBookmark) != nil
            isCurrentFavorite = ok
            showToast(ok ? "已收藏，并复制到收藏夹" : "收藏失败")
        }
    }

    func removeFavorite(_ identity: String) {
        favorites.remove(identity)
        if currentIdentity == identity { isCurrentFavorite = false }
    }

    // MARK: - 模式 / 目录

    func toggleMode() {
        if currentMode == "preview" {
            currentMode = "code"
            sourceText = currentMarkdown
        } else {
            // 从源码切回预览：同步编辑内容
            currentMarkdown = sourceText
            currentMode = "preview"
        }
        prefs.viewMode = currentMode
        engine.setMode(currentMode)
        if currentMode == "preview" {
            engine.setContent(currentMarkdown)
        }
    }

    /// 源码模式内容变更时调用（防抖自动保存）
    func sourceTextChanged() {
        sourceSaveWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            self.currentMarkdown = self.sourceText
            // 尝试原位保存（仅对 md/txt）
            if let id = self.currentIdentity,
               let url = URL(string: id),
               id.hasSuffix(".md") || id.hasSuffix(".markdown") || id.hasSuffix(".txt") {
                try? self.sourceText.write(to: url, atomically: true, encoding: .utf8)
            }
        }
        sourceSaveWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.0, execute: work)
    }

    func requestOutline() {
        engine.getToc { [weak self] items in
            guard let self else { return }
            self.toc = items
            self.showOutline = true
        }
    }

    func scrollTo(_ id: String) { engine.scrollTo(id) }

    // MARK: - Web 消息

    private func handleWebMessage(_ body: [String: Any]) {
        guard let type = body["type"] as? String else { return }
        switch type {
        case "copy":
            if let text = body["text"] as? String { UIPasteboard.general.string = text }
        case "centerTap":
            if currentMode == "preview" { showSettings = true }
        case "modeChanged":
            if let m = body["mode"] as? String {
                currentMode = m
                prefs.viewMode = m
            }
        case "saveScroll":
            // 阅读进度由 iOS 原生管理（此处可扩展）
            break
        case "saveElement", "saveMermaid":
            // iOS 暂不支持原生 WebView 截图导出，提示用户
            showToast("图表/表格导出功能仅 Android 版支持")
        case "openWiki":
            if let name = body["name"] as? String {
                showToast("Wikilink: \(name)（Vault 功能待实现）")
            }
        case "openVault":
            showToast("Vault 功能待实现")
        case "searchVault":
            // 返回空结果
            break
        case "ready":
            // 页面就绪：推送设置和内容
            onWebReady()
        default:
            break
        }
    }

    // MARK: - Toast

    private var toastWork: DispatchWorkItem?
    func showToast(_ text: String) {
        toastText = text
        toastWork?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.toastText = nil }
        toastWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.8, execute: work)
    }

    // MARK: - 清理

    func cleanup() {
        sourceSaveWork?.cancel()
    }

    // MARK: - 欢迎内容

    static let welcomeMarkdown = """
    # 👋 欢迎使用 MD阅读器

    这是一个本地 **Markdown 阅读器**（iOS v1.9.4）。支持多种格式和 Obsidian 语法。

    ## 怎么用

    - 顶部 **⋯ → 打开**，选择 `.md` / `.txt` / `.doc` / `.docx` / `.pdf` 文件
    - **目录** 按钮唤出大纲，点击标题快速跳转
    - **源码 / 预览** 切换；源码模式可直接编辑
    - **点击屏幕中央** 调出「显示设置」（字号 / 行距 / 段距 / 主题 / 护眼 / 字体）
    - 顶部 **星标** 收藏当前文档；**⋯** 里可打开 **收藏夹** 与 **打开历史**
    - 在别的 App（如微信）里对 `.md` 选择「用其他应用打开 / 拷贝到 MD阅读器」即可送入阅读

    ## 语法示例

    ### 列表与引用

    1. 有序列表项一
    2. 有序列表项二
       - 嵌套无序项

    > 这是一段引用文字（blockquote）。

    ### 表格

    | 功能 | 是否支持 |
    | --- | :---: |
    | 标题 / 列表 | ✅ |
    | 表格 | ✅ |
    | 代码高亮 | ✅ |
    | Mermaid 图表 | ✅ |
    | Obsidian 语法 | ✅ |
    | 护眼模式 | ✅ |

    ### 代码

    行内代码 `let x = 1`，以及代码块（右上角可一键复制）：

    ```swift
    import SwiftUI

    @main
    struct MDReaderApp: App {
        var body: some Scene { WindowGroup { ReaderView() } }
    }
    ```

    ### Obsidian 语法

    - `==高亮文字==` → 黄色背景高亮
    - `#标签` → 胶囊样式标签
    - `%%注释%%` → 隐藏内容
    - `> [!NOTE]` → Callout 标注
    - `[^1]` → 脚注

    ### Mermaid 图表

    ```mermaid
    graph LR
        A[开始] --> B{条件}
        B -->|是| C[执行]
        B -->|否| D[跳过]
        C --> E[结束]
        D --> E
    ```

    ---

    打开一个文件开始阅读吧 📖
    """
}
