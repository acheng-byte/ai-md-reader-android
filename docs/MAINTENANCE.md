# AI MD Reader Android — 维护与开发手册

> 版本：v2.1.7 | 更新日期：2026-07-12
> 仓库：https://github.com/acheng-byte/ai-md-reader-android

---

## 目录

1. [项目概览](#1-项目概览)
2. [目录结构](#2-目录结构)
3. [当前支持的功能清单](#3-当前支持的功能清单)
4. [核心文件职责说明](#4-核心文件职责说明)
5. [修改指南：改什么功能 → 动哪个文件](#5-修改指南)
6. [JS 桥接层说明](#6-js-桥接层说明)
7. [构建与发布流程](#7-构建与发布流程)
8. [已知问题与注意事项](#8-已知问题与注意事项)
9. [未来开发建议](#9-未来开发建议)

---

## 1. 项目概览

这是一个 Android 本地 Markdown 阅读器，核心技术栈：

- **前端渲染**：WebView + markdown-it（JS）+ highlight.js + Mermaid
- **原生层**：Kotlin + AndroidX + Material Components
- **文件访问**：Storage Access Framework (SAF)，无需危险权限
- **文档解析**：Apache POI 4.1.2（HWPF for .doc）、Android PdfRenderer（PDF）
- **导出**：MediaStore API（Android 10+）/ File API（Android 9 及以下）

**设计哲学**：所有渲染逻辑放在 Web 层（JS/CSS/HTML），原生层只做文件读写、系统调用和 UI 壳；两层通过 `MarkdownBridge` 互相通信。

---

## 2. 目录结构

```
ai-md-reader-android/
├── app/
│   ├── build.gradle.kts                  # 版本号、依赖、签名配置
│   └── src/main/
│       ├── AndroidManifest.xml           # 权限、Intent Filter（md/txt/docx/doc/pdf）
│       ├── assets/                       # WebView 加载的前端资源
│       │   ├── viewer.html               # 入口 HTML
│       │   ├── app.js                    # 全部渲染逻辑（核心）
│       │   ├── app.css                   # 全部样式（含护眼模式、字体变量）
│       │   └── lib/
│       │       ├── markdown-it.min.js    # Markdown 解析器
│       │       ├── highlight.min.js      # 代码高亮
│       │       └── mermaid.min.js        # Mermaid 图表
│       ├── java/com/mdreader/app/
│       │   ├── MainActivity.kt           # 主 Activity（含导出、设置、更新检查）
│       │   ├── MarkdownBridge.kt         # JS↔原生桥接接口定义
│       │   ├── Prefs.kt                  # SharedPreferences 封装
│       │   ├── FileUtils.kt              # 文件读取（md/txt/docx/doc，含编码检测）
│       │   ├── VaultSearch.kt            # Vault 搜索（异步）
│       │   ├── UpdateChecker.kt          # GitHub Releases 更新检查
│       │   ├── History.kt / HistoryAdapter.kt
│       │   ├── Favorites.kt / FavoritesAdapter.kt
│       │   ├── ReadingProgress.kt        # 阅读进度持久化
│       │   └── DocStatus.kt              # 文档状态枚举
│       └── res/
│           ├── layout/
│           │   ├── activity_main.xml
│           │   ├── sheet_settings.xml    # 含护眼模式、字体、检查更新
│           │   ├── sheet_history.xml
│           │   └── sheet_favorites.xml
│           ├── menu/menu_main.xml
│           └── values/strings.xml
├── .github/workflows/
│   ├── android-ci.yml                    # push/PR 自动构建
│   └── release.yml                       # tag push 自动发布 Release
└── docs/
    ├── MAINTENANCE.md                    # 本文件
    └── RELEASE_NOTES_v*.md
```

---

## 3. 当前支持的功能清单

### 3.1 Markdown 渲染

| 功能 | 状态 | 说明 |
|---|:---:|---|
| 标题 H1–H6 | ✅ | 含折叠/展开交互 |
| 有序/无序列表 | ✅ | 多级嵌套 |
| 表格 | ✅ | 自动横向滚动 |
| 代码块（带高亮）| ✅ | highlight.js，100+ 语言，右上角复制按钮 |
| 任务列表 `- [ ]` | ✅ | 渲染为复选框 |
| 引用块 | ✅ | 可通过设置关闭样式 |
| 图片 | ✅ | 远程 + Vault 本地图片 |
| 视频嵌入 | ✅ | Vault 内 mp4/webm 等 |
| HTML 渲染 | ✅ | `html: true` |
| Mermaid 图表 | ✅ | 离线可用 |
| YAML Frontmatter | ✅ | 可通过设置关闭 |
| Obsidian Wikilink `[[Page]]` | ✅ | 需设置 Vault |
| Obsidian 图片嵌入 `![[img.png]]` | ✅ | 通过 WebViewAssetLoader |
| 引用文档展开 `![[doc.md]]` | ✅ | 点击折叠条内联展开 |
| `==高亮==` | ✅ | Obsidian 语法 |
| `#标签` | ✅ | 胶囊样式标签 |
| `%%注释%%` | ✅ | 自动隐藏 |
| 脚注 `[^1]` | ✅ | 上标链接 + 文末列表 |
| `[[#标题]]` | ✅ | 页内锚点跳转 |
| Callout `> [!NOTE]` | ✅ | 多种类型支持 |
| 数学公式 KaTeX/MathJax | ❌ | 待支持 |

### 3.2 文件格式

| 格式 | 状态 | 说明 |
|---|:---:|---|
| .md / .markdown | ✅ | 完整支持 |
| .txt | ✅ | UTF-8 / GBK / GB18030 自动检测 |
| .docx | ✅ | XML 解析，保留标题层级和表格 |
| .doc | ✅ | Apache POI HWPF 提取文本和图片 |
| .pdf | ✅ | Android PdfRenderer 逐页渲染为图片 |

### 3.3 阅读体验

| 功能 | 状态 |
|---|:---:|
| 目录（TOC）侧滑面板 | ✅ |
| 标题折叠/展开 | ✅ |
| 文内搜索（默认）| ✅ |
| 全库搜索（Vault，异步）| ✅ |
| 字号/行距/段距调节 | ✅ |
| 主题浅色/深色/系统 | ✅ |
| 护眼模式 | ✅ |
| 字体切换（9种：默认/宋体/等宽/黑体/楷体/仿宋/小标宋/隶书/微软雅黑） | ✅ |
| 字符统计（点击标题栏） | ✅ |
| 阅读进度记忆（断点续读） | ✅ |
| Frontmatter 开关 | ✅ |
| 引用块样式开关 | ✅ |
| 源码模式编辑（已合并到源码视图） | ✅ |
| 表格/图表单击预览（全屏+双指缩放） | ✅ |
| 图表/表格下载为PNG | ✅ |
| 源码模式直接编辑+自动保存 | ✅ |
| 阅读标注（多色多模式） | ✅ |
| 设置持久化（备份规则） | ✅ |
| 隐藏文件名标题 | ✅ |
| 打开历史扩容到200条 | ✅ |

### 3.4 导出与分享

| 功能 | 状态 |
|---|:---:|
| 导出长图片 | ✅ |
| 导出 HTML（含完整样式）| ✅ |
| 转发分享 | ✅ |
| Mermaid图表下载为PNG | ✅ |
| 表格下载为PNG | ✅ |

### 3.5 系统集成

| 功能 | 状态 |
|---|:---:|
| 自动检查更新（12h 节流）| ✅ |
| 手动检查更新按钮 | ✅ |
| 发现新版本弹窗 + 直接下载安装 | ✅ |
| PDF 文件关联 | ✅ |
| CI 自动构建 | ✅ |
| 自动发布 Release APK | ✅ |

---

## 4. 核心文件职责说明

### `app.js`

**最核心的文件**，所有 Markdown 渲染逻辑都在这里：

```
app.js 职责：
├── markdown-it 初始化 + 任务列表规则注入
├── ==高亮== / #标签 / %%注释%% / 脚注 预处理
├── parseFrontmatter()       — 解析 YAML front matter
├── preprocessWikilinks()    — 替换 [[Page]]、![[img]]、![[doc]]
├── preprocessInternalLinks() — [[#标题]] 锚点
├── preprocessImages()       — 图片路径 → Vault URL
├── renderFrontmatter()      — 渲染 frontmatter 为 HTML 表格
├── postprocessCallouts()    — blockquote → Callout 样式
├── initMermaid()            — 初始化 Mermaid
├── render()                 — 主渲染
├── 折叠/展开逻辑
├── 目录构建
├── 搜索（文内高亮 + 全库异步搜索）
├── window._toggleEmbed()    — 引用文档展开
├── applySettings()          — CSS 变量注入 + 护眼/字体/frontmatter/citations
├── 表格/图表预览覆盖层（setupTableInteractions / openMermaidPreview）
├── pinch zoom（双指缩放预览）
├── showDownloadConfirm()    — 下载确认弹窗
├── inlineSvgStyles()        — Mermaid SVG 计算样式内联（修复保存样式丢失）
├── countChars() / showCharCount() — 字符统计（去除 MD 语法/HTML/标点）
├── setupCenterTap()         — 中心点击（排除交互元素 + 防抖）
├── 渲染缓存（缓存已渲染结果，加速重复打开）
└── 暴露给原生的 API
```

### `app.css`

所有样式，关键 CSS 变量：

```
:root {
    --font-size / --line-height / --para-gap / --max-width
    --font-family（由设置注入）
    --bg / --fg / --muted / --border / --code-bg / --link 等
}
body.dark { ... }
body.eye-protection { ... }  /* 暖色羊皮纸 */
```

### `MainActivity.kt`

- `loadDocument()` — 异步读文件，loadGeneration 防竞态
- `renderCurrent()` — 50ms 防抖
- `showSettings()` — 设置面板（字号/行距/段距/主题/护眼/字体/Vault/检查更新）
- `exportLongImage()` — WebView 滚动截图拼接
- `exportHtml()` — 导出完整 HTML
- `saveImageToGallery()` / `saveHtmlToGallery()` — MediaStore 保存
- `checkForUpdates()` / `manualCheckForUpdates()` — 自动/手动更新检查
- `saveElementImage()` — 保存表格/图表元素为 PNG
- `toggleMode()` — 源码编辑模式切换
- `debouncedApplySettings()` — 防抖应用设置（避免频繁写入）
- 标注系统（annotation system）— 管理阅读标注的创建/保存/加载
- `WELCOME_MD` — 欢迎页内容

### `AnnotationOverlay.kt`

- 阅读标注覆盖层，支持多色、多模式标注
- 管理标注的创建、显示、隐藏和持久化
- 与 WebView 渲染层交互作，定位标注在文档中的位置

### `MarkdownBridge.kt`

- JS ↔ 原生桥接接口定义
- `saveElementImage(type, html)` — 保存表格/图表为 PNG
- `saveMermaidImage(svgHtml)` — 保存 Mermaid SVG 为 PNG
- `getTitle()` — 获取当前文档标题
- `showCharCount(stats)` — 接收 JS 端字符统计结果并展示

### `UpdateChecker.kt`

- `checkLatest()` — 请求 GitHub Releases API（15s 超时）
- `isNewer()` — 版本号比较（去 v 前缀，数字段比较）

---

## 5. 修改指南

### 5.1 渲染相关

| 要改的功能 | 文件 | 位置 |
|---|---|---|
| Markdown 语法支持 | `app.js` | markdown-it 初始化 + core rules |
| Vault 图片/资源路径 | `app.js` | `VAULT_BASE` 常量 + `preprocessWikilinks()` |
| Frontmatter 开关效果 | `app.js` | `render()` 中 `showFm` 检查 |
| 引用块开关 | `app.css` | `.hide-citations blockquote` |
| 护眼模式颜色 | `app.css` | `body.eye-protection` |
| 字体映射 | `app.js` | `applySettings()` 中 `fontMap` |
| Callout 类型/图标 | `app.js` | `calloutIcon()` |
| 表格/图表预览 | `app.js` | `setupTableInteractions()` / `openMermaidPreview()` |
| 下载确认 | `app.js` | `showDownloadConfirm()` |

### 5.2 搜索相关

| 要改的功能 | 文件 | 位置 |
|---|---|---|
| 文内搜索逻辑 | `app.js` | `doSearch()` |
| 全库搜索异步回调 | `MainActivity.kt` | `searchVaultAsync()` |
| JS 接收搜索结果 | `app.js` | `window.appVaultSearchResult` |
| 搜索模式切换 | `app.js` | `search-vault-btn` onclick |

### 5.3 导出相关

| 要改的功能 | 文件 | 位置 |
|---|---|---|
| 长图片拼接逻辑 | `MainActivity.kt` | `exportLongImage()` |
| HTML 导出模板 | `MainActivity.kt` | `exportHtml()` + `EXPORT_CSS` |
| 保存路径 | `MainActivity.kt` | `saveImageToGallery()` / `saveHtmlToGallery()` |
| Android 9 回退路径 | `MainActivity.kt` | `getExportDir()` |

### 5.4 文件格式

| 要改的功能 | 文件 | 位置 |
|---|---|---|
| 添加新格式支持 | `FileUtils.kt` | `readText()` + 新解析函数 |
| Intent Filter | `AndroidManifest.xml` | 添加 MIME 或扩展名 |
| 文件类型检查 | `MainActivity.kt` | `isAllowedFile()` |

### 5.5 发版相关

| 要改的功能 | 文件 | 位置 |
|---|---|---|
| 修改版本号 | `app/build.gradle.kts` | `versionCode` + `versionName` |
| 修改更新检查仓库 | `UpdateChecker.kt` | `RELEASES_API` 常量 |
| 自动更新节流时间 | `MainActivity.kt` | `checkForUpdates()` 中的 `TimeUnit.HOURS` |

### 5.6 标注相关

| 要改的功能 | 文件 | 位置 |
|---|---|---|
| 标注覆盖层逻辑 | `AnnotationOverlay.kt` | 标注创建/显示/隐藏 |
| 标注模式切换 | `MainActivity.kt` | `toggleAnnotationMode()` |
| 标注持久化 | `AnnotationOverlay.kt` | 本地存储读写 |

### 5.7 源码编辑

| 要改的功能 | 文件 | 位置 |
|---|---|---|
| 源码模式切换 | `MainActivity.kt` | `toggleMode()` |
| 源码内容同步 | `app.js` | `syncSourceContent()` |
| 源码自动保存 | `app.js` | `setupSourceAutoSave()` |

---

## 6. JS 桥接层说明

### 原生 → JS

```kotlin
js("window.appRender && window.appRender()")               // 触发重渲染
js("window.appSetMode && window.appSetMode('preview')")    // 切换模式
js("window.appApplySettings && window.appApplySettings($json)") // 注入设置
js("window.appToggleToc && window.appToggleToc()")         // 切换目录
js("window.appOpenSearch && window.appOpenSearch()")        // 打开搜索
js("window.appRestoreScroll && window.appRestoreScroll()")  // 恢复进度
// 异步搜索结果回调
js("window.appVaultSearchResult && window.appVaultSearchResult('$id', '$json')")
```

### JS → 原生（`window.Android.方法名()`）

| 方法 | 说明 |
|---|---|
| `getMarkdown()` | 获取当前 MD 文本 |
| `getSettingsJson()` | 获取设置 JSON |
| `searchVaultAsync(q, cbId)` | 异步全库搜索 |
| `searchVaultForEmbed(ref)` | 查找嵌入文件 URI |
| `loadEmbedContent(uri)` | 加载嵌入文件内容 |
| `openVaultFile(uri)` | 打开 Vault 文件 |
| `openWikiLink(name)` | 导航到 Wikilink |
| `saveMermaidImage(svgHtml)` | 保存 Mermaid SVG 为 PNG |
| `saveElementImage(type, html)` | 保存表格/图表为 PNG |
| `getTitle()` | 获取文档标题 |
| `showCharCount(stats)` | 显示字符统计结果 |

---

## 7. 构建与发布流程

### 发布新版本

```bash
# 1. 修改版本号
# app/build.gradle.kts: versionCode++ & versionName = "x.y.z"

# 2. 提交 + 推送
git add -A
git commit -m "feat: vX.Y.Z - 功能描述"
git push origin main

# 3. 打 tag（自动触发 release.yml → 构建 APK → 创建 GitHub Release）
git tag vX.Y.Z
git push origin vX.Y.Z
```

### CI/CD

- **android-ci.yml**：push/PR → 构建 debug + release APK，上传 Artifacts
- **release.yml**：tag `v*` → 构建 release APK → 创建 GitHub Release，附 APK 下载
- 签名：keystore 存在 repo secrets 中，CI 构建签名一致

---

## 8. 已知问题与注意事项

### 8.1 .doc 解析有限

旧版 `.doc`（OLE2 格式）通过 Apache POI HWPF 提取文本和图片，内容可能不完整，建议用户转换为 `.docx`。

### 8.2 PDF 渲染为图片

PDF 使用 PdfRenderer 逐页渲染为位图，不支持文字选择/搜索。内存占用较高，大文件可能卡顿。

### 8.3 Vault 图片相对路径

`resolveRelativeAsset()` 先在当前文档目录查找，再全库搜索文件名。同名文件在不同目录可能拿错。

### 8.4 SAF DocumentFile 性能

大型 Vault（数百文件）全库搜索仍有延迟（已改为异步，不阻塞 UI，但无进度提示）。

### 8.5 `@JavascriptInterface` 混淆

已关闭 minify，未来若开启需在 ProGuard 保留 `MarkdownBridge` 类。

### 8.6 微信临时 URI 授权

从微信打开的 content:// URI 在 App 杀死后失效，属 Android 系统限制。

### 8.7 自动更新检查

GitHub API 在中国大陆访问可能较慢，已将超时延长至 15s。如果仍然失败，用户可通过设置中的"检查更新"按钮手动检查。

---

## 9. 未来开发建议

- **数学公式**：引入 KaTeX（轻量）
- **全库搜索索引**：Room + SQLite FTS 提速 10x+
- **ViewModel 重构**：将 MainActivity 业务逻辑迁移到 ViewModel
- **PDF 文字选择**：使用 PdfRenderer 的文本层实现选择/搜索
- **iOS 资源同步**：CI 中 rsync `assets/` → `ios/MDReader/Resources/web/`

---

## 版本变更历史

| 版本 | 主要变更 |
|---|---|
| v1.0 | 基础 Markdown 渲染 |
| v1.1 | 代码高亮、TOC、标题折叠 |
| v1.2 | 代码块复制、收藏夹 |
| v1.3 | 阅读进度记忆、设置中央唤出 |
| v1.4 | 转发分享 |
| v1.4.1 | 阅读位置记忆修复 |
| v1.5.1 | Wikilink、Mermaid、Frontmatter、全文搜索、Vault、自动更新 |
| v1.6.0 | DOCX/TXT 支持、图片修复、搜索异步化、任务列表、引用展开 |
| v1.6.2 | Obsidian 兼容（高亮/标签/注释/脚注） |
| v1.6.4 | 启动自动恢复上次阅读 |
| v1.7.1 | 编译修复、DOC 图片、导出长图、DOCX 表格 |
| v1.7.2 | DOC 图片过滤、导出修复、编辑保护 |
| v1.7.3 | 历史面板双删除修复 |
| **v1.8.0** | 护眼模式、字体切换、PDF 支持、导出长图/HTML、检查更新按钮 |
| v1.9.0 | 阅读标注、HTML导出修复 |
| v1.9.1 | 全面性能优化（防抖/异步/缓存） |
| v1.9.2 | 隐藏文件名标题、渲染缓存 |
| v1.9.3 | Mermaid下载、长图导出优化 |
| **v1.9.4** | 表格/图表预览下载、源码编辑、设置持久化 |
| **v1.9.5** | 字符统计、9字体、误触修复、Mermaid保存修复 |
| **v1.9.7** | 隐藏标题修复、源码光标定位、Callout优化、纯文本代码块 |
| **v2.0.0** | Mermaid预览修复、保存修复、隐藏标题修复 |
| **v2.0.4** | 隐藏标题折叠后重新出现修复 |
| **v2.0.5** | 添加桌面快捷方式 |
| **v2.0.6** | 工具栏/状态栏颜色跟随主题 |
| **v2.0.7** | 移除Mermaid保存为PNG、清理代码 |
| **v2.1.0** | 全新羽毛笔图标、桌面快捷方式、主题同步、精简Mermaid |
| **v2.1.1** | 欢迎页更新、搜索栏优化、字体映射改善 |
| **v2.1.3** | 图片预览、视频全屏、音频播放 |
| **v2.1.4** | 编码检测增强、换行符统一、特殊字符转义、特殊空格规范化 |
| **v2.1.5** | 编码检测优化：比较解码质量，避免误切换导致乱码 |
| **v2.1.6** | 渲染前清除菱形问号（U+FFFD），编码检测阈值优化 |
| **v2.1.7** | PDF 编辑崩溃修复、TXT 行首数字反斜杠修复 |

---

*文档更新至 v2.1.7，适合开发者快速上手参与贡献。*
