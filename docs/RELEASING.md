# 发布流程（Release Checklist）

本文件记录 MD阅读器的发布“接力”流程，便于后续在已有版本基础上继续发版。内容基于当前仓库实际配置。

## 版本号来源

唯一来源是 `app/build.gradle.kts`，每次发版需同时递增两个字段：

- `versionName`：展示版本（如 `1.3`），用于 Git 标签与 Release 标题。
- `versionCode`：整数，单调递增（如 `4`），用于 Android 升级判定。

当前最新：`versionName = "1.3"`，`versionCode = 4`。

## 每个版本要更新 / 新增的文档

1. `CHANGELOG.md`：在顶部新增 `## vX.Y - 日期` 条目。
2. `docs/RELEASE_NOTES_vX.Y.md`：新建当版发布说明（参照已有的 `RELEASE_NOTES_v1.1/1.2/1.3.md` 格式：本次更新 / 安装 / 说明）。
3. `docs/ROADMAP.md`：把已交付项移入“已完成”，并维护“近期方向”。
4. `README.md`：若功能或交互有变化，同步“功能特性 / 使用方式”。
5. 如需公众号 / 社媒发布，再单独准备文案（当前可参考 `docs/WECHAT_PUBLIC_LAUNCH.md`，发新版本时请同步版本号、亮点和下载链接）。

## 构建“可分发”的签名 APK（务必本地构建）

> 注意：CI（`.github/workflows/android-ci.yml`）会在 push/PR 时构建 debug + release 产物用于校验，但**仓库不含签名材料，CI 的 release 产物会回退为 debug 签名**，不可直接作为正式发布包。正式签名包需在持有 keystore 的机器上本地构建。

前置：本地存在（不提交 git）

- `keystore/release.keystore`
- `keystore/keystore.properties`（storeFile / storePassword / keyAlias / keyPassword）

构建与校验：

```bash
export JAVA_HOME="<JDK 17 或 21，例如 Android Studio 自带 JBR>"
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk

# 校验签名与版本
"$ANDROID_HOME"/build-tools/34.0.0/apksigner verify app/build/outputs/apk/release/app-release.apk
"$ANDROID_HOME"/build-tools/34.0.0/aapt dump badging app/build/outputs/apk/release/app-release.apk | grep version
```

## 发布到 GitHub Releases

二进制不入库（`.gitignore` 已忽略 `*.apk` / `*.aab` / `/dist/` / `/keystore/`），统一通过 Releases 分发。

```bash
git add -A
git commit -m "Release vX.Y: <一句话摘要>"
git tag vX.Y
git push origin main --tags
```

然后创建 GitHub Release：

- 选择标签 `vX.Y`，标题 `MD阅读器 vX.Y`（或 `vX.Y`）。
- 正文粘贴 `docs/RELEASE_NOTES_vX.Y.md` 内容。
- 上传本地构建的签名 APK 作为附件（建议命名 `MD阅读器-X.Y.apk`）。

## 发布前自查清单

- [ ] `versionName` / `versionCode` 已递增
- [ ] `CHANGELOG.md` 顶部已加当版条目
- [ ] `docs/RELEASE_NOTES_vX.Y.md` 已新建
- [ ] `docs/ROADMAP.md` / `README.md` 已按需同步
- [ ] 本地 `assembleRelease` 通过，`apksigner verify` 为正式签名
- [ ] 真机安装冒烟测试通过（打开本地文件 / 微信“用其他应用打开” / 各项新功能）
- [ ] 已打标签并创建 Release，附上签名 APK
