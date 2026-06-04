import SwiftUI
import WebKit

/// 把 WebEngine 持有的 WKWebView 嵌入 SwiftUI。首次创建时加载本地 viewer.html。
struct MarkdownWebView: UIViewRepresentable {
    let engine: WebEngine

    func makeUIView(context: Context) -> WKWebView {
        engine.loadViewer()
        return engine.webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}
}
