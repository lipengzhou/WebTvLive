# AGENTS.md — WebTvLive 协作与调试说明

本文件面向在本仓库工作的 AI/协作者，记录经过真机/模拟器验证的构建与调试流程，避免重复踩坑。

## 项目速览

- 一款「双内核安卓直播电视 App」：新系统可用系统原生 WebView 小包，老系统可用 GeckoView 稳定包。两种内核都打开各电视台**官方直播网页**，通过页面适配脚本把网页 `<video>` 铺满全屏，做成「像传统电视一样换台」的体验。
- 语言 Kotlin，构建 Kotlin DSL + Version Catalog（`gradle/libs.versions.toml`），原生 XML View（不用 Compose）。
- 核心文件：
  - `app/src/main/java/com/lipengzhou/webtvlive/MainActivity.kt`：共享频道/菜单/设置/超时恢复/遥控器按键逻辑。
  - `app/src/main/java/com/lipengzhou/webtvlive/BrowserEngine.kt`：共享浏览器内核接口。
  - `app/src/gecko/java/com/lipengzhou/webtvlive/`：GeckoView 内核实现、进程级 GeckoRuntime 创建与首次页面预热。
  - `app/src/webview/java/com/lipengzhou/webtvlive/`：系统原生 WebView 内核实现。
  - `app/src/main/assets/webextension/player_adapter.js`：两种内核共用的页面播放器适配脚本；Gecko 通过 WebExtension 注入，WebView 通过 `evaluateJavascript` 注入。
  - `app/src/gecko/assets/webextension/manifest.json` / `request_filter.js`：GeckoView 专用内置 WebExtension 清单和请求过滤后台脚本。
  - `app/src/main/res/layout/activity_main.xml`：黑底 FrameLayout + 浏览器容器 + 频道名浮层。

## 构建（命令行）

macOS 上用 **Android Studio 内置 JBR** 作 `JAVA_HOME` 最稳（系统 JDK 版本常不匹配 AGP 9）：

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

# 只编译 Kotlin（快速验证语法/编译）
./gradlew :app:compileGeckoDebugKotlin -q
./gradlew :app:compileWebviewDebugKotlin -q

# 打 debug APK（本地开发/模拟器调试用，按内核 flavor + ABI 分包）
./gradlew :app:assembleDebug -q
# GeckoView 32 位：app/build/outputs/apk/gecko/debug/app-gecko-armeabi-v7a-debug.apk
# GeckoView 64 位：app/build/outputs/apk/gecko/debug/app-gecko-arm64-v8a-debug.apk
# 原生 WebView 32 位：app/build/outputs/apk/webview/debug/app-webview-armeabi-v7a-debug.apk
# 原生 WebView 64 位：app/build/outputs/apk/webview/debug/app-webview-arm64-v8a-debug.apk

# 打 release APK（GitHub Release/正式分发用，需要先配置签名）
./gradlew :app:assembleRelease -q
# GeckoView 32 位：app/build/outputs/apk/gecko/release/app-gecko-armeabi-v7a-release.apk
# GeckoView 64 位：app/build/outputs/apk/gecko/release/app-gecko-arm64-v8a-release.apk
# 原生 WebView 32 位：app/build/outputs/apk/webview/release/app-webview-armeabi-v7a-release.apk
# 原生 WebView 64 位：app/build/outputs/apk/webview/release/app-webview-arm64-v8a-release.apk
```

Release APK 启用 R8/资源优化，并通过 `useLegacyPackaging=true` 压缩 APK 内的 native
`.so`；产物仍是可直接安装的标准 APK。Android 安装时会把 native 库解压到应用目录，
所以安装后的磁盘占用会高于 APK 下载大小。Debug APK 保持默认的非压缩 native 库打包方式。

## 在模拟器/真机上调试（无遥控器时的等效操作）

模拟器没有实体遥控器，用 `adb ... input keyevent` 发按键即可等效触发 `MainActivity.onKeyDown`。**同时连了多台设备时必须用 `-s <serial>` 指定目标。**

```bash
# 0) 列设备；分辨哪台是目标（例如 Xiaomi 模拟器）
adb devices
adb -s 127.0.0.1:5555 shell getprop ro.product.manufacturer   # -> Xiaomi
adb -s 127.0.0.1:5555 shell getprop ro.build.version.sdk       # -> 32

