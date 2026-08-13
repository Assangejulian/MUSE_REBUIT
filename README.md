# Muse

跑在 Android 上的个人 Agent。默认 Model 是 DeepSeek V4 Flash，可一键切到 Pro。

界面是 Catppuccin（默认 Mocha，浅色 Latte）。句子用中文，专业名词保持英文：Agent / Tool / Model / API Key / Token / Thinking / Session。

## 初版能做什么

- 填 API Key 后直接对话
- Thinking 与回复分开展示
- 6 个 Tool：`device_status` / `memory_read` / `memory_write` / `note_save` / `http_fetch` / `finish`
- `memory.md` 跨 Session 记住偏好
- Flash / Pro、effort、主题可在设置里改

初版**不能**操作其他 App，也不在手机上跑 V4 权重。

## 构建

本机需要 JDK 17+ 和 Android SDK 35。

```powershell
$env:ANDROID_HOME = "C:\Users\Lenovo\AppData\Local\Android\Sdk"
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat assembleDebug --console=plain
```

打出来的包会自动拷到 `dist/Muse-<version>-debug.apk`。当前：`dist/Muse-0.2.0-debug.apk`。

设备控制：设置里打开 **Muse 无障碍**（点节点），再连 **Shizuku**（启动/shell 兜底）。任务运行时（需悬浮窗权限）主界面收成顶栏浮窗，直播中文 Thinking。

更新从 GitHub Release 拉取：`Assangejulian/MUSE_REBUIT`。需要在仓库发一个带 APK 的 Release（tag 如 `v0.1.1`）。

## 第一次使用

1. 安装 APK
2. 在 [DeepSeek 开放平台](https://platform.deepseek.com/api_keys) 申请 API Key
3. 粘贴 Key，选 Flash 或 Pro
4. 开始对话。可以说「记住我喜欢短回复」，或问现在的时间和电量

## 模块

- `app` — Compose UI
- `core-design` — Catppuccin
- `core-llm` — DeepSeek Stream / Tool 协议
- `core-agent` — Agent 循环
- `core-memory` — Session、memory.md、加密设置
