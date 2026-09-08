# WebTvLive

WebTvLive 是一款面向 Android TV 和触屏设备的全屏直播电视 App。它不采集、不存储、不转发视频流，只在应用内打开电视台官方公开直播网页，并通过页面适配脚本把网页中的播放器铺满全屏，提供接近传统电视的换台体验。

当前默认播放源来自央视频公开电视直播页，频道包括 CCTV 和主要卫视频道。

## 功能特性

- 双内核构建：
  - `gecko` flavor：随 APK 分发 GeckoView，适合系统 WebView 较旧的电视设备。
  - `webview` flavor：使用系统 WebView，APK 更小，适合较新的 Android 设备。
- 全屏直播播放：沉浸式横屏、黑底播放、屏幕常亮。
- 频道切换：支持遥控器、触屏控制层和快捷手势。
- 频道菜单：左侧两列频道列表，按分类浏览并选择频道。
- 系统设置面板：右侧设置面板，当前支持画质增强档位。
- 播放恢复：换台后等待真实 `playing` 事件再隐藏加载遮罩，超时后自动重试或回退到稳定频道。
- 画质增强：支持原始、轻度增强、标准增强、强力增强四档。
- 自动更新：启动时静默检查 Gitee 更新，按当前内核和 CPU 架构下载对应正式包。
- 触屏调节：左半屏滑动调应用内亮度，右半屏滑动调系统媒体音量。

## 触屏操作

播放态：

| 操作 | 行为 |
| --- | --- |
| 单击屏幕 | 显示或隐藏底部触屏控制层 |
| 左半屏快速双击 | 打开频道列表 |
| 右半屏快速双击 | 打开系统设置 |
| 左半屏上下滑 | 调整当前 App 内亮度 |
| 右半屏上下滑 | 调整系统媒体音量 |
| 屏幕中间区域上滑 | 切到下一个频道 |
| 屏幕中间区域下滑 | 切到上一个频道 |

底部触屏控制层：

| 按钮 | 行为 |
| --- | --- |
| 频道列表 | 打开左侧频道列表 |
| 系统设置 | 打开右侧系统设置面板 |
| 退出 | 直接退出 App |

面板行为：

- 频道列表打开后，点击右侧视频空白区域可关闭。
- 系统设置打开后，点击左侧视频空白区域可关闭。
- 频道列表和系统设置面板无操作 12 秒后自动关闭。
- 底部触屏控制层无操作 3 秒后自动隐藏。

## 遥控器操作

| 按键 | 播放态行为 |
| --- | --- |
| `DPAD_UP` / `CHANNEL_UP` | 下一个频道，末尾循环回第一个 |
| `DPAD_DOWN` / `CHANNEL_DOWN` | 上一个频道，开头循环到最后一个 |
| `DPAD_CENTER` / `ENTER` | 打开频道列表 |
| `MENU` / `SETTINGS` / `TV_CONTENTS_MENU` | 打开系统设置 |
| `BACK` | 2 秒内按两次退出 |
| `DPAD_LEFT` / `DPAD_RIGHT` | 播放态屏蔽，防止网页滚动或抢焦点 |

频道列表打开后：

| 按键 | 行为 |
| --- | --- |
| 上 / 下 | 在当前列移动选择 |
| 左 / 右 | 在分类列和频道列之间切换 |
| 确定 | 分类列进入频道列；频道列选中并换台 |
| 返回 | 关闭频道列表 |
| 菜单键 | 切换到系统设置 |

系统设置打开后：

| 按键 | 行为 |
| --- | --- |
| 上 / 下 | 选择画质增强档位 |
| 确定 | 应用当前档位 |
| 返回 / 菜单键 | 关闭系统设置 |

“检查更新”设置项支持手动检查。发现新版时会展示更新说明，可选择跳过该版本或后台下载；下载完成并校验文件、版本和签名后，会打开系统安装器。Android 首次侧载更新时需要用户允许本应用安装未知来源应用。

## 技术架构

核心思路：

