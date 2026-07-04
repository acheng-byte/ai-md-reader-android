# AI MD Reader Android — 维护与开发手册

> 版本：v1.6.0 | 更新日期：2026-07-04
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

- **前端渲染**：WebView + markdown-it（JS）
- **原生层**：Kotlin + AndroidX
- **文件访问**：Storage Access Framework (SAF)，无需危险权限
- **iOS 同款**：仓库内含 `ios/` 目录，SwiftUI 实现，共享同一套 web 资源

**设计哲学**：所有渲染逻辑放在 Web 层（JS/CSS/HTML），原生层只做文件读写、系统调用和 UI 壳；两层通过 `MarkdownBridge` 互相通信。

---

## 2. 目录结构

```
ai-md-reader-android/
├── app/
│   ├── build.gradle.kts                  # 版本号、依赖、签名配置
│   └── src/main/
│       ├── AndroidManifest.xml           # 权限、Intent Filter（接收 .md/.docx/.txt 文件）
│       ├── assets/                       # WebView 加载的前端资源
│       │   ├── viewer.html               # 入口 HTML
│       │   ├── app.js                    # 全部渲染逻辑（核心）
│       │   ├── app.css                   # 全部样式
│       │   └── lib/
│       │       ├── markdown-it.min.js    # Markdown 解析器
│       │       ├── highlight.min.js      # 代码高亮
│       │       └── mermaid.min.js        # Mermaid 图表
│       ├── java/com/mdreader/app/
│       │   ├── MainActivity.kt           # 主 Activity
│       │   ├── MarkdownBridge.kt         # JS↔原生桥接接口定义
│       │   ├── Prefs.kt                  # SharedPreferences 封装
│       │   ├── FileUtils.kt              # 文件读取（支持 md/txt/docx/doc）
│       │   ├── VaultSearch.kt            # Vault 搜索（异步）
│       │   ├── UpdateChecker.kt          # GitHub Releases 更新检查
│       │   ├── History.kt / HistoryAdapter.kt
│       │   ├── Favorites.kt / FavoritesAdapter.kt
│       │   ├── ReadingProgress.kt        # 阅读进度持久化
│       │   └── DocStatus.kt              # 文档状态枚举
│       └── res/
│           ├── layout/
│           │   ├── activity_main.xml
│           │   ├── sheet_settings.xml    # 含 Frontmatter/Citations 开关
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
| 代码块（带高亮）| ✅ | highlight.js，100+ 语言 |
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
| 数学公式 KaTeX/MathJax | ❌ | 待支持 |
| 脚注 | ❌ | 待支持 |

### 3.2 文件格式

| 格式 | 状态 | 说明 |
|---|:---:|---|
| .md / .markdown | ✅ | 完整支持 |
| .txt | ✅ | 包裹为代码块显示 |
| .docx | ✅ | XML 解析，保留标题层级 |
| .doc | ✅ | 尽力提取可读文本 |

### 3.3 阅读体验

| 功能 | 状态 |
|---|:---:|
| 目录（TOC）侧滑面板 | ✅ |
| 标题折叠/展开 | ✅ |
| 全文搜索（当前文档）| ✅ |
| 全库搜索（Vault，异步）| ✅ |
| 字号/行距/段距调节 | ✅ |
| 主题浅色/深色/系统 | ✅ |
| 阅读进度记忆 | ✅ |
| Frontmatter 开关 | ✅ |
| 引用块样式开关 | ✅ |

### 3.4 系统集成

| 功能 | 状态 |
|---|:---:|
| 自动检查更新（每日一次）| ✅ |
| 发现新版本弹窗 + 直接下载安装 | ✅ |
| CI 自动构建 | ✅ |
| 自动发布 Release APK | ✅ |

---

## 4. 核心文件职责说明

### `app.js`

**最核心的文件**，所有 Markdown 渲染逻辑都在这里：

```
app.js 职责：
├── markdown-it 初始化 + 任务列表规则注入
├── parseFrontmatter()       — 解析 YAML front matter
├── preprocessWikilinks()    — 替换 [[Page]]、![[img]]、![[doc]]
├── renderFrontmatter()      — 渲染 frontmatter 为 HTML 表格
├── initMermaid()            — 初始化 Mermaid
├── render()                 — 主渲染（立即清空旧内容，防止停留）
├── 折叠/展开逻辑
├── 目录构建
├── 搜索（当前文档高亮 + 全库异步搜索）
├── window._toggleEmbed()    — 引用文档展开
├── applySettings()          — CSS 变量注入 + frontmatter/citations 开关
└── 暴露给原生的 API
```

**重要 URL 约定**：Vault 内资源使用 WebViewAssetLoader 的 `/vault/` 路径：

```
const VAULT_BASE = 'https://appassets.androidplatform.net/vault/';
// ![[img.png]] → <img src="https://appassets.androidplatform.net/vault/img.png">
```

### `MainActivity.kt`

- `loadDocument()` — 异步读文件，loadGeneration 防竞态，防止快速切换时停留在旧文档
- `renderCurrent()` — 50ms 防抖，避免快速触发多次渲染
- `searchVaultAsync()` — 后台线程搜索，回调 `window.appVaultSearchResult`
- `searchVaultForEmbed()` — 按文件名查 Vault，返回 URI 供嵌入展开用

### `MarkdownBridge.kt`

新增接口：

| 方法 | 说明 |
|---|---|
| `searchVaultAsync(query, callbackId)` | 异步全库搜索，结果通过 JS 回调返回 |
| `searchVaultForEmbed(ref)` | 按名查 Vault 文件 URI（供 ![[doc]] 展开） |

---

## 5. 修改指南

### 5.1 渲染相关

| 要改的功能 | 文件 | 位置 |
|---|---|---|
| Markdown 语法支持 | `app.js` | markdown-it 初始化 + core rules |
| Vault 图片/资源路径 | `app.js` | `VAULT_BASE` 常量 + `preprocessWikilinks()` |
| Frontmatter 开关效果 | `app.js` | `render()` 中 `showFm` 检查 |
| 引用块开关 | `app.css` | `.hide-citations blockquote` |
| 视频/图片嵌入样式 | `app.css` | `.video-embed` |
| 引用展开样式 | `app.css` | `.embed-block` |

### 5.2 搜索相关

| 要改的功能 | 文件 | 位置 |
|---|---|---|
| 全库搜索异步回调 | `MainActivity.kt` | `searchVaultAsync()` |
| JS 接收搜索结果 | `app.js` | `window.appVaultSearchResult` |
| 搜索防抖延迟 | `app.js` | `setTimeout(doSearch, 150)` |

### 5.3 文件格式

| 要改的功能 | 文件 | 位置 |
|---|---|---|
| 添加新格式支持 | `FileUtils.kt` | `readText()` + 新解析函数 |
| Intent Filter | `AndroidManifest.xml` | 添加 MIME 或扩展名 |
| 文件类型检查 | `MainActivity.kt` | `isAllowedFile()` |

### 5.4 发版相关

| 要改的功能 | 文件 | 位置 |
|---|---|---|
| 修改版本号 | `app/build.gradle.kts` | `versionCode` + `versionName` |
| 修改更新检查仓库 | `UpdateChecker.kt` | `RELEASES_API` 常量 |

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

### Vault 资源加载流程（v1.6.0）

```
![[image.png]]
    ↓ (preprocessWikilinks)
