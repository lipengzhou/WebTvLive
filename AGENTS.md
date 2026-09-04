# AGENTS.md — WebTvLive 协作与调试说明

本文件面向在本仓库工作的 AI/协作者，记录经过真机/模拟器验证的构建与调试流程，避免重复踩坑。产品与里程碑规划见 `docs/开发计划.md`。

## 项目速览

- 一款「基于 WebView 的安卓直播电视 App」：内置浏览器打开各电视台**官方直播网页**，注入 JS 把网页 `<video>` 铺满全屏，做成「像传统电视一样换台」的体验。
- 语言 Kotlin，构建 Kotlin DSL + Version Catalog（`gradle/libs.versions.toml`），原生 XML View（不用 Compose）。
- 核心文件：
  - `app/src/main/java/com/lipengzhou/webtvlive/MainActivity.kt`：WebView 封装、频道表 `CHANNELS`、遥控器按键、全屏/常亮。
  - `app/src/main/assets/default_js_template.js`：每秒幂等维护的全屏注入脚本（自愈，压过站点内联样式）。
  - `app/src/main/res/layout/activity_main.xml`：黑底 FrameLayout + WebView 容器 + 原生全屏兜底容器 + 频道名浮层。

## 构建（命令行）

macOS 上用 **Android Studio 内置 JBR** 作 `JAVA_HOME` 最稳（系统 JDK 版本常不匹配 AGP 9）：

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

# 只编译 Kotlin（快速验证语法/编译）
./gradlew :app:compileDebugKotlin -q

# 打 debug APK
./gradlew :app:assembleDebug -q
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 在模拟器/真机上调试（无遥控器时的等效操作）

模拟器没有实体遥控器，用 `adb ... input keyevent` 发按键即可等效触发 `MainActivity.onKeyDown`。**同时连了多台设备时必须用 `-s <serial>` 指定目标。**

```bash
# 0) 列设备；分辨哪台是目标（例如 Xiaomi 模拟器）
adb devices
adb -s 127.0.0.1:5555 shell getprop ro.product.manufacturer   # -> Xiaomi
adb -s 127.0.0.1:5555 shell getprop ro.build.version.sdk       # -> 32

# 1) 安装新版（覆盖安装保留数据）
adb -s 127.0.0.1:5555 install -r app/build/outputs/apk/debug/app-debug.apk

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

排查页面/播放问题时看 WebView 控制台日志：

```bash
adb -s 127.0.0.1:5555 logcat -c                              # 先清空
# 触发操作后
adb -s 127.0.0.1:5555 logcat -d | grep -i "WebTvLive\|cctv\|chromium"
```

### 交互速查（当前实现）

- **上**（`DPAD_UP` / `CHANNEL_UP`）：下一个频道，到末尾循环回第一个。
- **下**（`DPAD_DOWN` / `CHANNEL_DOWN`）：上一个频道，到开头循环回最后一个。
- **返回键**：2 秒内按两次退出。
- 换台时先 `stopLoading()` 重置再 `loadUrl`，并在左上角短暂显示频道名。

## 注意点 / 踩坑

- Activity 是横屏（`sensorLandscape`）。模拟器若锁竖屏会显示异常，转成横屏即可。
- AGP 9.3.2 脚手架已内置 Kotlin 插件，**不要**再手动加 `org.jetbrains.kotlin.android`（会报 `extension 'kotlin' already registered`）。
- 全屏方案关键结论详见 `docs/开发计划.md` 的 M0 章节（清祖先 `transform`、每秒写 `!important` 内联样式、隐藏非播放器顶层节点、不开 `useWideViewPort/loadWithOverviewMode`）。

## 合规红线

只嵌套展示官网公开直播页，不采集/存储/转发视频流；不破解登录/付费/清晰度限制；保留《免责声明》与侵权删除通道；非营利声明。
