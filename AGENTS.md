# AGENTS.md — WebTvLive 协作与调试说明

本文件面向在本仓库工作的 AI/协作者，记录经过真机/模拟器验证的构建与调试流程，避免重复踩坑。产品与里程碑规划见 `docs/开发计划.md`。

## 项目速览

- 一款「基于 GeckoView 的安卓直播电视 App」：内置 Firefox 内核打开各电视台**官方直播网页**，通过内置 WebExtension 把网页 `<video>` 铺满全屏，做成「像传统电视一样换台」的体验。
- 语言 Kotlin，构建 Kotlin DSL + Version Catalog（`gradle/libs.versions.toml`），原生 XML View（不用 Compose）。
- 核心文件：
  - `app/src/main/java/com/lipengzhou/webtvlive/WebTvLiveApplication.kt`：进程级 GeckoRuntime 创建与首次页面预热。
  - `app/src/main/java/com/lipengzhou/webtvlive/MainActivity.kt`：GeckoSession、央视频页内换台、超时恢复、遥控器按键、全屏/常亮。
  - `app/src/main/assets/webextension/`：内置 WebExtension；通过 DOM/媒体事件发现播放器和换台状态，按需维护全屏样式与 100% 音量，清晰度交给官网默认/自适应策略，并通过持久 native messaging Port 接收页内换台指令。
  - `app/src/main/res/layout/activity_main.xml`：黑底 FrameLayout + GeckoView 容器 + 频道名浮层。

## 构建（命令行）

macOS 上用 **Android Studio 内置 JBR** 作 `JAVA_HOME` 最稳（系统 JDK 版本常不匹配 AGP 9）：

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

# 只编译 Kotlin（快速验证语法/编译）
./gradlew :app:compileDebugKotlin -q

# 打 debug APK（按 ABI 分包）
./gradlew :app:assembleDebug -q
# 电视：app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk
# ARM64 模拟器：app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

## 在模拟器/真机上调试（无遥控器时的等效操作）

模拟器没有实体遥控器，用 `adb ... input keyevent` 发按键即可等效触发 `MainActivity.onKeyDown`。**同时连了多台设备时必须用 `-s <serial>` 指定目标。**

```bash
# 0) 列设备；分辨哪台是目标（例如 Xiaomi 模拟器）
adb devices
adb -s 127.0.0.1:5555 shell getprop ro.product.manufacturer   # -> Xiaomi
adb -s 127.0.0.1:5555 shell getprop ro.build.version.sdk       # -> 32

# 1) 安装新版（覆盖安装保留数据）
adb -s 127.0.0.1:5555 install -r app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk

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

### 交互速查（当前实现）

- **上**（`DPAD_UP` / `CHANNEL_UP`）：标准态=下一个频道（到末尾循环回第一个）；菜单态=当前列上移一项。
- **下**（`DPAD_DOWN` / `CHANNEL_DOWN`）：标准态=上一个频道（到开头循环回最后一个）；菜单态=当前列下移一项。
- **确定**（`DPAD_CENTER` / `ENTER`）：标准态=呼出左侧频道菜单；菜单态在分类列=跳到频道列，在频道列=选中并换台。
- **左/右**（`DPAD_LEFT` / `DPAD_RIGHT`）：标准态屏蔽（防止 WebView 滚动页面/移焦点）；菜单态在「分类列 ↔ 频道列」间切换焦点。
- **返回键**：菜单态=关闭菜单；标准态=2 秒内按两次退出。
- 首次按最近成功频道的 `pid` 直达 `https://www.yangshipin.cn/tv/home?pid=...`；WebExtension 会核对页面实际选中频道，`pid` 失效时回退到按频道名点击。后续换台仍在当前页面按频道名点击并局部重建播放器。
- 发出页内换台指令时立即显示全屏加载遮罩；WebExtension 必须确认央视频已替换旧 `<video>`，或复用的 `<video>` 触发了新一轮 `playing`，才发送带本次请求 ID 的 `playing` 隐藏遮罩，不能让旧频道或过期请求提前解除遮罩。
- 每次播放器节点创建或换台重建后，WebExtension 会解除静音并持续把 `<video>.volume` 设为 `1`；不主动切换清晰度，使用官网默认/自适应策略。
- WebExtension 不再每 500ms 全量扫描 DOM：频道和播放器发现由 `MutationObserver` 驱动，播放完成由 `playing` 等媒体事件驱动；仅在受控节点样式被官网改写时定向修复。

### 侧边频道菜单（M1 首版）

- 确定键呼出，贴屏幕左侧显示，**不遮住右侧视频、视频继续播放**（菜单只是浮层，不碰 GeckoView）。
- 左区=分类列表（当前为 `CCTV`、`卫视`），右区=该分类下频道列表，均垂直滚动。
- 数据源在 `TvCatalog.kt`：`Category(name, channels)` 列表 + 拉平的 `flatChannels`（供上/下换台与「上次频道下标」历史兼容）。`Channel.siteName` 必须与央视频页面频道名完全一致。
- 菜单导航**不走系统焦点**（方向键被 `dispatchKeyEvent` 提前吞掉，进不了 RecyclerView）：`MenuAdapter` 按外部下标渲染高亮——活动列选中行=高亮蓝（`activated`），非活动列选中行=暗选中态（`selected`）。
- 打开菜单会把左右两列定位到「当前正在播放的频道」；左列上下移动即实时预览右列频道（不加载、不切台），在频道列按确定才真正 `loadCurrentChannel`。
- 调试按键：`adb ... input keyevent 23`=确定（开菜单/选中），`21`/`22`=左/右切列，`19`/`20`=上/下移动，`4`=返回（关菜单）。

## 注意点 / 踩坑

- Activity 是横屏（`sensorLandscape`）。模拟器若锁竖屏会显示异常，转成横屏即可。
- AGP 9.3.2 脚手架已内置 Kotlin 插件，**不要**再手动加 `org.jetbrains.kotlin.android`（会报 `extension 'kotlin' already registered`）。
- GeckoView 153 使用 Java 17 API，Gradle 的 Java/Kotlin JVM target 必须保持 17。
- 构建启用了 ABI 拆包：32 位电视安装 `app-armeabi-v7a-debug.apk`，ARM64 模拟器安装 `app-arm64-v8a-debug.apk`。
- 腾讯 X5 在 `MiTV-MFTP0` 上初始化返回下载状态 `-124`（服务端未下发内核），会退回系统 WebView 66，因此没有作为最终方案保留。
- 全屏方案关键结论详见 `docs/开发计划.md` 的 M0 章节（清祖先 `transform`、每秒写 `!important` 内联样式、隐藏非播放器顶层节点、不开 `useWideViewPort/loadWithOverviewMode`）。

## 合规红线

只嵌套展示官网公开直播页，不采集/存储/转发视频流；不破解登录/付费/清晰度限制；保留《免责声明》与侵权删除通道；非营利声明。
