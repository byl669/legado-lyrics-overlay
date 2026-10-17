# Legado · 朗读桌面歌词定制版

> 基于 [LegadoTeam/legado](https://github.com/LegadoTeam/legado)（「阅读 3.0」社区版）的定制分支：
> 在**朗读功能**上增加了一个**系统级桌面歌词悬浮窗**——像音乐 App 的桌面歌词一样，
> 把正在朗读的句子投影到其它应用之上（当前句 + 上下句、逐字进度、可拖动、可暂停）。
>
> 基线：上游 commit `5198645`（tag `beta`）。
> 许可证：沿用上游 **GPL-3.0**（见 [LICENSE](LICENSE)）。

## 功能特性

- **系统级悬浮窗**（`TYPE_APPLICATION_OVERLAY`），只在 App 退到后台时显示，亮屏自动恢复
- 三段式歌词：上一句 / **当前句（20sp 加粗）** / 下一句，逐字推进与朗读进度同步
- 手势三件套：**拖动**换位置（自动记忆）· **双击**暂停/恢复 · **长按**隐藏控制按钮
- **暂停即隐藏**：任何暂停入口（双击、通知栏、耳机线控、来电）都会收起悬浮窗，恢复播放自动回来
- **熄屏省电**：熄屏自动隐藏并停止刷新，亮屏恢复
- **文字恒不透明**：透明度只作用于背景，保证任何壁纸上文字清晰
- 外观可配置：字号 / 文字色 / 已读进度色 / 背景透明度 / 上下句开关 / 按钮开关
- 共存设计：包名 `com.legado.app.custom`，应用名「阅读·歌词版」，可与官方原版同装一台设备

## 构建

```bash
# 1. 依赖：JDK 17+（实测 JDK 21）、Android SDK（compileSdk 36 / ndk 29）
#    local.properties 中配置 sdk.dir

# 2. 签名（可选，缺省则出未签名包）：在 Gradle 用户目录（或 -g 指定的目录）的 gradle.properties 写入
#    RELEASE_STORE_FILE=<你的keystore绝对路径>
#    RELEASE_STORE_PASSWORD=xxx
#    RELEASE_KEY_ALIAS=xxx
#    RELEASE_KEY_PASSWORD=xxx

# 3. 编译
./gradlew -PversionCode=38202 :app:assembleRelease
# 产物: app/build/outputs/apk/app/release/app-app-release.apk
```

注意：

- `minSdk 26`（原版 23 与 `htmlunit-core-js` 依赖冲突，D8 要求 MethodHandle 需 min-api 26）
- 仓库为浅克隆基线整理而来，`versionCode` 已改为可用 `-PversionCode` 覆盖的固定值
- `app/google-services.json` 保留了上游原有 Firebase 配置并补充了 `com.legado.app.custom` 包名条目（google-services 插件要求；代码中未使用 Firebase）

## 文档

| 文档 | 内容 |
|---|---|
| [docs/Legado朗读桌面歌词-技术实现文档.md](docs/Legado朗读桌面歌词-技术实现文档.md) | 完整技术方案：架构、挂载点、修改前后对比、上游新版移植指南、回归验证 |
| [docs/阅读-歌词版-安装与数据迁移指引.md](docs/阅读-歌词版-安装与数据迁移指引.md) | 面向使用者：安装、数据备份迁移、各版本更新记录、FAQ |
| [patch-lyrics-overlay/](patch-lyrics-overlay/) | 15 文件统一 diff 补丁包（`git apply --3way` 可重放）+ 5 个新增文件快照，用于把本功能移植到上游新版本 |

## 移植到上游新版本

上游发布新版后，不需要重新开发：

```bash
git clone https://github.com/LegadoTeam/legado && cd legado
git apply --3way /path/to/patch-lyrics-overlay/legado-lyrics-overlay.patch
# 有冲突时: 用 patch-lyrics-overlay/new-files/ 覆盖 5 个新增文件,
# 再按 docs/技术实现文档.md §9 的挂载点清单手工合并 10 处修改
```

## 免责声明

本项目是开源阅读器 Legado 的功能定制分支，仅供学习与个人使用。
本项目**不内置、不分发任何书源或书籍内容**；使用时应遵守当地法律法规，
并确保对所访问的内容拥有合法授权。所有权利归上游项目及原作者所有。

## Star History

如果这个项目对你有帮助，欢迎 Star ⭐
