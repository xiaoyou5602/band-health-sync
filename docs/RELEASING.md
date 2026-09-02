# 发布新版本

面向维护者。普通用户装 [Releases](https://github.com/xiaoyou5602/band-health-sync/releases) 里的 APK 即可。

1. **改版本号**：`app/build.gradle` 里 `versionCode` +1、`versionName` 视情况改，**两个都要动**。
2. **准备签名**（首次）：把 `keystore.properties.example` 复制成 `keystore.properties`，填入 keystore 绝对路径与密码。
   - 该文件已被 `.gitignore`，**绝不提交**。
   - keystore 与密码必须与历史版本**同一个**，否则已安装用户无法覆盖升级。
3. **构建**：
   ```bash
   bash scripts/release.sh
   ```
   末尾会打印 APK 路径和 SHA-256。
4. **发布**：
   ```bash
   gh release create vX.Y.Z --repo xiaoyou5602/band-health-sync \
     --title "健康数据 vX.Y.Z" \
     --notes "APK SHA-256: …；签名指纹: a5c574ea…6f9aaf" \
     app/build/outputs/apk/mainline/release/app-mainline-release.apk
   ```

发布类型是**非混淆**（`minifyEnabled false`），行为与自测的 debug 版一致，规避未验证的 proguard 规则风险。

签名证书 SHA-256（所有版本应一致，用于校验同源）：`a5c574ea44c6128f0a30c335e4f9384882d0ea234a7e8953667516c08b6f9aaf`
