# AGENTS.md — WebTvLive AI 协作约束

面向在本仓库工作的 AI/协作者，记录「不看代码容易犯错」和「违反后会出问题」的规则。

配套文档，避免在此重复：

- `README.md`：面向用户的功能、完整遥控器/触屏交互表、真机注意事项。
- `.agents/skills/webtvlive-release/SKILL.md`：正式发版的权威流程（Gitee + GitHub 同步发布）。

## 项目速览

一款「双内核安卓直播电视 App」：嵌套展示电视台**官方公开直播网页**（当前为央视频），用页面适配脚本把网页 `<video>` 铺满全屏，做成「像传统电视一样换台」。语言 Kotlin，原生 XML View（**不用 Compose**），Gradle Kotlin DSL + Version Catalog（`gradle/libs.versions.toml`）。

内核以 flavor 隔离：`gecko`（随 APK 分发 GeckoView，兼容旧电视）、`webview`（用系统 WebView，包更小）。

## 项目结构与模块职责

`MainActivity` 已收敛为「生命周期 + 浏览器承载 + 顶层输入路由 + 播放 effect 执行」，具体行为下沉到各控制器/协调器。改动前先定位到对应模块，不要把逻辑再堆回 Activity：

- `app/src/main/java/com/lipengzhou/webtvlive/`（共享层）
  - `BrowserEngine.kt` / `BrowserProtocol.kt`：双内核共享接口与**版本化**原生↔页面消息协议。
  - `PlaybackCoordinator.kt`：换台/超时/重试/回退状态机（纯逻辑，注入时钟）。
  - `ChannelMenuController.kt` / `SettingsPanelController.kt` / `PlaybackTouchController.kt` / `AppUpdateController.kt`：频道菜单+节目单、右侧设置、触屏覆盖层、更新安装。
  - `PanelCoordinator.kt` / `TouchGestureInterpreter.kt` / `UpdateCheckCoordinator.kt`：可单测的纯状态/判定。
  - `TvCatalog.kt`：内置频道目录；`Channel.siteName` **必须与央视频页面频道名完全一致**。
  - `TrustedWebContent.kt`：主框架导航 allowlist（安全边界，见下）。
- `app/src/gecko/…/FlavorBrowserEngine.kt`、`WebTvLiveApplication.kt`：GeckoView 内核 + 进程级 `GeckoRuntime` 创建与 `warmUp()`。
- `app/src/webview/…/FlavorBrowserEngine.kt`：系统 WebView 内核。
- `app/src/main/assets/webextension/protocol.js`、`player_adapter.js`：两内核共用协议与页面播放器适配脚本（Gecko 经 WebExtension 注入，WebView 经 `evaluateJavascript` 注入）。
- `app/src/gecko/assets/webextension/manifest.json`、`request_filter.js`：Gecko 专用 WebExtension 清单与请求过滤后台脚本。

完整文件清单见 `README.md` 的「关键文件」表。

## 技术栈与关键版本

| 项 | 值 |
| --- | --- |
| AGP | 9.3.2（脚手架已内置 Kotlin 插件） |
| minSdk / targetSdk / compileSdk | 28 / 37 / 37 |
| GeckoView | 153.0（仅进入 `gecko` flavor） |
| 源码 Java/Kotlin 字节码目标 | **17** |
| Gradle Daemon JVM | **25**（`gradle/gradle-daemon-jvm.properties`，Foojay 解析） |

字节码目标 17 与 Gradle 运行时 JVM 25 并存是有意为之，不是 bug：准备 JDK 17 不代表 Gradle 用 17。构建异常时先 `./gradlew -version` 看实际 Launcher/Daemon JVM。

## 常用命令

macOS 上用 Android Studio 内置 JBR 作 `JAVA_HOME` 最稳：

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

# 快速验证编译（按 flavor）
./gradlew :app:compileGeckoDebugKotlin -q
./gradlew :app:compileWebviewDebugKotlin -q

# 唯一质量门禁：Shell/JS/JSON 语法 + node --test + 签名门禁 + 双 flavor 单测/Lint + 四个 Debug APK + git diff --check
./scripts/verify.sh

