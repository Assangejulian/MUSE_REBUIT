# dist

版本化 APK 出口。构建 `assembleDebug` / `assembleRelease` 后会自动拷到这里。

命名：`Muse-<versionName>-<debug|release>.apk`

| 文件 | versionName | 日期 | 说明 |
|---|---|---|---|
| `Muse-0.1.0-debug.apk` | 0.1.0 | 2026-08-13 | 初版：Chat、Thinking、6 个 Tool、memory.md |
| `Muse-0.1.1-debug.apk` | 0.1.1 | 2026-08-13 | 修复 tools[].type 导致的 400；GitHub Release 更新检测/下载/安装 |
| `Muse-0.1.2-debug.apk` | 0.1.2 | 2026-08-13 | web_search；open_url / share_text / open_app |
| `Muse-0.1.3-debug.apk` | 0.1.3 | 2026-08-13 | 修复 VPN 下 DNS 误杀；Bing/百度/DDG/Wiki 多源搜索 |
| `Muse-0.1.4-debug.apk` | 0.1.4 | 2026-08-13 | 修复 Tool 在主线程联网（NetworkOnMainThreadException） |
| `Muse-0.2.0-debug.apk` | 0.2.0 | 2026-08-13 | Shizuku 控制（tap/type/ui_dump/shell）+ 任务悬浮窗中文 CoT |
| `Muse-0.3.0-debug.apk` | 0.3.0 | 2026-08-13 | 无障碍树 + click_node/click_text；Shizuku 兜底 |
| `Muse-0.3.1-debug.apk` | 0.3.1 | 2026-08-13 | 去掉特化硬编码；点击拦截改为用户 blocklist |
| `Muse-0.3.2-debug.apk` | 0.3.2 | 2026-08-13 | Tool 轮次上限 16 → 100 |
| `Muse-0.3.3-debug.apk` | 0.3.3 | 2026-08-13 | 达上限/中断也把 Thinking 和 Tool 轨迹写入 Session |
| `Muse-0.3.4-debug.apk` | 0.3.4 | 2026-08-13 | 观察 Tool 不误杀；点击后自带新树；修好 open_app / ui_dump / 假成功点击 |
| `Muse-0.4.0-debug.apk` | 0.4.0 | 2026-08-14 | Cream UI；聊天/任务模式；+ 选 Tool；Markdown；复制/删对话；导入 memory |
| `Muse-0.4.1-debug.apk` | 0.4.1 | 2026-08-14 | 端侧 OCR（ML Kit 中文）；ocr_screen 辅助判断自绘/空树界面 |
| `Muse-0.4.2-debug.apk` | 0.4.2 | 2026-08-14 | float_window 开关悬浮窗；任务运行状态栏标志 |
| `Muse-0.4.3-debug.apk` | 0.4.3 | 2026-08-14 | 悬浮窗收成主题色小球；去掉状态栏通知标 |
| `Muse-0.5.0-debug.apk` | 0.5.0 | 2026-08-14 | 定时任务：对话写入清单；每天/一次；到点跑；可读页面时间再补跑 |
| `Muse-0.5.1-debug.apk` | 0.5.1 | 2026-08-14 | 小球人/模型可开关；语音输入；点击/OCR 少等一会 |
| `Muse-0.5.2-debug.apk` | 0.5.2 | 2026-08-14 | 开跑体检；wait_for；点击未跳转留证；定时回执 |
| `Muse-0.5.3-debug.apk` | 0.5.3 | 2026-08-14 | 左滑出历史；打开 Session 不再从头滑；Gemini/Qwen |
