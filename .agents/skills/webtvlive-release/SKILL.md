---
name: webtvlive-release
description: 构建、验签、提交、打标签，并将 WebTvLive Android 正式版同步发布到 Gitee 和 GitHub，同时维护四种内核/ABI APK 与 Gitee 在线更新清单。当用户要求发布、发版、上线或验证 WebTvLive 新版本，上传 APK，创建 Gitee/GitHub Release，或核对发布状态时使用。
---

# 发布 WebTvLive

将签名后的 WebTvLive 正式包同步发布到 Gitee 和 GitHub，并保持应用内更新只使用 Gitee Release 下载地址。把整个发版过程视为事务：所有 Gitee APK 上传并验证完成之前，不得让新版在线更新清单生效。

## 确认发版状态

1. 默认在 `/Users/bytedance/projects/WebTvLive` 工作；如果用户指定其他检出目录，则使用用户提供的目录。
2. 读取仓库的 `AGENTS.md`、`app/build.gradle.kts`、`release/update.json` 和 `scripts/prepare-gitee-release.sh`；以仓库说明和脚本为唯一事实源。
3. 检查 `git status --short`、当前分支、最近标签和两个远端。保留用户的无关改动；无法安全隔离时停止发版并说明原因。
4. 检查 `gitee auth status` 和 `gh auth status`。确认目标标签及两边 Release 尚不存在；若已存在，先检查现状并补齐缺失步骤，不要删除或重新创建。
5. 使用用户指定的版本。未指定时，只递增 `versionName` 的 patch 位，并将 `versionCode` 加一。标签固定为 `v<versionName>`。
6. 根据上一个标签以来的实际差异撰写简洁中文发布说明，包含 APK 选择说明和项目既有合规声明。

## 构建与准备

1. 修改 `app/build.gradle.kts` 中的 `versionCode` 和基础 `versionName`。不要手工添加 flavor 后缀；Gradle 会自动添加 `-gecko` 和 `-webview`。
2. 使用 Android Studio JBR 执行完整校验：

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew \
  :app:testGeckoDebugUnitTest \
  :app:testWebviewDebugUnitTest \
  :app:lintGeckoDebug \
  :app:lintWebviewDebug \
  :app:assembleRelease
```

3. 将发布说明写入临时文件，执行：

```bash
./scripts/prepare-gitee-release.sh --notes <发布说明文件>
```

该命令必须生成 `release/update.json`，并验证以下四个签名 APK：

- `app-gecko-arm64-v8a-release.apk`
- `app-gecko-armeabi-v7a-release.apk`
- `app-webview-arm64-v8a-release.apk`
- `app-webview-armeabi-v7a-release.apk`

4. 确认清单版本与 Gradle 一致，所有资源 URL 均位于 `https://gitee.com/lipengzhou/WebTvLive/releases/download/v<version>/`。确认四个本地 APK 的文件大小和 SHA-256 与清单一致。

## 提交并暂存发布状态

1. 只暂存本次发版文件，执行 `git diff --cached --check`，使用 `Release 0.0.4` 这类简短提交信息。如果拆分清单提交能明显提高安全性或可读性，可额外使用 `Publish 0.0.4 update manifest`。
2. 在包含最终更新清单的提交上创建附注标签 `v<version>`。不得移动已经发布到远端的发版标签。
3. 此时两个远端的 `main` 都应保持旧版本，只先将新标签推送到 Gitee 和 GitHub。这样可以创建 Release，同时公开的 raw 更新清单仍指向旧版本。

## 先发布 Gitee

1. 使用 `gitee release create` 创建正式 Release，目标提交必须是最终提交。通过 `gitee release view --json=id` 获取数字 Release ID。
2. 依次通过官方接口上传四个 APK：

```text
POST /api/v5/repos/lipengzhou/WebTvLive/releases/<release-id>/attach_files
multipart 字段：access_token、file
```

使用 `gitee auth token` 将令牌读入 shell 变量。禁止打印令牌、开启 shell 跟踪、将令牌写入文件或让令牌出现在日志中；上传结束后立即 `unset`。
3. 通过 API 查询附件，忽略 Gitee 自动生成的源码压缩包，并要求 APK 名单与预期四个文件完全一致。
4. 执行 `./scripts/prepare-gitee-release.sh --verify-remote`。要求每个 Gitee 资源的 URL、字节数、SHA-256、包名、版本和签名证书全部通过。任一项失败都不得推送 `main`。

## 发布 GitHub 并启用在线更新

1. 使用相同标签、标题、发布说明和四个完全相同的 APK 创建 GitHub Release。首次发布使用 `gh release create`；恢复失败任务时，只对确认缺失或错误的资源使用 `gh release upload --clobber`。
2. 确认 GitHub Release 为正式版且恰好包含四个目标 APK。将附件下载到 `mktemp -d` 创建的临时目录，逐个对照 `release/update.json` 校验 SHA-256。
3. 只有 Gitee 与 GitHub 两边 Release 都完整后，才将最终 `main` 推送到 Gitee 和 GitHub。此操作会让 Gitee raw 更新清单正式生效。
4. 使用防缓存查询参数轮询以下公开 raw URL，直到返回新版本，然后与本地 `release/update.json` 做逐字节比较：

```text
https://gitee.com/lipengzhou/WebTvLive/raw/main/release/update.json
```

5. 确认本地 `HEAD`、两个远端的 `main`、两个远端解引用后的 tag 均指向同一提交，并确认工作区干净。

## 故障恢复与完成标准

- 禁止发布 debug APK，禁止上传 universal APK 或错误内核的 APK。
- 发布清单哈希后禁止重新生成签名 APK。APK 签名可能使重新构建的文件字节不同；应保留或重新下载已发布的原始文件。
- 上传中断时保留已有 Release 和标签，查询现有附件，只补传缺失或损坏的文件。恢复完成前不得公开新版 Gitee 清单。
- Gitee `main` 推送后 raw 地址短暂返回 404 或旧内容时，先通过 Gitee Contents API 确认文件存在，再进行有时限的轮询。
- 不得为了测试流程而发布新版本；需要验证时使用只读检查或临时本地构建。
- 最终报告提交 SHA、版本和标签、Gitee/GitHub Release 链接、四包校验结果、测试与构建结果、在线清单状态和工作区状态。若上一版本尚无更新器，说明首个带更新器的版本需要用户手动安装一次。