![image.png](https://appassets.androidplatform.net/vault/image.png)
    ↓ (WebViewAssetLoader)
VaultPathHandler.handle("image.png")
    ↓
VaultSearch.resolveRelativeAsset() → InputStream
    ↓
图片显示
```

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

---

## 8. 已知问题与注意事项

### 8.1 .doc 解析有限

旧版 `.doc`（OLE2 格式）通过字节扫描提取可读文本，内容可能不完整，建议用户转换为 `.docx`。

### 8.2 Vault 图片相对路径

`resolveRelativeAsset()` 先在当前文档目录查找，再全库搜索文件名。同名文件在不同目录可能拿错。

### 8.3 SAF DocumentFile 性能

大型 Vault（数百文件）全库搜索仍有延迟（已改为异步，不阻塞 UI，但无进度提示）。

### 8.4 `@JavascriptInterface` 混淆

已关闭 minify，未来若开启需在 ProGuard 保留 `MarkdownBridge` 类。

### 8.5 微信临时 URI 授权

从微信打开的 content:// URI 在 App 杀死后失效，属 Android 系统限制。

---

## 9. 未来开发建议

- **数学公式**：引入 KaTeX（轻量）
- **脚注**：markdown-it-footnote 插件
- **全库搜索索引**：Room + SQLite FTS 提速 10x+
- **iOS 资源同步**：CI 中 rsync `assets/` → `ios/MDReader/Resources/web/`
- **ViewModel 重构**：将 MainActivity 业务逻辑迁移到 ViewModel

---

## 版本变更历史

| 版本 | 主要变更 |
|---|---|
| v1.0 | 基础 Markdown 渲染 |
| v1.1 | 代码高亮 |
| v1.2 | TOC 侧滑面板 |
| v1.3 | 阅读进度记忆 |
| v1.4 | 收藏夹、历史、分享 |
| v1.4.1 | Bug 修复 |
| v1.5.1 | Wikilink、Mermaid、Frontmatter、全文搜索、Vault、HTML 渲染、自动更新 |
| **v1.6.0** | DOCX/TXT 支持、图片修复、搜索异步化、任务列表、引用展开、设置开关 |

---

*文档由 Capy 维护，适合开发者快速上手参与贡献。*
