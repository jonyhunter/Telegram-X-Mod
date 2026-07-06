# Telegram Z 二次开发恢复手册

本文档记录 Telegram Z 相对上游 `TGX-Android/Telegram-X` 的核心二次开发内容。后续同步上游 `main` 后，可按本文逐项恢复与验证。

## 变更来源

- 主仓库提交：`105bfdc171a741c86f7f5de906fa03c62703a4d0`，短 SHA 为 `105bfdc1`，提交标题为 `Apply Telegram Z storage and build configuration`。
- `vkryl/leveldb` 子模块提交：`30755c231292d21c02cfb740f20891275fe86d58`，短 SHA 为 `30755c23`，提交标题为 `Respect app.abis in leveldb build`。
- 注意：`vkryl/leveldb` 的 `30755c23` 是子模块内的相关二开提交，不属于主仓库 `105bfdc1` 的文件列表；同步上游后需要单独检查子模块指针或子模块分支。

## 核心功能

Telegram Z 的核心改动是将 TDLib 外部媒体缓存目录固定迁移到：

```text
/sdcard/TelegramZ/
```

常见 TDLib 媒体缓存子目录包括：

```text
animations
documents
music
photos
videos
voice
video_notes
temp
```

多账号目录继续使用 Telegram X/TDLib 既有规则：

```text
/sdcard/TelegramZ/x_account1/
/sdcard/TelegramZ/x_account2/
```

只迁移 TDLib 的 `filesDirectory`。`databaseDirectory` 必须继续保留在应用内部私有目录，不能迁移到 `/sdcard/TelegramZ`。

`Tdlib.java` 中应保持：

```java
parameters.databaseDirectory = TdlibManager.getTdlibDirectory(accountId, false);
parameters.filesDirectory = TdlibManager.getTdlibDirectory(accountId, !isService);
```

## 恢复步骤

1. 恢复 TDLib 目录逻辑

   修改 `app/src/main/java/org/thunderdog/challegram/telegram/TdlibManager.java`：

   - `allowExternal == true` 时优先解析 `/sdcard/TelegramZ`。
   - Android 11 及以上必须满足 `Environment.isExternalStorageManager()`。
   - 外部存储必须处于 `Environment.MEDIA_MOUNTED`。
   - 根目录创建成功后写入 `.nomedia`。
   - `accountId != 0` 时使用 `x_account{accountId}`，账号目录内也写入 `.nomedia`。
   - `allowExternal == false` 时继续使用应用内部 `filesDir/tdlib` 或 `filesDir/tdlib{accountId}`。
   - 如果没有 all-files access，必须保留 fallback 或非创建场景返回 `null` 的行为，避免 TDLib 初始化流程直接崩溃。

2. 恢复权限声明

   在 `app/src/main/AndroidManifest.xml` 中声明：

   ```xml
   <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE"
     tools:ignore="ScopedStorage" />
   ```

   Android 11 及以上安装后仍需用户或 ADB 授予 all-files access。

3. 恢复构建配置读取

   `local.properties` 必须包含 Telegram Z 的本地构建配置：

   ```properties
   app.id=app.jony.tgz
   app.name=Telegram Z
   app.abis=arm64
   app.languages=en,zh-hans
   app.output_version_code=1789000
   keystore.file=G\:\\TelegramZ\\.codex-tools\\signing\\release-keystore.properties
   ```

   `.codex-tools/` 存放本地 JDK 和签名配置，不应提交到 Git。

   JDK 根目录：

   ```text
   G:\TelegramZ\.codex-tools\jdk21\jdk-21.0.11+10
   ```

4. 恢复 arm64-only 构建逻辑

   `buildSrc/src/main/kotlin/Config.kt` 与 `buildSrc/src/main/kotlin/tgx/gradle/plugin/ConfigurationPlugin.kt` 需要支持：

   - `app.output_version_code`
   - `app.languages`
   - `app.abis`

   `app/build.gradle.kts` 需要：

   - 在 `defaultConfig` 中应用 `resourceConfigurations.addAll(config.resourceConfigurations)`。
   - 在 `androidComponents.beforeVariants` 中用 `config.abiFlavors` 禁用未选择 ABI 的变体。
   - 输出版本号优先使用 `config.outputVersionCode`。

   ABI 过滤条件应包含：

   ```kotlin
   (config.abiFlavors.isEmpty() || config.abiFlavors.contains(abiFlavor))
   ```

   `vkryl/leveldb/build.gradle.kts` 也必须读取 `local.properties` 的 `app.abis`，并在 `defaultConfig` 中设置：

   ```kotlin
   ndk.abiFilters.addAll(selectedAbiFilters)
   ```

   最终构建日志中不应出现非目标 ABI 变体，例如：

   ```text
   armeabi-v7a
   x86
   x86_64
   arm32
   x64
   universal
   ```

5. 恢复中文资源与语言过滤

   - 保留 `app/src/main/res/values-zh/strings.xml`。
   - `app/src/main/res/.gitignore` 不应忽略 `values-zh`。
   - `FetchLanguagesTask` 应读取 `app.languages`，只更新选定语言。
   - `zh-hans` 在资源过滤中映射为 Android 资源目录使用的 `zh`。