1. 原生 `MainActivity` 管理全屏、频道状态、遥控器和触屏输入。
2. 浏览器内核通过 `BrowserEngine` 抽象隔离，分别由 GeckoView 和系统 WebView flavor 实现。
3. 页面适配脚本 `player_adapter.js` 注入官网页面，负责查找 `<video>`、全屏铺满、换台和播放状态回传。
4. 原生层收到播放成功事件后隐藏加载遮罩，并持久化最近成功频道。

关键文件：

| 文件 | 说明 |
| --- | --- |
| `app/src/main/java/com/lipengzhou/webtvlive/MainActivity.kt` | 主交互、频道切换、菜单、设置、触屏手势和恢复逻辑 |
| `app/src/main/java/com/lipengzhou/webtvlive/BrowserEngine.kt` | 双内核共享接口 |
| `app/src/gecko/java/com/lipengzhou/webtvlive/FlavorBrowserEngine.kt` | GeckoView 内核实现 |
| `app/src/webview/java/com/lipengzhou/webtvlive/FlavorBrowserEngine.kt` | 系统 WebView 内核实现 |
| `app/src/main/assets/webextension/player_adapter.js` | 页面播放器适配脚本 |
| `app/src/gecko/assets/webextension/request_filter.js` | GeckoView 请求过滤脚本 |
| `app/src/main/java/com/lipengzhou/webtvlive/TvCatalog.kt` | 内置频道目录 |
| `app/src/main/java/com/lipengzhou/webtvlive/VideoEnhancement.kt` | 画质增强档位 |
| `app/src/main/res/layout/activity_main.xml` | 主界面布局 |

## 构建环境

建议在 macOS 上使用 Android Studio 自带 JBR：

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

项目配置：

| 项 | 值 |
| --- | --- |
| 语言 | Kotlin |
| UI | 原生 XML View |
| 构建 | Gradle Kotlin DSL + Version Catalog |
| AGP | 9.3.2 |
| minSdk | 28 |
| targetSdk | 37 |
| compileSdk | 37 |
| GeckoView | 153.0 |

## 构建命令

快速编译 Kotlin：

```bash
./gradlew :app:compileGeckoDebugKotlin -q
./gradlew :app:compileWebviewDebugKotlin -q
```

运行单元测试并打本地调试包：

```bash
./gradlew :app:testGeckoDebugUnitTest :app:testWebviewDebugUnitTest :app:assembleDebug -q
```

Debug APK 用于本地开发和模拟器调试，输出路径：

```text
app/build/outputs/apk/gecko/debug/app-gecko-arm64-v8a-debug.apk
app/build/outputs/apk/gecko/debug/app-gecko-armeabi-v7a-debug.apk
app/build/outputs/apk/webview/debug/app-webview-arm64-v8a-debug.apk
app/build/outputs/apk/webview/debug/app-webview-armeabi-v7a-debug.apk
```

Release APK 用于 Gitee Release 和正式分发。签名信息从环境变量、Gradle property 或本地 `local.properties` 读取：

```properties
WEBTVLIVE_RELEASE_STORE_FILE=/absolute/path/to/webtvlive-release.jks
WEBTVLIVE_RELEASE_STORE_PASSWORD=...
WEBTVLIVE_RELEASE_KEY_ALIAS=webtvlive
WEBTVLIVE_RELEASE_KEY_PASSWORD=...
```

```bash
./gradlew :app:testGeckoDebugUnitTest :app:testWebviewDebugUnitTest :app:assembleRelease -q
```

`assembleRelease` 会同时开启 R8/资源优化，并压缩 APK 内的 native `.so`。输出文件仍是
可直接安装的标准 APK，不需要用户解压；安装时 Android 会把 native 库解压到应用目录，
因此安装后的磁盘占用会高于 APK 文件大小。

Release APK 输出路径：

```text
app/build/outputs/apk/gecko/release/app-gecko-arm64-v8a-release.apk
app/build/outputs/apk/gecko/release/app-gecko-armeabi-v7a-release.apk
app/build/outputs/apk/webview/release/app-webview-arm64-v8a-release.apk
app/build/outputs/apk/webview/release/app-webview-armeabi-v7a-release.apk
```

