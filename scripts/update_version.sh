#!/usr/bin/env bash
# ============================================================
# update_version.sh — 统一版本号同步脚本
# 从 version.properties 读取版本号，同步到所有配置文件
# 用法: ./scripts/update_version.sh
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
PROPS_FILE="$ROOT_DIR/version.properties"
IOS_YML="$ROOT_DIR/ios/project.yml"

# ---------- 读取 version.properties ----------
if [ ! -f "$PROPS_FILE" ]; then
    echo "错误: 找不到 $PROPS_FILE"
    exit 1
fi

versionName=""
versionCode=""
iosBuildNumber=""

while IFS='=' read -r key value; do
    # 跳过注释和空行
    [[ "$key" =~ ^[[:space:]]*# ]] && continue
    [[ -z "$key" ]] && continue
    key=$(echo "$key" | xargs)
    value=$(echo "$value" | xargs)
    case "$key" in
        versionName)     versionName="$value" ;;
        versionCode)     versionCode="$value" ;;
        iosBuildNumber)  iosBuildNumber="$value" ;;
    esac
done < "$PROPS_FILE"

if [ -z "$versionName" ] || [ -z "$versionCode" ] || [ -z "$iosBuildNumber" ]; then
    echo "错误: version.properties 缺少必要字段"
    exit 1
fi

echo "当前版本号:"
echo "  versionName    = $versionName"
echo "  versionCode    = $versionCode"
echo "  iosBuildNumber = $iosBuildNumber"

# ---------- 更新 Android build.gradle.kts ----------
# build.gradle.kts 直接从 version.properties 读取，无需修改
echo "✓ Android build.gradle.kts — 已直接读取 version.properties，无需同步"

# ---------- 更新 iOS project.yml ----------
if [ ! -f "$IOS_YML" ]; then
    echo "警告: 找不到 $IOS_YML，跳过 iOS 版本同步"
else
    # 用 sed 替换 MARKETING_VERSION 和 CURRENT_PROJECT_VERSION
    # 匹配所有出现的行（base 和 target 级别）
    if [[ "$OSTYPE" == "darwin"* ]]; then
        sed -i '' "s/MARKETING_VERSION: .*/MARKETING_VERSION: \"$versionName\"/g" "$IOS_YML"
        sed -i '' "s/CURRENT_PROJECT_VERSION: .*/CURRENT_PROJECT_VERSION: \"$iosBuildNumber\"/g" "$IOS_YML"
    else
        sed -i "s/MARKETING_VERSION: .*/MARKETING_VERSION: \"$versionName\"/g" "$IOS_YML"
        sed -i "s/CURRENT_PROJECT_VERSION: .*/CURRENT_PROJECT_VERSION: \"$iosBuildNumber\"/g" "$IOS_YML"
    fi
    echo "✓ iOS project.yml — 已更新为 $versionName ($iosBuildNumber)"
fi

echo ""
echo "同步完成！所有版本号已统一为 $versionName"
