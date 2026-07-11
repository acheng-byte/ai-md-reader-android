# 版本记录

## v1.8.0 - 2026-07-12

- 新增：护眼模式 — 暖色羊皮纸背景（`#f5ecd7`），浅色/深色主题下均可开启，减轻长时间阅读的视觉疲劳。
- 新增：字体切换 — 支持默认 / 宋体（Noto Serif CJK SC）/ 等宽三种字体，通过 CSS 变量 `--font-family` 注入。
- 新增：PDF 文件打开与阅读 — 使用 Android PdfRenderer 逐页渲染为图片，支持缩放和翻页。
- 新增：PDF 文件关联 — AndroidManifest 新增 PDF Intent Filter，系统文件管理器可直接用本 App 打开 PDF。
- 新增：导出长图片 — WebView 滚动截图拼接（75% 步进 + 400ms 等待），保存至 `Download/MD阅读器/Picture`（Android 10+ 使用 MediaStore）。
- 新增：导出 HTML — 包含完整样式（代码高亮、Mermaid CDN、Callout）的独立 HTML 文件，保存至 `Download/MD阅读器/HTML`。
- 新增：设置面板新增"检查更新"按钮，手动检查 GitHub Release 是否有新版本，绕过节流并显示 Toast 反馈。
- 新增：设置面板显示当前版本号。
- 修复：历史记录面板单条删除导致 `IndexOutOfBoundsException` 闪退——`HistoryAdapter` 改用 `notifyDataSetChanged()`。
- 修复：导出 HTML 时 `evaluateJavascript` 返回的 JSON 字符串解码损坏——改用 `JSONArray` 安全解码。
- 修复：编辑 DOC 后保存 Markdown 覆盖二进制文件导致损坏——`trySaveInPlace()` 拒绝非 MD/TXT 格式的原位保存。
- 修复：导出文件名被过度清理，中文字符被去除——改为仅移除文件系统非法字符。
- 修复：Android 10+ 导出路径不正确——改用 MediaStore Downloads + `RELATIVE_PATH`。
- 修复：MediaScanner 使用了 FileProvider content:// URI 而非 file:// URI——改用 `Uri.fromFile()`。
- 优化：DOC 图片提取过滤 EMF/WMF 等 WebView 不支持的格式，仅保留 png/jpg/gif/bmp/webp。
- 优化：自动更新检查超时从 8s 延长至 15s，提高弱网环境下的成功率；节流从 24h 缩短至 12h。
- 优化：欢迎页重写，列出所有支持功能及示例。

## v1.7.3 - 2026-07-10

- 修复：`showHistory()` 中 `adapter.removeAt(position)` 和 `entries.removeAt(position)` 操作同一列表导致双删除——移除冗余的 `entries.removeAt()`。
- 修复：MediaScanner 使用 FileProvider 的 content:// URI 导致扫描失败——改用 `Uri.fromFile()`。

## v1.7.2 - 2026-07-10

- 修复：DOC 文件中 EMF/WMF 格式图片无法在 WebView 中显示——`FileUtils` 图片提取过滤非 web 兼容格式。
- 修复：导出 HTML 时 `evaluateJavascript` 返回的 JSON 编码字符串被手动 replace 损坏——改用 `JSONArray("[$raw]").getString(0)` 安全解码。
- 修复：导出文件名正则过于激进，去除中文字符——改为仅移除 `[/\\:*?"<>|]` 等文件系统非法字符。
- 修复：Android 10+ 使用已弃用的 `getExternalStoragePublicDirectory` 导致导出文件不可见——改用 `getExternalFilesDir`。
- 修复：编辑 DOC/DOCX/PDF 后保存 Markdown 覆盖二进制文件导致损坏——`trySaveInPlace()` 检测非 MD/TXT 格式时拒绝原位保存，强制使用"另存为"。

## v1.7.1 - 2026-07-09

- 修复：编译失败——新增缺失的 `poi-scratchpad:4.1.2` 依赖，`HWPFDocument` 需位于 scratchpad 模块而非 poi 核心模块。
- 修复：`.doc` 文件图片提取调用了不存在的 `suggestedFileExtension`，改为正确的 `suggestFileExtension()`。
- 修复：导出长图片在主线程调用 `Thread.sleep` 阻塞 UI，导致滚动截图始终重复第一屏；改为后台线程 + `CountDownLatch` + `postDelayed` 方案。
- 修复：Android 8/9 设备导出图片或 HTML 时未请求 `WRITE_EXTERNAL_STORAGE` 运行时权限。
- 新增：DOCX 表格渲染支持——解析 `w:tbl / w:tr / w:tc` 并转为 Markdown 表格格式。

## v1.6.4 - 2026-07-05

- 新增：启动时自动恢复上次阅读的文档，无需重新手动打开；首次安装（无历史）才显示欢迎页。

## v1.6.3 - 2026-07-05

- 修复：Markdown 文件单个换行符不生效，需要空行才能换段——开启 `breaks: true`，单个回车即可换行。
- 修复：DOCX 文档所有段落被误识别为标题（全部显示 `#` / `##`）——`parseWordXml` 移除错误的数字样式 ID 匹配（`"1"/"2"`），改为精确匹配 `Heading1`/`Heading2`/`标题1`/`标题2` 等标准样式名；同时对非标题段落首字符 `#` 进行转义，防止 Markdown 渲染误判。
- 优化：TXT 文件不再用代码块包裹，改为转义 Markdown 特殊字符后直接渲染为普通段落，文本自动折行，告别横向滚动。