# 打包（按 内核 flavor × ABI 分包，无 universal APK）
./gradlew :app:assembleDebug -q      # 本地/模拟器调试用
./gradlew :app:assembleRelease -q    # 正式分发用，需先配置签名（见发版）
# 产物：app/build/outputs/apk/{gecko,webview}/{debug,release}/app-{flavor}-{abi}-{type}.apk
```

`verify.sh` 是本地、CI（`.github/workflows/ci.yml`）和发版脚本的统一入口。**不要**另拼一套 Gradle 校验任务；要加检查就加进 `verify.sh`。注意它**不跑** androidTest（`MainActivitySmokeTest` 需手动/真机验证）。

### 模拟器/真机调试（等效遥控器）

模拟器无实体遥控器，用 `adb ... input keyevent` 触发 `MainActivity.dispatchKeyEvent`。**多设备必须带 `-s <serial>`。** 完整 adb 触屏/按键清单见 `README.md`，最小验证环：

```bash
adb -s <serial> install -r app/build/outputs/apk/webview/debug/app-webview-arm64-v8a-debug.apk
adb -s <serial> shell monkey -p com.lipengzhou.webtvlive.debug -c android.intent.category.LAUNCHER 1
adb -s <serial> shell input keyevent 19   # 19/20=换台 23=开菜单/确定 82=设置 4=返回(2s内两次退出)
adb -s <serial> exec-out screencap -p > /tmp/webtv.png   # 换台时左上角有频道名浮层可核对
adb -s <serial> logcat -d | grep -i "WebTvLive\|Yangshipin\|Gecko\|MediaCodec"
```

安装的是 **Debug** 包（`com.lipengzhou.webtvlive.debug`，应用名「看电视 Debug」），与 Release 沙箱隔离、可并存。

## 质量门禁硬约束

- **Lint = 错误**：`warningsAsErrors=true`、`abortOnError=true`，基线在 `app/lint-baseline.xml`。新增告警会让构建失败。修不掉时优先修代码或做**带注释的精准抑制**；不要为省事无脑扩大 baseline（治理方向是收缩 baseline，不是膨胀）。
- `verify.sh` 会 `git diff --check`：不要提交行尾空白/冲突标记。
- `org.gradle.configuration-cache=true`：新增 Gradle 逻辑注意 configuration-cache 兼容性。

## 关键架构约束与踩坑

- **协议是版本化契约，且双端都测**：`BrowserProtocol`（Kotlin `BrowserProtocolTest`）与 `scripts/browser-protocol.test.js`（页面侧 `protocol.js`）共用同一套语义。改消息名/字段/`VERSION` 必须同步 Kotlin 端、`player_adapter.js` 和两个 flavor 的解析，并让两端测试都过；否则真机才暴露。
- **换台以真实播放为准**：发页内换台指令即显示全屏遮罩；只有 WebExtension 确认新 `<video>` 或复用 `<video>` 触发新一轮 `playing`、且携带本次 `requestId` 时才隐藏遮罩。绝不能让旧频道/过期请求提前解遮罩。
- **播放器发现是事件驱动**：`player_adapter.js` 用 `MutationObserver` + 媒体事件，不做 500ms 全量轮询；只在官网改写受控节点样式时定向修复。全屏关键手法：清祖先 `transform`、写 `!important` 内联样式、隐藏非播放器顶层节点、不开 `useWideViewPort/loadWithOverviewMode`。
- **菜单导航不走系统焦点**：方向键被 `dispatchKeyEvent` 提前吞掉，进不了 RecyclerView；高亮按外部下标手动渲染。别指望 Android 焦点系统驱动菜单。
- **内核失败要闭环**：初始化失败/主框架失败/内容进程崩溃/导航阻断都是显式事件，最多自动重建一次内核，再失败进入「重试/退出」错误态。新增内核逻辑不要退回「只记日志、无限超时重试」。
- **ABI 分包**：32 位电视装 `armeabi-v7a`，ARM64 模拟器/设备装 `arm64-v8a`；系统 WebView 很旧（如 Chromium 66）优先用 `gecko`。
- Activity 固定横屏（`sensorLandscape`）；模拟器锁竖屏会显示异常。
- 不要手动加 `org.jetbrains.kotlin.android`（AGP 9 已内置，会报 `extension 'kotlin' already registered`）。

## 安全红线（技术）

以下是已落地的最小权限边界，**不得为图方便放宽**：

- 主框架导航 allowlist：`TrustedWebContent` 只放行 `https://www.yangshipin.cn/tv/home`；Gecko 与 WebView 都执行。
- WebView：`usesCleartextTraffic="false"`、`MIXED_CONTENT_NEVER_ALLOW`、`allowFileAccess=false`、`allowContentAccess=false`；bridge 用每次导航刷新的随机 token + 消息 schema/大小校验，仅在受信任主框架生效。
- 改动播放桥接或注入逻辑时保留上述校验，并考虑补安全回归（HTTP URL、非允许 host、伪造事件、超大消息均不能改变播放成功状态）。

## 发版规则

完整流程（构建→验签→提交→打标签→Gitee/GitHub 双发布→在线清单生效）以 `webtvlive-release` skill 为准。此处只记不变量：

- 版本：`0.0.x`，默认只递增 patch；`versionCode` 每次 +1；tag 固定 `v<versionName>`（`app/build.gradle.kts`）。
- 只发 **release** APK（四个：`{gecko,webview}` × `{arm64-v8a,armeabi-v7a}`），禁发 debug/universal/错内核包。
- 签名从环境变量 / Gradle property / 被 Git 忽略的 `local.properties` 读取：`WEBTVLIVE_RELEASE_STORE_FILE`、`..._STORE_PASSWORD`、`..._KEY_ALIAS`、`..._KEY_PASSWORD`。缺失会让 release 任务主动失败。
- 应用内更新清单 `release/update.json` 的下载链接**只用** Gitee Release（`https://gitee.com/lipengzhou/WebTvLive/releases/download/v<versionName>/...`）。
- **清单最后发布**：四个 APK 全部上传并远端校验通过（`prepare-gitee-release.sh --verify-remote`）之前，不得让 raw 更新清单指向新版本。
- 发版提交/标签不夹带无关本地改动（如 `.idea/*`）。

## 合规红线

只嵌套展示官网公开直播页，不采集/存储/转发视频流；不破解登录/付费/清晰度限制；保留《免责声明》与侵权删除通道；非营利声明。

## AI Agent 必守规则（速查）

1. 用 `./scripts/verify.sh` 验证，不要另造校验；改检查就改脚本本身。
2. 改协议同步「Kotlin 协议 + `player_adapter.js` + 两个 flavor 解析 + 两端测试」。
3. 不放宽 allowlist / 明文 / 混合内容 / bridge 校验等安全边界。
4. Lint 零新增告警；抑制要带原因，不要无脑扩 baseline。
5. 新逻辑放进对应控制器/协调器，别堆回 `MainActivity`。
6. 发版走 `webtvlive-release` skill，遵守「只发签名 release、清单最后发布」。
7. 无法确认的行为先读 `README.md` 或对应源码，不臆造规则。
