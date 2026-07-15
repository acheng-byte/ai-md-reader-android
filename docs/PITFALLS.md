# 踩坑记录

本文记录 MD阅读器 开发过程中遇到的典型问题和解决方案，供后续开发参考。

## 1. CI 编译错误

### 1.1 Unresolved reference: FileWriter (v2.2.31)
- 问题：`VaultIndex.kt:406` 使用了 `FileWriter`，但未导入 `java.io.FileWriter`
- 原因：本地 IDE 自动导入在 CI 环境不可用
- 解决：添加 `import java.io.FileWriter` 导入语句
- 教训：CI 编译是最终验证，本地构建通过不代表 CI 通过

### 1.2 References to variables and parameters are unsupported (v2.2.30)
- 问题：`MainActivity.kt:1533` 在 lambda 中引用了不支持的变量
- 原因：Kotlin 编译器对某些 lambda 内变量引用有限制
- 解决：重构代码，避免在 lambda 中直接引用外部变量
- 教训：Android 的 Kotlin 编译器版本可能与本地不同，注意兼容性

### 1.3 Function invocation 'exists()' expected (v2.2.28)
- 问题：`VaultSearch.kt:304` 调用 `exists()` 时缺少括号
- 原因：Kotlin 中 `exists` 是函数不是属性，必须加 `()` 调用
- 解决：改为 `exists()`
- 教训：注意 Kotlin 中函数调用与属性访问的区别

## 2. WebView 相关

### 2.1 CSS zoom 无法影响 WebView 原生缩放 (v2.2.36)
- 问题：通过 JS 设置 `document.documentElement.style.zoom = '1'` 无法重置 WebView 的 pinch zoom
- 原因：WebView 的 pinch zoom 是原生缩放（native scale），与 CSS zoom 是完全不同的机制
- 解决：通过 `@JavascriptInterface` 桥接，在 Kotlin 端调用 `webView.setInitialScale(100)` + `reload()`
- 教训：WebView 的缩放有两个层面——CSS 层和原生层，JS 只能控制 CSS 层

### 2.2 Canvas 绘制表格 PNG 文字压缩错位 (v2.2.39)
- 问题：使用 `getBoundingClientRect()` 递归遍历 DOM 元素来绘制表格，输出图片中文字被压缩变窄、部分错位
- 原因：`getBoundingClientRect()` 返回的是当前布局下的坐标，受滚动位置、布局偏移、CSS transform 等影响，在 WebView 中尤其不稳定
- 解决：恢复 v2.2.35 的方案——从 DOM 提取纯文本数据，用 `ctx.measureText()` 测量文字宽度，计算列宽后从零用 Canvas 2D 绘制整个表格
- 教训：Canvas 绘制不要依赖 DOM 布局坐标，应该从数据出发独立计算

### 2.3 shouldInterceptRequest 递归搜索导致白屏 (v2.2.17)
- 问题：资源拦截请求中触发递归文件搜索，导致 WebView 白屏
- 原因：`shouldInterceptRequest` 在 WebView 的 I/O 线程执行，递归搜索阻塞了资源加载
- 解决：改为只查已有缓存，不触发搜索
- 教训：WebView 资源拦截回调必须快速返回，不能做耗时操作

## 3. 文件与存储

### 3.1 缓存 URI 比较错误导致白屏 (v2.2.18)
- 问题：缓存的 URI 比较逻辑有误，导致部分文件加载白屏
- 原因：URI 编码/解码不一致，`content://` URI 中的特殊字符未被正确处理
- 解决：统一 URI 编码方式，比较前先规范化
- 教训：Android SAF 的 URI 比较必须考虑编码问题

### 3.2 索引空目录断点 (v2.2.27)
- 问题：Vault 索引扫描到空目录时中断
- 原因：空目录返回 null 或空数组时未做防御
- 解决：添加空值检查
- 教训：文件遍历必须处理所有边界情况

### 3.3 递归扫描 StackOverflowError (v2.2.23)
- 问题：深层嵌套的 Vault 目录导致递归扫描栈溢出
- 原因：使用递归函数遍历目录树，深度超过 JVM 栈限制
- 解决：改为迭代方式（使用显式栈/队列）
- 教训：文件系统的目录深度不可控，永远用迭代代替递归遍历

## 4. 时区与统计

### 4.1 陪伴天数与活跃天数不一致 (v2.2.38)
- 问题："陪你读了 1 天"但"活跃天数：2 天"
- 原因：`companionDays()` 使用 UTC 毫秒除法计算天数差，而 `activeDays` 使用北京时间（Asia/Shanghai）午夜为分界线
- 解决：`companionDays()` 改用 `Calendar` + `Asia/Shanghai` 时区，将两个时间都归一化到北京时间午夜后再计算天数差
- 教训：涉及"天"的计算必须统一时区，UTC 毫秒除法不等于日历天数

## 5. 功能迭代教训

### 5.1 预览覆盖层的取舍 (v2.2.35-v2.2.37)
- 背景：v1.9.4 引入了表格/图表全屏预览覆盖层，后续不断围绕它做修改
- 问题：预览覆盖层与 WebView 原生缩放、图片点击、设置面板等多个系统冲突
- 最终方案：v2.2.37 彻底移除预览覆盖层，回归纯净阅读体验；v2.2.40 仅保留长按表格保存 PNG
- 教训：功能不是越多越好，当多个功能互相冲突时，应该做减法而不是打补丁

### 5.2 极简改动原则
- 教训：用户明确要求"只要这个功能，不要后边的这些功能"
- 原则：每次改动只实现明确提到的功能，不自作主张添加额外功能
- 案例：v2.2.36 添加了双击重置缩放 + 表格预览 + 预览层双击关闭三个功能，用户只想要双击重置缩放，最终 v2.2.37 全部回退
