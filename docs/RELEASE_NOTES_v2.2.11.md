# MD阅读器 v2.2.11

## 修复

- **重写 ensureEncoded**：改用逐字符扫描方式，只对非 ASCII 字符做 percent-encode，保留所有结构字符（`/` `:` 等）不变。修复 v2.2.10 中 `Uri.Builder.appendPath()` 过度编码（`:` → `%3A`）导致 Content Provider 不识别的问题。