发版版本号使用 `0.0.x` 小版本递增策略；如无特殊说明，每次只递增最后一位 patch 号。例如 `0.0.1` 的下一版是 `0.0.2`。

### Gitee Release 与更新清单

App 从 `release/update.json` 检查版本，APK 下载地址固定使用 Gitee Release。发版时先准备一份纯文本更新说明，然后执行：

```bash
./scripts/prepare-gitee-release.sh --notes /path/to/release-notes.txt
```

脚本会运行双 flavor 单元测试和 release 构建，校验四个 APK 的版本、签名，计算文件大小与 SHA-256，并生成更新清单。随后在 Gitee 创建 `v<versionName>` Release，上传四个 APK；上传完成后执行：

```bash
./scripts/prepare-gitee-release.sh --verify-remote
```

确认四条下载链接可访问后，再提交并推送 `release/update.json`。必须最后发布清单，避免客户端在 APK 上传完成前发现新版本。

## 模拟器调试

查看设备：

```bash
adb devices
adb -s emulator-5554 shell getprop ro.product.cpu.abi
adb -s emulator-5554 shell getprop ro.build.version.sdk
```

安装 GeckoView debug 版本并启动：

```bash
adb -s emulator-5554 install -r app/build/outputs/apk/gecko/debug/app-gecko-arm64-v8a-debug.apk
adb -s emulator-5554 shell monkey -p com.lipengzhou.webtvlive -c android.intent.category.LAUNCHER 1
```

确认 App 在前台：

```bash
adb -s emulator-5554 shell dumpsys activity activities | rg -i "topResumedActivity|mResumedActivity"
```

模拟遥控器：

```bash
adb -s emulator-5554 shell input keyevent 19  # 下一个频道
adb -s emulator-5554 shell input keyevent 20  # 上一个频道
adb -s emulator-5554 shell input keyevent 23  # 打开频道列表 / 确定
adb -s emulator-5554 shell input keyevent 82  # 打开系统设置
adb -s emulator-5554 shell input keyevent 4   # 返回
```

模拟触屏：

```bash
# 单击屏幕，显示底部触屏控制层
adb -s emulator-5554 shell input tap 1280 720

# 左半屏快速双击，打开频道列表
adb -s emulator-5554 shell input tap 300 720
adb -s emulator-5554 shell input tap 300 720

# 右半屏快速双击，打开系统设置
adb -s emulator-5554 shell input tap 2200 720
adb -s emulator-5554 shell input tap 2200 720

# 屏幕中间上滑/下滑，切换频道
adb -s emulator-5554 shell input swipe 1280 1100 1280 250 650
adb -s emulator-5554 shell input swipe 1280 250 1280 1100 650

# 左半屏上下滑，调整 App 内亮度
adb -s emulator-5554 shell input swipe 640 1100 640 250 650

# 右半屏上下滑，调整系统媒体音量
adb -s emulator-5554 shell input swipe 1920 250 1920 1100 650
```

抓取日志：

```bash
adb -s emulator-5554 logcat -c
adb -s emulator-5554 logcat -d | rg "WebTvLive|Yangshipin|Gecko|MediaCodec"
```

截图：

```bash
adb -s emulator-5554 exec-out screencap -p > /tmp/webtvlive.png
```

## 真机注意事项

- GeckoView 包体较大，但对旧电视的兼容性更稳定。
- 如果电视是 32 位设备，安装 `app-gecko-armeabi-v7a-debug.apk`。
- 如果系统 WebView 很旧，例如 Chromium 66 级别，优先使用 GeckoView 版本。
- 后台时 App 会暂停浏览器内核和媒体会话，不会保持前台直播拉流状态；刚切后台时可能有少量未完成请求收尾。

## 合规说明

WebTvLive 只嵌套展示电视台官网公开直播网页，不采集、不存储、不转发任何视频流，不破解登录、付费或清晰度限制。频道页面和视频内容版权归原网站及权利方所有。

本项目仅用于个人学习、调试和非营利使用。如权利方认为某个公开页面不应被嵌套展示，请移除对应频道配置或停止使用。

## 开发资料

- `AGENTS.md`：协作、构建、模拟器和真机调试说明。
