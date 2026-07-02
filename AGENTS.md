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
- provider 功能相关的长期代码主要在：
  - `app/src/main/java/org/thunderdog/challegram/provider/TgxCacheDocumentsProvider.java`
  - `app/src/main/AndroidManifest.xml`
- 如果官方更新改动了 `AndroidManifest.xml`，rebase 时需要特别确认 provider 注册仍然存在。
