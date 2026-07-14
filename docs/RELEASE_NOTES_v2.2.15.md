## v2.2.15 更新日志

### Bug 修复
- 修复 VaultSearch 线程安全问题（`isScanning` 加 `@Volatile`，`fileCache` 加 `synchronized`）
- 修复启动预扫描 `prefs.vaultUri!!` 空指针风险
- 修复 `Logger.errorCount()` 每次遍历全部 10000 条导致 UI 卡顿（改为运行计数器）

### 性能优化
- **修复启动卡顿一分钟的根本原因**：`shouldInterceptRequest` 加载资源时不再触发全库扫描，改为只查已有缓存
- Vault 状态检查移到后台线程，不阻塞主线程
- 预扫描延迟 3 秒 + 最低线程优先级，不与文档加载抢 I/O
