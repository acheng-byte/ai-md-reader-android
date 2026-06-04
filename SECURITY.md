# 安全说明

如果你发现安全问题，请不要在公开 issue 中粘贴敏感 Markdown 文件、私有链接、访问令牌、客户材料或个人信息。

建议先提交一个不含敏感内容的 issue，说明问题类别和影响范围；如需提供复现样例，请尽量使用脱敏文件。

当前安全设计：

- 默认不渲染 Markdown 中内嵌的原始 HTML。
- WebView 禁用 file access 与 content access，应用资源通过 WebViewAssetLoader 提供。
- Markdown 渲染资源打包在 APK 内，常规阅读无需联网。
- 签名材料和本地构建产物不进入开源仓库。