6. 恢复 Firebase 包名

   `app/google-services.json` 中所有 Android `package_name` 必须是：

   ```text
   app.jony.tgz
   ```

   如果使用旧 Firebase App ID 本地编译 release，需要跳过 `validateApiTokens`；长期应使用 Firebase 控制台为 `app.jony.tgz` 生成的新 `google-services.json`。

7. 记录相关但非核心迁移项

   `app/src/main/java/org/thunderdog/challegram/provider/TgxCacheDocumentsProvider.java` 是提交 `105bfdc1` 新增文件，但当前没有在 `AndroidManifest.xml` 注册 provider。

   该 provider 使用的是 `getExternalFilesDir(null)`，不是 `/sdcard/TelegramZ`。恢复时不要把它当作 TDLib 外部媒体目录迁移入口；TDLib 迁移入口仍是 `TdlibManager.getTdlibDirectory(...)`。

## 验证命令

Debug 真机测试包：

```powershell
$env:JAVA_HOME='G:\TelegramZ\.codex-tools\jdk21\jdk-21.0.11+10'
.\gradlew.bat :app:assembleLatestArm64Debug -x updateLanguages --offline --console=plain
```

Release 包：

```powershell
$env:JAVA_HOME='G:\TelegramZ\.codex-tools\jdk21\jdk-21.0.11+10'
.\gradlew.bat :app:assembleLatestArm64Release -x validateApiTokens -x updateLanguages --offline --console=plain
```

APK 签名与 badging 检查：

```powershell
D:\Android\SDK\build-tools\37.0.0\apksigner.bat verify --verbose G:\TelegramZ\app\build\outputs\apk\latestArm64\release\Telegram-Z-0.28.9.1788-arm64-v8a.apk
D:\Android\SDK\build-tools\37.0.0\aapt.exe dump badging G:\TelegramZ\app\build\outputs\apk\latestArm64\release\Telegram-Z-0.28.9.1788-arm64-v8a.apk
```

期望关键信息：

```text
package name: app.jony.tgz
versionCode: 1789000
native-code: arm64-v8a
locales: --_--, zh
signature: APK Signature Scheme v2 verified
```

授予 all-files access 并重启应用：

```powershell
adb shell appops set --uid app.jony.tgz MANAGE_EXTERNAL_STORAGE allow
adb shell appops get --uid app.jony.tgz MANAGE_EXTERNAL_STORAGE
adb shell am force-stop app.jony.tgz
adb shell am start -n app.jony.tgz/org.thunderdog.challegram.MainActivity
```

期望授权状态：

```text
Uid mode: MANAGE_EXTERNAL_STORAGE: allow
```

验证 TDLib 媒体目录：

```powershell
adb shell find /sdcard/TelegramZ -maxdepth 2 -print
adb shell ls -la /sdcard/TelegramZ
adb shell ls -la /sdcard/TelegramZ/photos
adb shell ls -la /sdcard/TelegramZ/temp
```

成功状态应包含：

```text
/sdcard/TelegramZ/.nomedia
/sdcard/TelegramZ/photos/.nomedia
/sdcard/TelegramZ/temp/.nomedia
/sdcard/TelegramZ/x_account1/.nomedia
/sdcard/TelegramZ/photos/<tdlib media files>
```

## 恢复检查清单

- `app/src/main/java/org/thunderdog/challegram/telegram/TdlibManager.java`：`allowExternal == true` 使用 `/sdcard/TelegramZ`，并创建 `.nomedia`。
- `app/src/main/java/org/thunderdog/challegram/telegram/Tdlib.java`：`databaseDirectory` 继续传 `false`，`filesDirectory` 继续传 `!isService`。
- `app/src/main/AndroidManifest.xml`：声明 `MANAGE_EXTERNAL_STORAGE`。
- `app/build.gradle.kts`：读取语言过滤、ABI 过滤、固定输出 `versionCode`。
- `buildSrc/src/main/kotlin/Config.kt` 与 `ConfigurationPlugin.kt`：解析 `app.output_version_code`、`app.languages`、`app.abis`。
- `buildSrc/src/main/kotlin/tgx/gradle/task/FetchLanguagesTask.kt`：只拉取 `app.languages` 指定语言。
- `app/src/main/res/values-zh/strings.xml`：简体中文资源存在。
- `app/google-services.json`：Android `package_name` 为 `app.jony.tgz`。
- `vkryl/leveldb/build.gradle.kts`：读取 `app.abis` 并设置 `ndk.abiFilters`。
- 构建产物：包名、版本号、ABI、语言、签名符合预期。
- 真机运行：`/sdcard/TelegramZ` 生成媒体缓存和 `.nomedia`。

## 注意事项

- 不要提交 `.codex-tools/`、本地 keystore、`local.properties` 或其他本机密钥材料。
- 同步上游 `main` 时不要覆盖用户未提交改动；恢复前先查看 `git status` 和子模块状态。
- `vkryl/leveldb` 的 ABI 修复需要单独检查，因为它是子模块内提交。
- 如果 Android 11 及以上没有授予 all-files access，`/sdcard/TelegramZ` 可能不可用；实现必须能 fallback，避免 TDLib 初始化失败。
- `TgxCacheDocumentsProvider.java` 当前不是 `/sdcard/TelegramZ` 的路径控制点，不要围绕它修改 TDLib 媒体缓存目录。
