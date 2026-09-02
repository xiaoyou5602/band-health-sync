#!/usr/bin/env bash
# 构建正式签名的 release APK。
# 签名信息从仓库根目录的 keystore.properties 读取（已被 .gitignore，切勿提交/分享）。
set -euo pipefail
cd "$(dirname "$0")/.."

if [ ! -f keystore.properties ]; then
  echo "✗ 缺少 keystore.properties" >&2
  echo "  从 keystore.properties.example 复制一份，填入你的 keystore 绝对路径和密码。" >&2
  echo "  该文件已被 .gitignore——切勿提交或分享。" >&2
  exit 1
fi

echo "→ 构建 mainlineRelease（签名来自 keystore.properties）…"
./gradlew :app:assembleMainlineRelease "$@"

APK="app/build/outputs/apk/mainline/release/app-mainline-release.apk"
[ -f "$APK" ] || { echo "✗ 没找到产物：$APK" >&2; exit 1; }

echo ""
echo "✓ 完成"
echo "  APK    : $APK"
echo "  SHA-256: $(sha256sum "$APK" | cut -d' ' -f1)"
echo ""
echo "发布（记得先在 app/build.gradle 把 versionCode / versionName 各 +1）："
echo "  gh release create vX.Y.Z --repo xiaoyou5602/band-health-sync \\"
echo "    --title \"健康数据 vX.Y.Z\" --notes-file <说明> \"$APK\""
