## v2.2.6 更新日志

### 崩溃修复
- 修复 ERR_INVALID_RESPONSE：标准 markdown .md 链接在 Vault 中未找到时不再崩溃
- `shouldInterceptRequest` 增加 Vault 回退：/assets/ 路径的文件从 Vault 加载
- 添加 `onReceivedError` 处理防止 WebView 错误页面导致后续崩溃
- 返回键处理增加 `pageReady` 守卫，防止 WebView 未初始化时崩溃
- 字数统计增加 `pageReady` 守卫

### 路径与链接修复
- 修复含斜杠路径的 wikilink/图片在 Vault 中找不到的问题
- `encodeVaultPath()` 按路径段编码，保留 `/` 分隔符
- `findFile`/`findAssetInVault` 增加 URL 解码匹配、大小写不敏感匹配
- 缓存构建时存储 URL 解码后的文件名，兼容不同设备

### 外链资源
- 修复 `![[https://...]]` 外链图片/视频无法显示

### Callout 渲染
- 修复 callout 标题正则吞正文（`breaks: true` 模式）
- 修复嵌套 blockquote callout 处理顺序

### 其他修复
- 修复 `%%注释%%` 正则无法处理含 `%` 的注释
- 修复图片扩展名检查未去除 query 参数
- TOC 目录链接使用实际 heading ID
- 恢复默认设置按钮改为重置全部设置
- 修复全角空格转换导致中文文件路径断裂

### 功能精简
- 移除导出长图片/HTML/DOC 功能
- 移除 Apache POI OOXML 依赖
