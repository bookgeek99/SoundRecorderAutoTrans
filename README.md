# SoundRecorderAutoTrans

一个基于 **LSPosed** 的 Xposed 模块，让小/红米系统自带的**录音机**（`com.android.soundrecorder`）在后台**自动发起「录音转文字」任务**，支持批量排队、只在 WiFi / 充电时执行、以及运行统计展示。

> 痛点：官方录音机的转文字需要手动一步步操作（进播放页 → 显示文本 → 选简体中文 → 发起云端转录），且一次只能转一个，第一个没转完第二个就暂停。本模块把这些步骤全部自动化。

---

## 功能特性

- **后台自动转文字**：自动轮询本地录音，找到未转录的录音并自动发起云端转文字任务（默认简体中文 `zh-CN`）。
- **批量排队**：尊重录音机「单任务串行」的限制，一个完成后再处理下一个，逐个把堆积的录音全部转完。
- **运行时设置**（在 App 页面可开关）：
  - 仅在 WiFi 下执行
  - 仅在充电时执行
  - 自动转文字总开关
  - 是否弹出 Toast 通知
- **运行统计展示**：显示本地录音总数、已完成转文字数、本次启动后转换数、累计运行轮数、最近处理的录音。
- **失败重试**：转文字失败（`state=4/5`）的录音允许重新尝试。

---

## 工作原理

模块通过 hook 录音机进程的 `Application.attach` 拿到 Context，随后在后台线程每 25 秒执行一轮：

1. 读取设置（启用 / WiFi / 充电 / Toast 开关）。
2. 查询录音机数据库 `ai_recognize_queue`，若存在 `state = 0`（待转录）的任务则跳过本轮（尊重串行限制）。
3. 否则从 `records` 表挑选一条「未转录、本地、有 sha1、时长>0、文件存在」的录音。
4. 发送与官方 `SoundRecorderToolService.getTranscription()` **完全相同**的 `Intent` 到 `AsrService`（extras：`fromRecord`、`autoAsrFilePath`、`autoAsrFileSha1`、`packageName`、`asrLanguage=zh-CN`、`fromToolService`），触发官方云端转录。

统计与设置通过 `/sdcard/Download/autotrans/` 下的 JSON 文件在「模块进程（录音机）」与「模块 App」之间共享。

---

## 环境要求

- 设备已 **Root**，并安装了 **LSPosed**（Zygisk 版，`xposedminversion >= 82`）。
- 系统：小米 / Redmi（MIUI / HyperOS），录音机包名 `com.android.soundrecorder`（已在 Android 16 / HyperOS 3.x 上验证）。
- 目标 SDK 36 / Android 16（`compileSdk 36`）。较低版本可自行调整 `compileSdk` / `minSdk`。

---

## 构建

### 依赖

- JDK 17
- Android SDK：`platforms;android-36`、`build-tools;36.0.0`
- Gradle 8.1+（本项目使用 AGP 8.1.0）

### 步骤

```bash
# 1. 设置 SDK 路径（写进 local.properties，或设 ANDROID_HOME）
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 2. 构建 release APK
./gradlew assembleRelease
# 或
gradle assembleRelease
```

> `release` 变体默认不混淆（`minifyEnabled false`）。生成的 APK 在 `app/build/outputs/apk/release/`。
>
> 注意：产物为 **unsigned**，安装前需自行签名（如用 `apksigner` + debug keystore，或发布构建配置签名）。

---

## 安装与使用

1. 用 Root 权限安装构建好的 APK（例如 `adb install` 或 `pm install`）。
2. 打开 **LSPosed Manager**，启用「录音机自动转文字」模块。
3. 在模块的**作用域（scope）**勾选目标应用：**`com.android.soundrecorder`**。
4. **重启录音机应用**（LSPosed 里点「重启」或 force-stop 后重新打开），让模块注入生效。
5. 打开模块的 App 界面，可查看统计、按需开启「仅在 WiFi / 充电」等开关。
   - 若 App 提示存储权限未授权，点「授权存储访问」开启「所有文件访问」，以便读写共享统计/设置文件。

---

## 设置说明（App 内）

| 设置项 | 说明 | 默认 |
|--------|------|------|
| 启用自动转文字 | 关闭后模块不发起任何新任务 | 开启 |
| 仅在 WiFi 下执行 | 非 WiFi 环境不发起转文字 | 关闭 |
| 仅在充电时执行 | 非充电状态不发起转文字 | 关闭 |
| 弹出 Toast 通知 | 每次自动发起时弹出提示 | 开启 |

> 改动设置后需**重启录音机**才能生效。

---

## 项目结构

```
AutoTransModule/
├── app/
│   ├── build.gradle
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml       # LSPosed 元数据 + 作用域
│       ├── assets/xposed_init        # 入口类声明
│       ├── java/com/example/autotrans/
│       │   ├── AutoTransHook.java    # 核心 hook：轮询 + 发起转文字
│       │   ├── MainActivity.java      # App 界面：统计 + 设置
│       │   └── TransStore.java        # 跨进程共享的统计/设置存取
│       └── res/                       # 图标与字符串资源
├── build.gradle
├── gradle.properties
├── settings.gradle
└── .gitignore
```

---

## 已知限制与说明

- **单任务串行**：录音机自身对 ASR 是串行的（`state=0` 时等待），模块不强行并发，而是逐个排队完成。
- **统计的「已完成数」**：按 `ai_recognize_queue` 中 `state = 1`（完成）的记录估算，可能不含历史未入队的记录。
- 本模块主要面向**已 Root 并熟悉 LSPosed** 的用户，仅作学习与个人使用。

---

## License

本项目仅供个人学习与研究使用。使用前请遵守你所在地区与设备厂商的相关规定。
