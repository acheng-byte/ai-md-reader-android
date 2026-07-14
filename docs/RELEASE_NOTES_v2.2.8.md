## v2.2.8 更新日志

### 诊断日志系统
- 新增运行日志功能（菜单 → 运行日志），保留最近 500 条操作记录
- 日志覆盖 Vault 文件查找全链路：缓存构建、目录列表、文件名匹配、搜索结果
- 支持一键复制全部日志到剪贴板，方便反馈问题
- 启动时自动记录 Vault URI 状态和权限情况

### Vault 文件查找改进
- listDir 三重回退：DocumentFile.listFiles → DocumentsContract(getDocumentId) → DocumentsContract(URI segment)
- 移除 buildCache 中 canRead() 检查，避免某些 Storage Provider 误判
- findInDir/findFileInDir 统一使用 listDir，消除空数组连锁失败
