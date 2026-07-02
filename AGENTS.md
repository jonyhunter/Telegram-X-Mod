# AGENTS.md

## 项目说明

本仓库基于官方 `TGX-Android/Telegram-X` 维护本地修改。

当前长期维护分支：

```text
tgx-cache-provider
```

本分支包含的核心自定义功能：

- 新增 `TgxCacheDocumentsProvider`
- 在 `AndroidManifest.xml` 注册 SAF / `DocumentsProvider`
- 让支持 Android SAF 的文件管理器可以浏览并删除 Telegram X 外部媒体缓存目录

## Remote 约定

```text
origin   = https://github.com/jonyhunter/Telegram-X-Mod.git
upstream = https://github.com/TGX-Android/Telegram-X.git
```

`origin` 是自己的 fork，可以推送。

`upstream` 是官方只读仓库，只用于拉取更新。不要向 `upstream` 推送。

当前已将 `upstream` 的 push 地址设置为无效地址，避免误推：

```text
DISABLED_PUSH_TO_UPSTREAM
```

## 同步前清理工作区

在同步 `upstream/main` 或执行 `rebase` 前，建议先确认工作区是否干净：

```powershell
cd G:\TelegramX-Mod\Telegram-X

git status --short
```

如果只有本机构建/生成状态，例如 `ffmpeg`、`libvpx`、`opus`、Emoji 生成文件或 `vkryl/td` 行尾变化，可以按以下命令清理：

```powershell
cd G:\TelegramX-Mod\Telegram-X

git restore app/src/main/java/org/thunderdog/challegram/tool/EmojiBidi.kt
git restore app/src/main/java/org/thunderdog/challegram/tool/EmojiBidiLegacy.kt
git restore app/src/main/java/org/thunderdog/challegram/tool/Emojis.kt

git -C vkryl\td restore src/main/kotlin/tgx/td/TdCompileAssert.kt
git -C vkryl\td restore src/main/kotlin/tgx/td/TdEqualsTo.kt
git -C vkryl\td restore src/main/kotlin/tgx/td/TdUnsupported.kt

git -C app\jni\third_party\ffmpeg clean -fdx
git -C app\jni\third_party\libvpx clean -fdx

git -C app\jni\third_party\opus restore celt/arm/celt_pitch_xcorr_arm.s
git -C app\jni\third_party\opus clean -fdx
```

清理后再次确认：

```powershell
git status --short
```

注意：清理 `ffmpeg`、`libvpx`、`opus` 会删除本地 native 构建缓存和生成文件。之后如果重新全量构建 APK，可能需要重新运行相关 native patch/build 脚本，构建耗时会明显增加。

Windows 下重新运行 Opus patch 后，需要特别检查生成的汇编 include 行。如果 `celt_pitch_xcorr_arm_gnu.s` 中出现类似 `.include "celt/arm/armopts_gnu.s\r"` 的 CR 字符残留，CMake/clang 会报 `Could not find include file 'celt/arm/armopts_gnu.s'`。可用以下命令移除生成文件中的 CR 字符：

```powershell
& 'C:\msys64\usr\bin\bash.exe' -lc 'cd /g/TelegramX-Mod/Telegram-X && perl -pi -e "s/\r//g" app/jni/third_party/opus/celt/arm/celt_pitch_xcorr_arm_gnu.s app/jni/third_party/opus/celt/arm/armopts_gnu.s'
```

## 同步官方更新

当官方 `TGX-Android/Telegram-X` 有新提交时，按以下流程把官方更新合并到当前项目，同时保留本地 provider 功能：

```powershell
cd G:\TelegramX-Mod\Telegram-X

git fetch upstream
git switch tgx-cache-provider
git rebase upstream/main
```

如果 rebase 过程中没有冲突，继续推送到自己的 fork：

```powershell
git push --force-with-lease origin tgx-cache-provider
```

## 处理 rebase 冲突

如果出现冲突：

```powershell
git status
```

手动编辑冲突文件，保留官方更新，同时保留本地 provider 功能。

解决后继续：

```powershell
git add <冲突文件>
git rebase --continue
```

如果需要放弃本次同步：

```powershell
git rebase --abort
```

rebase 完成后再推送：

```powershell
git push --force-with-lease origin tgx-cache-provider
```

## 构建验证

同步官方更新后建议重新构建：

```powershell
cd G:\TelegramX-Mod\Telegram-X

$env:JAVA_HOME=(Get-ChildItem -Directory '.codex-tools\jdk21' | Select-Object -First 1 -ExpandProperty FullName)
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :app:assembleLatestUniversalDebug --console=plain
```

构建成功后 APK 通常位于：

```text
G:\TelegramX-Mod\Telegram-X\app\build\outputs\apk\latestUniversal\debug\
```

## 本地 Release 打包

当前本地 release APK 推荐只打 `arm64-v8a` 架构：

```powershell
cd G:\TelegramX-Mod\Telegram-X

$env:JAVA_HOME=(Get-ChildItem -Directory '.codex-tools\jdk21' | Select-Object -First 1 -ExpandProperty FullName)
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :app:assembleLatestArm64Release -x :app:validateApiTokens --console=plain
```

产物通常位于：

```text
G:\TelegramX-Mod\Telegram-X\app\build\outputs\apk\latestArm64\release\
```

`-x :app:validateApiTokens` 用于跳过当前本地包名 `app.jony.tgz` 与仓库内 `google-services.json` 不匹配导致的 Firebase / Google Services 校验。这样可以打出非实验 release APK；但如果没有为 `app.jony.tgz` 准备真实 `google-services.json`，推送通知相关能力可能不可用。

## 敏感配置

不要提交以下内容：

- `local.properties`
- Telegram `api_id` / `api_hash`
- keystore / 签名文件
- APK 构建产物
- `build/`、`.gradle/`、`.cxx/`

`local.properties` 已被 `.gitignore` 忽略，本地 Telegram API 参数只应保存在该文件中。

## 注意事项

- 不要把 native 子模块中的构建生成文件提交到 provider 功能提交中。
- 清理 native 子模块后，如果构建再次遇到缺少 Opus 汇编或 ffmpeg/libvpx 静态库，需要重新执行项目脚本生成这些本地构建产物。
- Windows 下重新执行 Opus patch 后，如果 native 编译报找不到 `armopts_gnu.s`，优先检查生成汇编文件中的 CR 字符残留，并按上文命令清理。
- provider 功能相关的长期代码主要在：
  - `app/src/main/java/org/thunderdog/challegram/provider/TgxCacheDocumentsProvider.java`
  - `app/src/main/AndroidManifest.xml`
- 如果官方更新改动了 `AndroidManifest.xml`，rebase 时需要特别确认 provider 注册仍然存在。
