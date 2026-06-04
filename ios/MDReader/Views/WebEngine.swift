import WebKit
import UIKit

/// 包装 WKWebView：加载本地 viewer.html，提供 push 内容/设置/模式、目录拉取、滚动定位，
/// 并通过 messageHandlers 接收 JS 消息（复制 / 中央点击 / 模式变化 / ready）。
@MainActor
final class WebEngine: NSObject {

    let webView: WKWebView
    var onReady: (() -> Void)?
    var onMessage: (([String: Any]) -> Void)?
    private var loaded = false

    override init() {
        let cfg = WKWebViewConfiguration()
        let ucc = WKUserContentController()
        cfg.userContentController = ucc
        cfg.defaultWebpagePreferences.allowsContentJavaScript = true
        webView = WKWebView(frame: .zero, configuration: cfg)
        super.init()
        ucc.add(WeakBridge(self), name: "bridge")   // 弱引用代理，避免保留环
        webView.navigationDelegate = self
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .clear
        #if DEBUG
        if #available(iOS 16.4, *) { webView.isInspectable = true }
        #endif
    }

    func loadViewer() {
        guard let webDir = Bundle.main.url(forResource: "web", withExtension: nil) else { return }
        let viewer = webDir.appendingPathComponent("viewer.html")
        webView.loadFileURL(viewer, allowingReadAccessTo: webDir)
    }

    func setContent(_ markdown: String) { eval("window.appSetContent(\(jsString(markdown)))") }
    func applySettings(_ json: String) { eval("window.appApplySettings(\(json))") }
    func setMode(_ mode: String) { eval("window.appSetMode('\(mode)')") }
    func scrollTo(_ id: String) { eval("window.appScrollTo('\(escapeSingleQuoted(id))')") }

    func getToc(_ completion: @escaping ([TocItem]) -> Void) {
        guard loaded else { completion([]); return }
        webView.evaluateJavaScript("window.appGetToc()") { result, _ in
            guard let s = result as? String, let data = s.data(using: .utf8),
                  let items = try? JSONDecoder().decode([TocItem].self, from: data) else {
                completion([]); return
            }
            completion(items)
        }
    }

    fileprivate func handle(_ body: Any) {
        if let dict = body as? [String: Any] { onMessage?(dict) }
        else if let s = body as? String { onMessage?(["type": s]) }
    }

    private func eval(_ js: String) {
        guard loaded else { return }
        webView.evaluateJavaScript(js, completionHandler: nil)
    }

    /// 把 Swift 字符串安全编码成 JS 字符串字面量（JSON 字符串即合法 JS 字符串）。
    private func jsString(_ s: String) -> String {
        let data = (try? JSONEncoder().encode(s)) ?? Data("\"\"".utf8)
        return String(data: data, encoding: .utf8) ?? "\"\""
    }

    private func escapeSingleQuoted(_ s: String) -> String {
        s.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "'", with: "\\'")
    }
}

extension WebEngine: WKNavigationDelegate {
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        loaded = true
        onReady?()
    }

    func webView(_ webView: WKWebView,
                 decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = navigationAction.request.url else { decisionHandler(.allow); return }
        if url.isFileURL { decisionHandler(.allow); return }   // 本地 viewer / 资源
        // 外部链接交给系统打开
        if let scheme = url.scheme, ["http", "https", "mailto", "tel"].contains(scheme) {
            UIApplication.shared.open(url)
            decisionHandler(.cancel)
            return
        }
        decisionHandler(.allow)
    }
}

/// 弱引用脚本消息代理，打破 WKUserContentController → handler 的保留环。
private final class WeakBridge: NSObject, WKScriptMessageHandler {
    weak var target: WebEngine?
    init(_ target: WebEngine) { self.target = target }
    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        target?.handle(message.body)
    }
}
