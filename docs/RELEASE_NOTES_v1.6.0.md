# Release Notes — v1.6.0

发布日期：2026-07-04

## 新功能

- **支持 DOCX / DOC / TXT 文档打开**：直接从文件管理器或微信等 App 打开 Word 文档和纯文本，自动转换为可阅读格式
- **图片 & 视频正确加载**：修复了 Vault 内图片无法显示的问题；支持 mp4/webm 视频嵌入
- **`![[doc.md]]` 引用展开**：点击折叠条可内联展开被引用文档的内容
- **任务列表支持**：`- [ ]` / `- [x]` 渲染为可视复选框
- **显示设置新增开关**：
  - 关闭自动识别元数据（Frontmatter）
  - 关闭引用块样式（Blockquote）

## 修复

- **图片加载修复**：vault:// 资源 URL 已改为 WebViewAssetLoader 兼容路径，彻底解决 Vault 图片/视频加载失败问题
- **快速切换文档**：loadDocument 防止竞态，切换时立即清空旧内容，不再停留在上一个文档
- **搜索流畅性**：全库搜索改为异步非阻塞，搜索过程中 UI 保持响应；输入防抖缩短至 150ms
- **卡顿优化**：renderCurrent 防抖 50ms，loadGeneration 保证多次快速点击只处理最后一次

## 技术变更

- `app.js`：vault:// → `https://appassets.androidplatform.net/vault/` 统一通过 WebViewAssetLoader 服务
- `MarkdownBridge`：新增 `searchVaultAsync`、`searchVaultForEmbed` 接口
- `AndroidManifest`：新增 DOCX/DOC/TXT MIME 类型和扩展名 Intent Filter
- `app.css`：新增视频、任务列表、引用展开块、hide-citations 样式