# 1) 安装本地调试包（覆盖安装保留数据）
adb -s 127.0.0.1:5555 install -r app/build/outputs/apk/webview/debug/app-webview-arm64-v8a-debug.apk

# 2) 启动 app
adb -s 127.0.0.1:5555 shell monkey -p com.lipengzhou.webtvlive -c android.intent.category.LAUNCHER 1

# 3) 确认已在前台
adb -s 127.0.0.1:5555 shell dumpsys activity activities | grep -i topResumedActivity
#   期望包含 com.lipengzhou.webtvlive/.MainActivity

# 4) 模拟遥控器换台（对应 MainActivity 的按键映射）
adb -s 127.0.0.1:5555 shell input keyevent 19    # DPAD_UP   = 下一个频道（cctv1 -> cctv2 …）
adb -s 127.0.0.1:5555 shell input keyevent 20    # DPAD_DOWN = 上一个频道
#   频道键同样有效：166 = CHANNEL_UP，167 = CHANNEL_DOWN
#   返回键：4 = BACK（2 秒内按两次退出）

# 5) 连发验证首尾循环（如 CCTV-17 再按上应回到 CCTV-1）
for i in $(seq 1 6); do adb -s 127.0.0.1:5555 shell input keyevent 19; sleep 3; done

# 6) 截图核对（换台时左上角有 3 秒频道名浮层，可据此确认当前台）
adb -s 127.0.0.1:5555 exec-out screencap -p > /tmp/webtv.png
```

排查页面/播放问题时看 GeckoView 和系统媒体日志：

```bash
adb -s 127.0.0.1:5555 logcat -c                              # 先清空
# 触发操作后
adb -s 127.0.0.1:5555 logcat -d | grep -i "WebTvLive\|cctv\|Gecko\|MediaCodec"
```

## 发版规则

- 版本号使用 `0.0.x` 小版本递增策略；如无特殊说明，每次发版只递增最后一位 patch 号。例如 `0.0.1` 的下一版是 `0.0.2`。
- `versionCode` 每次正式发版递增 1。
- Git tag 使用 `v<versionName>` 格式，例如 `v0.0.1`。
- 本地开发、模拟器调试用 `debug` APK；GitHub Release/正式分发只上传 `release` APK。
- release 签名信息从环境变量、Gradle property 或已被 Git 忽略的 `local.properties` 读取：
  - `WEBTVLIVE_RELEASE_STORE_FILE`
  - `WEBTVLIVE_RELEASE_STORE_PASSWORD`
  - `WEBTVLIVE_RELEASE_KEY_ALIAS`
  - `WEBTVLIVE_RELEASE_KEY_PASSWORD`
- 发版前至少执行：

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testGeckoDebugUnitTest :app:testWebviewDebugUnitTest :app:assembleRelease -q
```

- GitHub Release 需要附带 release notes，并上传本次构建出的 APK。默认上传 `gecko` 和 `webview` 两个 flavor 的 arm64-v8a / armeabi-v7a release APK。
- 发版提交、tag 和 release 不应包含无关本地改动，例如 `.idea/misc.xml`。

### 交互速查（当前实现）

- **上**（`DPAD_UP` / `CHANNEL_UP`）：标准态=下一个频道（到末尾循环回第一个）；菜单态=当前列上移一项。
- **下**（`DPAD_DOWN` / `CHANNEL_DOWN`）：标准态=上一个频道（到开头循环回最后一个）；菜单态=当前列下移一项。
- **确定**（`DPAD_CENTER` / `ENTER`）：标准态=呼出左侧频道菜单；菜单态在分类列=跳到频道列，在频道列=选中并换台。
- **左/右**（`DPAD_LEFT` / `DPAD_RIGHT`）：标准态屏蔽（防止 WebView 滚动页面/移焦点）；菜单态在「分类列 ↔ 频道列」间切换焦点。
- **返回键**：菜单态=关闭菜单；标准态=2 秒内按两次退出。
- **菜单键**（`MENU` / `SETTINGS` / `TV_CONTENTS_MENU`）：标准态=从右侧呼出系统设置；设置态=关闭设置；频道菜单态=切换到系统设置。
- 首次按最近成功频道的 `pid` 直达 `https://www.yangshipin.cn/tv/home?pid=...`；WebExtension 会核对页面实际选中频道，`pid` 失效时回退到按频道名点击。后续换台仍在当前页面按频道名点击并局部重建播放器。
- 发出页内换台指令时立即显示全屏加载遮罩；WebExtension 必须确认央视频已替换旧 `<video>`，或复用的 `<video>` 触发了新一轮 `playing`，才发送带本次请求 ID 的 `playing` 隐藏遮罩，不能让旧频道或过期请求提前解除遮罩。
- 每次播放器节点创建或换台重建后，WebExtension 会解除静音并持续把 `<video>.volume` 设为 `1`；不主动切换清晰度，使用官网默认/自适应策略。
- WebExtension 不再每 500ms 全量扫描 DOM：频道和播放器发现由 `MutationObserver` 驱动，播放完成由 `playing` 等媒体事件驱动；仅在受控节点样式被官网改写时定向修复。
- WebExtension 后台脚本会保守拦截已确认无关的频道封面、二维码/页脚图片与路由 chunk 推测性预取；频道 API、播放器脚本、WASM、媒体、鉴权、登录和统计请求默认放行。统计请求虽然与播放无关，但直接取消会触发官网未捕获的 Promise/CORS 错误，因此不作为默认优化。命中统计会以 `Resource filter blocked ...` 输出到 `WebTvLive` 日志。

