# MD阅读器 v2.2.10

## 修复

- **修复 Vault 子文件夹 URI 编码的根本问题**：`ensureEncoded()` 之前把 docId 中的 `/子文件夹` 当作独立路径段，导致 tree docId 被截断为 `primary:Documents`（丢失子文件夹）。现在正确合并为 `primary:Documents/精华帖子收集箱` 并编码为单个 URI 段。
- 修复后 `DocumentFile.fromTreeUri()` 和 `DocumentsContract` 查询都能正确定位到用户选择的子文件夹。
