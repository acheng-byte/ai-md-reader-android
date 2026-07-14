## v2.2.7 更新日志

### Vault 文件查找修复
- 恢复缓存机制（v2.2.5 方案）：缓存存储文件名多种变体（原始/小写/URL解码），解决 wikilink 找不到文件问题
- buildCache 增加 root.canRead() 检查和空缓存检测，URI 权限过期时安全回退
- findInDir 改进 fileNameMatches：支持 URL 解码后匹配，不再依赖简单 equals
- findFile 新增 dirNameMatches：目录名也支持 URL 解码匹配
- 缓存未命中时回退到改进后的递归搜索（不再是简单 equals）
