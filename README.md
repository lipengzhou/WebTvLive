# WebTvLive

WebTvLive 是一款面向 Android TV 和触屏设备的全屏直播电视 App。它不采集、不存储、不转发视频流，只在应用内打开电视台官方公开直播网页，并把网页中的播放器铺满全屏，提供接近传统电视的换台体验。

当前默认播放源来自央视频公开电视直播页，频道包括 CCTV 和主要卫视。

## 功能特性

- 双内核构建：`gecko`（随 APK 分发 GeckoView，适合系统 WebView 较旧的电视）、`webview`（用系统 WebView，包更小，适合较新设备）。
- 全屏直播：沉浸式横屏、黑底播放、屏幕常亮。
- 多种换台方式：遥控器、底部触屏控制层、快捷手势。
- 频道菜单：左侧按分类浏览频道，并显示当天节目单。
- 系统设置面板：右侧面板，当前支持画质增强档位（原始 / 轻度 / 标准 / 强力）。
- 稳健播放：换台后等真实 `playing` 事件再隐藏加载遮罩，超时自动重试或回退到稳定频道。
- 自动更新：Release 启动时静默检查更新，按当前内核和 CPU 架构下载对应正式包；Debug 关闭更新。
- 触屏调节：左半屏滑动调应用内亮度，右半屏滑动调系统媒体音量。

## 使用说明

### 遥控器（播放态）

| 按键 | 行为 |
| --- | --- |
| `DPAD_UP` / `CHANNEL_UP` | 下一个频道，末尾循环回第一个 |
| `DPAD_DOWN` / `CHANNEL_DOWN` | 上一个频道，开头循环到最后一个 |
| `DPAD_CENTER` / `ENTER` | 打开频道列表 |
| `MENU` / `SETTINGS` / `TV_CONTENTS_MENU` | 打开系统设置 |
| `BACK` | 2 秒内按两次退出 |
| `DPAD_LEFT` / `DPAD_RIGHT` | 播放态屏蔽，防止网页滚动或抢焦点 |

频道列表打开后：上/下在当前列移动，左/右在分类列与频道列间切换，确定进入频道列并换台，返回关闭，菜单键切到系统设置。

系统设置打开后：上/下选择画质增强档位，确定应用，返回/菜单键关闭。

### 触屏（播放态）

| 操作 | 行为 |
| --- | --- |
| 单击屏幕 | 显示/隐藏底部触屏控制层 |
| 左半屏快速双击 | 打开频道列表 |
| 右半屏快速双击 | 打开系统设置 |
| 左半屏上下滑 | 调整 App 内亮度 |
| 右半屏上下滑 | 调整系统媒体音量 |
| 屏幕中间上滑 / 下滑 | 切到下一个 / 上一个频道 |

底部控制层提供「频道列表 / 系统设置 / 退出」三个按钮。点击视频空白区可关闭已打开的面板；频道列表和设置面板无操作 12 秒后自动关闭，底部控制层无操作 3 秒后自动隐藏。

## 下载与安装

正式版 APK 在两处 Release 同步发布，任选其一下载：

- [Gitee Release](https://gitee.com/lipengzhou/WebTvLive/releases)（主发布源，应用内自动更新也从这里拉取）
- [GitHub Release](https://github.com/lipengzhou/WebTvLive/releases)（镜像）

按设备情况选择一个 APK 下载安装：

| 选择 | 说明 |
| --- | --- |
| 内核 `gecko` | 系统 WebView 较旧的电视（如 Chromium 66 级别），兼容性更稳，包更大 |
| 内核 `webview` | 较新的 Android 设备，复用系统 WebView，包更小 |
| ABI `arm64-v8a` | 64 位设备 |
| ABI `armeabi-v7a` | 32 位设备 |

安装后即可使用；Release 版会在后续启动时自动检查更新。首次通过应用内更新侧载新版本时，需允许本应用安装未知来源应用。

## 开发指南

macOS 上建议用 Android Studio 内置 JBR 作 `JAVA_HOME`：

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

统一质量门禁（Shell/JS/JSON 语法、双 flavor 单测与 Lint、四个 Debug APK、`git diff --check`）：

```bash
./scripts/verify.sh
```

打 Debug 包用于本地/模拟器调试，产物按 `内核 × ABI` 分包：

```bash
./gradlew :app:assembleDebug -q
# app/build/outputs/apk/{gecko,webview}/debug/webtvlive-{flavor}-{version}-{abi}-debug.apk
```

在模拟器/真机上运行（多设备用 `-s <serial>` 指定；模拟器用 `input keyevent` 等效遥控器）：

```bash
adb -s <serial> install -r app/build/outputs/apk/gecko/debug/webtvlive-gecko-0.0.6-arm64-v8a-debug.apk
adb -s <serial> shell monkey -p com.lipengzhou.webtvlive.debug -c android.intent.category.LAUNCHER 1
adb -s <serial> shell input keyevent 19   # 19/20 换台，23 开菜单/确定，82 设置，4 返回
```

Debug 版应用名为「看电视 Debug」，与 Release 版沙箱隔离、可同时安装。

更完整的架构约束、调试按键/触屏映射、发版流程等，见 `AGENTS.md`。

## 合规说明

WebTvLive 只嵌套展示电视台官网公开直播网页，不采集、不存储、不转发任何视频流，不破解登录、付费或清晰度限制。频道页面和视频内容版权归原网站及权利方所有。

本项目仅用于个人学习、调试和非营利使用。如权利方认为某个公开页面不应被嵌套展示，请移除对应频道配置或停止使用。

## 授权协议

本项目**源代码**以 [MIT License](LICENSE) 授权。

注意区分授权对象：MIT 仅覆盖本仓库的源代码；应用内展示的频道页面与视频内容版权归原网站及权利方所有，不在本授权范围内，请遵守上方「合规说明」。
