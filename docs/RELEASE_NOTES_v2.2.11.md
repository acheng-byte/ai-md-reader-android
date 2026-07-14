# MD阅读器 v2.2.11

## 修复

- **彻底修复 Vault 文件夹含中文子路径时搜索/跳转全部失败的问题**

  根因链条（三层问题叠加）：
  1. `Uri.parse()` 不自动编码非 ASCII 字符（如中文），导致 Content Provider 匹配失败
  2. SAF picker 返回的 tree URI 中，子文件夹作为独立路径段存在（如 `/tree/primary:Documents/精华帖子收集箱`），`extractDocId` 只取第一段 `primary:Documents`，丢失子文件夹路径
  3. `DocumentFile.listFiles()` 在某些设备返回空数组，DocumentsContract 回退因 docId 不完整也返回空

  修复方案：
  - `ensureEncoded()`：逐字符扫描，只对非 ASCII 做 percent-encode，保留 `/` `:` 等结构字符不变
  - `extractDocId()`：合并 tree 后所有路径段为完整 docId（`primary:Documents/精华帖子收集箱`）
  - `listDir()`：递归时始终传递原始 vault treeUri，子文件用 `buildDocumentUriUsingTree` 构建

## 改进

- 运行日志缓冲区从 500 条扩大到 5000 条
- 日志消息改为中文说明，用【标签】格式标注操作类型
- 新增"运行日志"菜单项，支持一键复制、清空