### 侧边频道菜单（M1 首版）

- 确定键呼出，贴屏幕左侧显示，**不遮住右侧视频、视频继续播放**（菜单只是浮层，不碰 GeckoView）。
- 左区=分类列表（当前为 `CCTV`、`卫视`），右区=该分类下频道列表，均垂直滚动。
- 数据源在 `TvCatalog.kt`：`Category(name, channels)` 列表 + 拉平的 `flatChannels`（供上/下换台与「上次频道下标」历史兼容）。`Channel.siteName` 必须与央视频页面频道名完全一致。
- 菜单导航**不走系统焦点**（方向键被 `dispatchKeyEvent` 提前吞掉，进不了 RecyclerView）：`MenuAdapter` 按外部下标渲染高亮——活动列选中行=高亮蓝（`activated`），非活动列选中行=暗选中态（`selected`）。
- 打开菜单会把左右两列定位到「当前正在播放的频道」；左列上下移动即实时预览右列频道（不加载、不切台），在频道列按确定才真正 `loadCurrentChannel`。
- 调试按键：`adb ... input keyevent 23`=确定（开菜单/选中），`21`/`22`=左/右切列，`19`/`20`=上/下移动，`4`=返回（关菜单）。

### 右侧系统设置面板

- 遥控器菜单键呼出，贴屏幕右侧显示；左列是具体档位，右列是「画质增强」设置项。ADB 可用 `input keyevent 82` 模拟 `MENU`。
- 当前设置项为「画质增强」，可选「原始 / 轻度增强 / 标准增强 / 强力增强」；上下选择、确定应用，当前生效档位带 `✓`。
- 档位通过 `SharedPreferences` 持久化，并在 WebExtension Port 每次连接后重新下发；换台重建播放器节点时也会自动应用。
- 增强使用 GeckoView 可稳定合成的 CSS 对比度、饱和度和亮度组合，不使用 WebGL，也不会改变视频源分辨率。Android GeckoView 的硬件解码视频叠加 SVG `feConvolveMatrix` 实测会黑屏，不能用于 App。

## 注意点 / 踩坑

- Activity 是横屏（`sensorLandscape`）。模拟器若锁竖屏会显示异常，转成横屏即可。
- AGP 9.3.2 脚手架已内置 Kotlin 插件，**不要**再手动加 `org.jetbrains.kotlin.android`（会报 `extension 'kotlin' already registered`）。
- GeckoView 153 使用 Java 17 API，Gradle 的 Java/Kotlin JVM target 必须保持 17。
- 构建启用了 ABI 拆包：32 位电视安装 `app-armeabi-v7a-debug.apk`，ARM64 模拟器安装 `app-arm64-v8a-debug.apk`。
- 腾讯 X5 在 `MiTV-MFTP0` 上初始化返回下载状态 `-124`（服务端未下发内核），会退回系统 WebView 66，因此没有作为最终方案保留。
- 全屏方案关键结论：清祖先 `transform`、每秒写 `!important` 内联样式、隐藏非播放器顶层节点、不开 `useWideViewPort/loadWithOverviewMode`。

## 合规红线

只嵌套展示官网公开直播页，不采集/存储/转发视频流；不破解登录/付费/清晰度限制；保留《免责声明》与侵权删除通道；非营利声明。
