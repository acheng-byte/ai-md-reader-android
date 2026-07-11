# MD阅读器

一款为 AI 时代准备的轻量级手机 Markdown 阅读器。支持 Markdown、TXT、DOC、DOCX、PDF 等多种格式，兼容 Obsidian 语法。

[![Latest release](https://img.shields.io/github/v/release/acheng-byte/ai-md-reader-android?label=latest)](https://github.com/acheng-byte/ai-md-reader-android/releases/latest)
[![Android CI](https://github.com/acheng-byte/ai-md-reader-android/actions/workflows/android-ci.yml/badge.svg)](https://github.com/acheng-byte/ai-md-reader-android/actions/workflows/android-ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

现在越来越多内容从 AI 对话、代码助手、知识库、会议纪要和自动化工作流里直接产出为 Markdown。电脑上阅读很方便，但手机上常常缺一个足够轻、足够直接、能从微信和文件管理器顺手打开 `.md` 的本地阅读器。**MD阅读器**解决的就是这个小而高频的痛点：把 Markdown 文件在手机上安静、清楚、离线地读起来。

## 最新版本 v1.8.0

v1.8.0 是功能最完善的版本，新增了护眼模式、字体切换、PDF 支持、导出功能等：

- **新增**：护眼模式 — 暖色羊皮纸背景，减轻长时间阅读的视觉疲劳
- **新增**：字体切换 — 支持默认 / 宋体 / 等宽三种字体
- **新增**：PDF 文件打开与阅读（逐页渲染为图片）
- **新增**：导出长图片（保存至 `Download/MD阅读器/Picture`）
- **新增**：导出 HTML（保存至 `Download/MD阅读器/HTML`，包含完整样式）
- **新增**：PDF 文件关联 — 系统文件管理器可直接用本 App 打开 PDF
- **新增**：设置中手动检查更新按钮
- **修复**：历史记录单条删除闪退
- **修复**：导出长图不完整、HTML 丢失格式
- **修复**：编辑 DOC 后再打开其他 DOC 解析失败
- **优化**：DOC 图片过滤不支持的格式（EMF/WMF）
- **优化**：自动更新检查超时时间延长，提高成功率

[下载最新 APK](https://github.com/acheng-byte/ai-md-reader-android/releases/latest) · [查看 GitHub Releases](https://github.com/acheng-byte/ai-md-reader-android/releases)

## 核心定位

- **轻量优先**：不做臃肿编辑器，先把"打开、阅读、跳转、排版"做好。
- **本地优先**：Markdown 渲染资源打包在 APK 内，常规阅读无需联网。
- **移动优先**：适配手机上的文件打开链路，尤其是微信、文件管理器、聊天软件里的 `.md` 文件。
- **AI 场景优先**：面向 AI 生成文档、代码说明、知识库片段、长提示词、会议纪要等高频 Markdown 内容。

## 功能特性

| 场景 | 能力 |
| --- | --- |
| 打开本地文档 | 通过系统文件选择器读取 `.md` / `.markdown` / `.txt` / `.doc` / `.docx` / `.pdf` 文件 |
| 从其他应用打开 | 支持微信、文件管理器、QQ、邮件等应用的"用其他应用打开"入口 |
| 源码 / 预览切换 | 同一文件可在原始 Markdown 与渲染预览之间切换 |
| 代码块复制 | 预览模式下每个代码块右上角提供一键复制 |
| 阅读排版调节 | 点击屏幕中央区域唤出，支持字号、行间距、段间距、字体实时调节 |
| 护眼模式 | 暖色羊皮纸背景，浅色/深色主题下均可开启 |
| 字体切换 | 默认 / 宋体 / 等宽三种字体可选 |
| 明暗主题 | 支持跟随系统、浅色、深色 |
| 文档目录 | 自动生成标题目录，点击后平滑跳转 |
| 标题折叠 | 预览模式下点击标题即可折叠或展开对应章节 |
| 打开历史 | 记录最近打开文件，支持单条删除和清空 |
| 收藏夹 | 收藏会把文档复制到应用目录，原文件被删除后仍可打开 |
| 断点续读 | 自动记录阅读位置，下次打开回到原位 |
| 文内搜索 | 在当前文档中搜索关键词，高亮显示并逐个定位 |
| 全库搜索 | 在 Vault 文件夹中搜索所有文档（需先设置 Vault） |
| 转发分享 | 一键把完整文档经系统分享转发到微信等应用 |
| 编辑模式 | 内置 Markdown 编辑器，保存后立即刷新预览 |
| 导出长图片 | 滚动截图拼接，保存至 `Download/MD阅读器/Picture` |
| 导出 HTML | 包含完整样式的独立 HTML 文件，保存至 `Download/MD阅读器/HTML` |
| 自动更新 | 启动时自动检查 GitHub Release 更新，也可手动检查 |
| 离线渲染 | 内置 markdown-it 与 highlight.js，支持常见 Markdown 语法和代码高亮 |

### Obsidian 兼容语法

| 语法 | 说明 |
| --- | --- |
| `[[页面]]` | Wikilink 跳转 |
| `[[目录/文件\|显示名]]` | 路径式 Wikilink |
| `[[#标题]]` | 页内锚点跳转 |
| `![[图片.png]]` | 嵌入图片 |
| `![[视频.mp4]]` | 嵌入视频 |
| `![[文档.md]]` | 引用文档内联展开 |
| `> [!NOTE]` | Callout 标注（支持多种类型） |
| `==高亮==` | 黄色背景高亮 |
| `#标签` | 标签胶囊样式 |
| `%%注释%%` | 注释内容隐藏 |
| `[^1]` 脚注 | 上标链接 + 文末脚注列表 |
| YAML Frontmatter | 自动解析为元数据表格 |
| Mermaid 图表 | 流程图、时序图、饼图等 |
| 任务列表 `- [ ]` | 可视复选框 |

## 下载安装

Android 发布版 APK 会放在 GitHub Releases 中。下载最新版本后，在 Android 手机上允许"安装未知来源应用"即可侧载安装。支持 APK 安装的鸿蒙设备可按同样方式试用。

也可以通过 ADB 安装本地构建产物：

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

## 版本更新

| 版本 | 类型 | 更新重点 |
| --- | --- | --- |
| [v1.8.0](https://github.com/acheng-byte/ai-md-reader-android/releases/tag/v1.8.0) | 最新版本 | 护眼模式、字体切换、PDF 支持、导出长图/HTML、检查更新按钮、PDF 文件关联 |
| [v1.7.3](https://github.com/acheng-byte/ai-md-reader-android/releases/tag/v1.7.3) | Bug 修复 | 修复历史面板双删除 bug、MediaScanner URI 修复 |
| [v1.7.2](https://github.com/acheng-byte/ai-md-reader-android/releases/tag/v1.7.2) | Bug 修复 | DOC 图片过滤、导出 HTML 修复、导出路径优化、编辑 DOC 保护 |
| [v1.7.1](https://github.com/acheng-byte/ai-md-reader-android/releases/tag/v1.7.1) | Bug 修复 | 编译依赖修复、DOC 图片提取、导出长图线程修复、DOCX 表格渲染 |
| [v1.6.4](https://github.com/acheng-byte/ai-md-reader-android/releases/tag/v1.6.4) | 体验优化 | 启动自动恢复上次阅读文档 |
| [v1.6.3](https://github.com/acheng-byte/ai-md-reader-android/releases/tag/v1.6.3) | Bug 修复 | 单换行符、DOCX 标题误识别、TXT 代码块优化 |
| [v1.6.2](https://github.com/acheng-byte/ai-md-reader-android/releases/tag/v1.6.2) | Obsidian 兼容 | 路径式 Wikilink、高亮/标签/注释/脚注等 Obsidian 语法 |
| [v1.6.0](https://github.com/acheng-byte/ai-md-reader-android/releases/tag/v1.6.0) | 多格式支持 | DOCX/TXT 支持、Vault 图片视频、引用展开、全库异步搜索、自动更新 |
| [v1.4.1](https://github.com/acheng-byte/ai-md-reader-android/releases/tag/v1.4.1) | 阅读体验 | 阅读位置记忆、收藏夹修复 |
| [v1.4](https://github.com/acheng-byte/ai-md-reader-android/releases/tag/v1.4) | 分享 | 新增转发分享功能 |

完整版本记录见 [CHANGELOG.md](CHANGELOG.md)，每版详细说明见 [GitHub Releases](https://github.com/acheng-byte/ai-md-reader-android/releases)。

## 使用方式

1. 工具栏常显五项：转发、目录、源码 / 预览切换、收藏切换、更多菜单（⋮）。
2. 从"更多菜单 ⋮ → 打开"用系统文件选择器选择文档文件。
3. 点击"目录"，从左侧目录快速跳转到标题位置。
4. 在预览模式下点击标题，可折叠或展开该章节内容；代码块右上角可一键复制。
5. 点击屏幕中央区域，调出"显示设置"（字号、行间距、段间距、主题、护眼模式、字体、Vault 文件夹、检查更新）。
6. 点击搜索图标，在当前文档中搜索关键词；也可切换为全库搜索。
7. 点击工具栏星形图标"收藏"当前文档；在"更多菜单 ⋮ → 收藏夹 / 打开历史"中管理。
8. 通过"更多菜单 ⋮ → 导出长图片 / 导出 HTML"导出文档。
9. 下次重新打开同一文档时，会自动回到上次阅读位置。
10. 在微信收到 `.md` 文件时，选择"用其他应用打开"，再选择"MD阅读器"。

## 从源码构建

环境要求：

- Android SDK：compileSdk 34，minSdk 26
- JDK：17 或 21
- Gradle：使用仓库自带 `./gradlew`

构建命令：

```bash
# 调试包
./gradlew :app:assembleDebug

# 发布包
./gradlew :app:assembleRelease
```

如果本地存在 `keystore/keystore.properties`，构建脚本会使用其中配置进行 release 签名；如果不存在，会回退到 debug 签名，仍可生成可安装 APK。正式分发时请使用你自己的 keystore，不要提交任何签名材料。

## 技术栈

- Kotlin + 原生 Android
- AndroidX Core / AppCompat / WebKit
- Material Components
- WebView + WebViewAssetLoader
- markdown-it 14.1.0
- highlight.js 11.9.0
- Mermaid（离线渲染）
- Apache POI 4.1.2（HWPF for .doc）
- Android PdfRenderer（PDF 逐页渲染）

项目结构：

```text
.
├─ app/
│  ├─ build.gradle.kts
│  └─ src/main/
│     ├─ AndroidManifest.xml
│     ├─ java/com/mdreader/app/
│     │   ├─ MainActivity.kt
│     │   ├─ MarkdownBridge.kt
│     │   ├─ Prefs.kt
│     │   ├─ FileUtils.kt
│     │   ├─ VaultSearch.kt
│     │   ├─ UpdateChecker.kt
│     │   ├─ History.kt / HistoryAdapter.kt
│     │   ├─ Favorites.kt
│     │   ├─ ReadingProgress.kt
│     │   └─ DocStatus.kt
│     ├─ res/
│     └─ assets/
│        ├─ viewer.html
│        ├─ app.js
│        ├─ app.css
│        └─ lib/
├─ .github/workflows/
│  ├─ android-ci.yml
│  └─ release.yml
├─ docs/
│  ├─ MAINTENANCE.md
│  └─ RELEASE_NOTES_v*.md
├─ CHANGELOG.md
├─ LICENSE
└─ THIRD_PARTY_NOTICES.md
```

## 开源协议

本项目使用 [MIT License](LICENSE) 开源。第三方依赖声明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

欢迎提交 issue、PR 和真实使用场景。