## v1.6.2 - 2026-07-05

- 修复：`[[文件夹/文件名]]` 路径式 Wikilink 点击提示"找不到文件"——`VaultSearch.findFile` 现支持多级路径导航，失败后回退到全库文件名搜索，同时支持 `[[File#Heading]]` 格式（自动去除锚点后缀）。
- 修复：目录面板中标题前多余的 `#` 符号——`buildToc` 对 `textContent` 去除前导 `#`。
- 修复：目录项点击无响应——改为 `href='#'` + `e.preventDefault()`，通过 `getElementById` 重新查找标题，消除闭包引用陈旧问题。
- 修复：中文 TXT 文件（GBK / GB2312）打开乱码——`FileUtils.decodeText` 先 UTF-8 解码，若替换字符比例超过 1% 则自动回退 GBK 重新解码。
- 新增：冷启动且无外部文件意图时，若打开历史非空，自动弹出历史面板。
- 新增：`==高亮==` Obsidian 语法，渲染为黄色背景 `<mark>`，深色模式自动切换。
- 新增：`#标签` 渲染为圆角标签胶囊样式（蓝色），不影响标题层级。
- 新增：`%%注释%%` Obsidian 注释语法在渲染时完全隐藏。
- 新增：脚注 `[^1]` 支持，行内渲染为上标链接，文末自动生成脚注列表，支持反向跳回。
- 新增：`[[#标题]]` 内部锚点链接，生成页内跳转。

## v1.6.0 - 2026-07-04

- 新增：支持 DOCX / DOC / TXT 文档打开，自动转换为可阅读格式。
- 新增：修复 Vault 内图片无法显示，支持 mp4/webm 视频嵌入。
- 新增：`![[doc.md]]` 引用展开，点击折叠条可内联展开被引用文档内容。
- 新增：`- [ ]` / `- [x]` 任务列表渲染为可视复选框。
- 新增：显示设置新增"关闭自动识别 Frontmatter"和"关闭引用块样式"开关。
- 新增：全库异步搜索，搜索过程中 UI 保持响应，防抖缩短至 150ms。
- 新增：自动更新检查（`UpdateChecker`）。
- 修复：vault:// 资源改为 WebViewAssetLoader 兼容路径，彻底解决图片/视频加载失败。
- 修复：切换文档时立即清空旧内容，防止停留在上一文档。

## v1.4.1 - 2026-06-06

- 修复：已收藏的文档在「打开历史」中不再误报「授权过期」。收藏即已复制到本地副本，该历史记录直接指向收藏夹副本，点击静默打开、不再弹「从收藏夹打开」提示；仅当取消收藏（副本被删除）后才恢复为「从来源读取」并提示授权过期。
- 新增：记住上次阅读位置。再次打开文档（含从历史/收藏夹重新打开）时自动定位到上次阅读处；按滚动比例记忆，调整字号/行距后仍能大致对位。
- 本次为 Android / 支持 APK 安装的鸿蒙设备更新；iOS 版暂不包含上述改动。

## v1.4 - 2026-06-05

- 新增「转发」：阅读时一键将完整 `.md` 文档通过系统分享面板转发到微信等社交应用（如发送给微信好友）；转发的是文档文件本身，接收方得到可再次打开的 `.md`。
- 转发入口常显在工具栏；经 FileProvider 以临时授权安全共享，应用本身不申请额外权限。
- 说明：受系统能力限制，App 只能把文件交给微信，最终由微信自身的「发送给朋友」界面选择联系人。
- 本次为 Android / 支持 APK 安装的鸿蒙设备更新；iOS 版暂不包含该功能。

## v1.3 - 2026-06-04

- 收藏 / 取消收藏移至工具栏常显，使用矢量星形图标（实心表示已收藏）。
- 工具栏常显项调整为：目录、源码 / 预览切换、收藏切换、更多菜单；“打开”移入更多菜单。
- 显示设置改为点击屏幕中央区域唤出（电子书阅读器常见手势），不再占用右上角菜单。
- 优化标题折叠箭头：内联到标题文字前（不再悬挂到最左、避免曲面屏裁切），并放大、改用主题色。
- 新增 iOS v1.3 源码：SwiftUI 原生外壳 + WKWebView Markdown 正文，功能对齐 Android v1.3；已通过 iPhone 17 模拟器构建、安装、启动、浅色 / 深色首屏冒烟测试。

## v1.2 - 2026-06-04

- 预览模式代码块新增一键复制按钮。
- 打开文件时仅允许选择 `.md` / `.markdown`，选择其他类型会友好提示。
- 打开历史区分「授权过期」（如微信临时授权失效）与「已删除」（文件被物理删除）两种状态。
- 新增收藏夹：收藏会把文档复制到应用收藏目录，原文件被删除或授权过期后仍可打开；同一文件不重复复制，取消收藏会同步删除副本。

## v1.1 - 2026-06-04

- 新增文档目录，支持点击标题快速跳转。
- 新增标题折叠与展开，适合阅读长文档。
- 新增打开历史，支持失效文件友好提示。
- 优化预览模式下的阅读排版设置。
- 保持 markdown-it 与 highlight.js 本地打包，支持离线阅读。

## v1.0 - 2026-06-04

- 首个可安装版本。
- 支持本地 Markdown 打开、源码 / 预览切换。
- 支持从微信等应用通过“用其他应用打开”进入。
- 支持字号、行间距、段间距和明暗主题设置。
