# 默认未开启代码压缩(isMinifyEnabled=false)，以下规则在开启时生效。
# 保留 WebView 的 JS 桥接口，防止被 R8 删除/重命名。
-keepclassmembers class com.mdreader.app.MarkdownBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
