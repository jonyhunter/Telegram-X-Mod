你正在修改开源 Android 项目 `TGX-Android/Telegram-X`，仓库地址：https://github.com/TGX-Android/Telegram-X。

用户母语是简体中文。除功能代码、系统 API、第三方库名称、协议字段、配置键名、包名、命名空间、英文日志、专业术语、技能名称等必须保持原文的内容外，所有对外可见内容均使用简体中文。

## 最终目标

通过源码级修改，将 TDLib 外部媒体缓存目录固定迁移到：

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

多账号目录继续使用 TDLib/Telegram X 现有规则：

```text
/sdcard/TelegramZ/x_account1/
/sdcard/TelegramZ/x_account2/
```

只迁移 TDLib 的 `filesDirectory`。`databaseDirectory` 必须继续保留在应用内部私有目录，不允许迁到 `/sdcard/TelegramZ`。

## TDLib 目录修改方法

### 1. 启用所有文件访问权限

在 `app/src/main/AndroidManifest.xml` 中声明：

```xml
<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE"
  tools:ignore="ScopedStorage" />
```

### 2. 修改 TdlibManager

修改文件：

```text
app/src/main/java/org/thunderdog/challegram/telegram/TdlibManager.java
```

`getTdlibDirectory(int accountId, boolean allowExternal, boolean createIfNotFound)` 的最终行为：

- `allowExternal == true` 时使用 `/sdcard/TelegramZ`。
- Android 11+ 必须满足 `Environment.isExternalStorageManager()`。
- 根目录创建后写入 `.nomedia`。
- 多账号目录使用 `x_account{accountId}`，账号目录内也写入 `.nomedia`。
- `allowExternal == false` 时保持原内部数据库目录逻辑。
- 当没有 all-files access 时保留原有 fallback，防止 TDLib 初始化失败。

参考实现：

```java
public static String getTdlibDirectory (int accountId, boolean allowExternal, boolean createIfNotFound) {
  File file = allowExternal ? getTelegramZDirectory() : null;
  if (file != null) {
    try {
      File externalStorageDirectory = Environment.getExternalStorageDirectory();
      if (externalStorageDirectory != null && file.getAbsolutePath().startsWith(externalStorageDirectory.getAbsolutePath())) {
        String state = Environment.getExternalStorageState();
        if (!Environment.MEDIA_MOUNTED.equals(state)) {
          file = null;
        }
      }
    } catch (Throwable t) {
      t.printStackTrace();
    }
  }
  if (file != null) {
    try {
      if (!FileUtils.createDirectory(file) || !file.canWrite()) {
        file = null;
      } else {
        createNoMediaFile(file);
      }
    } catch (SecurityException e) {
      e.printStackTrace();
      file = null;
    }
  }
  if (file == null && allowExternal) {
    file = UI.getAppContext().getExternalFilesDir(null);
    if (file != null) {
      try {
        if (!FileUtils.createDirectory(file) || !file.canWrite()) {
          file = null;
        }
      } catch (SecurityException e) {
        e.printStackTrace();
        file = null;
      }
    }
  }
  if (file != null) {
    if (accountId != 0) {
      file = new File(file, "x_account" + accountId);
      if (!file.exists()) {
        if (createIfNotFound) {
          if (!FileUtils.mkdirs(file))
            throw new DeviceStorageError("Could not create external working directory: " + file.getPath());
        } else {
          return null;
        }
      }
      createNoMediaFile(file);
    }
  } else {
    if (allowExternal && !createIfNotFound)
      return null;
    file = new File(UI.getContext().getFilesDir(), accountId != 0 ? "tdlib" + accountId : "tdlib");
    if (!file.exists()) {
      if (createIfNotFound) {
        if (!FileUtils.mkdirs(file))
          throw new DeviceStorageError("Cannot create working directory: " + file.getPath());
      } else {
        return null;
      }
    }
  }
  return TD.normalizePath(file.getPath());
}

private static File getTelegramZDirectory () {
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
    return null;
  }
  File externalStorageDirectory = Environment.getExternalStorageDirectory();
  return externalStorageDirectory != null ? new File(externalStorageDirectory, "TelegramZ") : null;
}

private static void createNoMediaFile (File directory) {
  try {
    File noMedia = new File(directory, ".nomedia");
    if (!noMedia.exists()) {
      noMedia.createNewFile();
    }
  } catch (Throwable t) {
    Log.w("Cannot create .nomedia file in %s", directory);
  }
}
```

### 3. 保持 Tdlib.java 参数赋值

`app/src/main/java/org/thunderdog/challegram/telegram/Tdlib.java` 中保持：

```java
parameters.databaseDirectory = TdlibManager.getTdlibDirectory(accountId, false);
parameters.filesDirectory = TdlibManager.getTdlibDirectory(accountId, !isService);
```

## 权限授权与真机验证

安装新版后必须授予 all-files access。ADB 命令：

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

成功状态：

```text
/sdcard/TelegramZ/.nomedia
/sdcard/TelegramZ/photos/.nomedia
/sdcard/TelegramZ/temp/.nomedia
/sdcard/TelegramZ/x_account1/.nomedia
/sdcard/TelegramZ/photos/<tdlib media files>
```

## 构建配置

`.codex-tools/` 目录下有编译使用的 JDK 和签名文件。

JDK 根目录：

```text
G:\TelegramZ\.codex-tools\jdk21\jdk-21.0.11+10
```

`local.properties` 必须包含：

```properties
app.id=app.jony.tgz
app.name=Telegram Z
app.abis=arm64
app.languages=en,zh-hans
app.output_version_code=1789000
keystore.file=G\:\\TelegramZ\\.codex-tools\\signing\\release-keystore.properties
```

`app/google-services.json` 中所有 Android `package_name` 必须是：

```text
app.jony.tgz
```

## 只编译 arm64

Gradle 必须读取 `app.abis=arm64` 并禁用非 arm64 变体。

`app/build.gradle.kts` 的 `androidComponents.beforeVariants` 必须包含 ABI 过滤：

```kotlin
(config.abiFlavors.isEmpty() || config.abiFlavors.contains(abiFlavor))
```

`vkryl/leveldb/build.gradle.kts` 也必须读取 `local.properties` 的 `app.abis`，并设置：

```kotlin
ndk.abiFilters.addAll(selectedAbiFilters)
```

最终构建日志中不得出现：

```text
armeabi-v7a
x86
x86_64
arm32
x64
universal
```

## 编译命令

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

`validateApiTokens` 需要真实 Firebase 控制台为 `app.jony.tgz` 生成的 `google-services.json`。本地使用旧 Firebase App ID 编译 release 时跳过该任务。

## APK 验证命令

```powershell
D:\Android\SDK\build-tools\37.0.0\apksigner.bat verify --verbose G:\TelegramZ\app\build\outputs\apk\latestArm64\release\Telegram-Z-0.28.9.1788-arm64-v8a.apk
D:\Android\SDK\build-tools\37.0.0\aapt.exe dump badging G:\TelegramZ\app\build\outputs\apk\latestArm64\release\Telegram-Z-0.28.9.1788-arm64-v8a.apk
```

期望结果：

```text
package name: app.jony.tgz
versionCode: 1789000
native-code: arm64-v8a
locales: --_--, zh
signature: APK Signature Scheme v2 verified
```
