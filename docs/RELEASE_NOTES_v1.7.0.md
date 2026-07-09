# MD阅读器 v1.7.0 更新日志

## 新增功能

### PDF 文件阅读支持
- 支持打开 `.pdf` 文件，使用 Android PdfRenderer 逐页渲染为高清图片
- 最多支持 50 页，2x 缩放确保文字清晰
- 无需额外库，使用系统原生 API

### 导出长图片
- 在菜单中选择「导出长图片」，将当前渲染好的文档保存为 PNG 长图
- 支持表格、Mermaid 图表、代码高亮等所有渲染效果
- 自动保存到 Download 目录
- 超长文档自动缩放，防止内存溢出

### 导出 HTML
- 在菜单中选择「导出 HTML」，生成独立的 HTML 文件
- 内嵌完整样式表，可在任何浏览器中完美呈现
- 自动保存到 Download 目录

### 历史记录单条删除
- 每条历史记录右侧新增删除按钮（×）
- 可单独清理无效/过期的文件记录，不再只能全部清空

## Bug 修复

### .doc 文档乱码修复
- **问题**：旧版 .doc（OLE2 格式）文档打开后全是乱码
- **原因**：之前使用简单的字节扫描方式提取文本，无法正确解析 OLE2 复合文档格式
- **修复**：引入 Apache POI HWPF 库，正确解析 OLE2 piece table 结构，完美还原中文内容

### DOC/DOCX 内嵌图片查看
- **问题**：文档中嵌入的图片无法查看
- **修复**：DOCX 从 `word/media/` 目录提取图片，DOC 通过 POI PicturesTable 提取，均以 base64 内嵌到 Markdown 中显示

## 优化

- 增强 WebView 渲染稳定性
- 优化大文档的内存管理（PDF 渲染后及时回收 Bitmap）
- 更新欢迎文档，反映新功能

## 技术变更

- 新增依赖：`org.apache.poi:poi:5.2.5`（HWPF，用于 .doc OLE2 解析）
- PDF 渲染使用 Android 原生 `PdfRenderer` API（API 21+，无需额外依赖）
- 导出长图片使用滚动截图拼接方案
- 导出 HTML 使用 WebView evaluateJavascript 获取渲染后的 DOM
