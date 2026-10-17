# Legado 朗读桌面歌词 —— 技术实现文档

> 面向对象：后续维护者 / 未来「用新版上游重做一遍」的执行人
> 一句话：把 Legado 朗读的内容投影成**系统级悬浮窗**，做成音乐 App 那样的桌面歌词。

| 项 | 值 |
|---|---|
| 基线仓库 | `LegadoTeam/legado` |
| 基线 commit | `5198645`（tag `beta`，`fix(cronet): 修复 WebDAV 跨站跳转认证冲突 (#1343)`） |
| 克隆方式 | `git clone --depth=1`（**浅克隆 / grafted**，影响 versionCode 计算，见 §8.3） |
| 补丁包 | `patch-lyrics-overlay/legado-lyrics-overlay.patch`（15 文件，1531 行，66 KB） |
| 新增文件快照 | `patch-lyrics-overlay/new-files/`（5 个） |
| 当前交付 | `dist/Legado-3.26100809-r4-朗读桌面歌词-共存版.apk`（versionCode **38202**） |
| 包名 | `com.legado.app.custom`（应用名「阅读·歌词版」，与原版**共存**） |
| 签名 | 自建 `legado-signing/release-key.jks`，SHA-1 `e7762e08…bbec02` |
| 配套技能 | `~/.workbuddy/skills/legado-lyrics-overlay`（项目专属）、`~/.workbuddy/skills/android-apk-build`（通用） |

---

## 目录

1. [需求背景与目标](#1-需求背景与目标)
2. [总体设计](#2-总体设计)
3. [功能模块与文件结构](#3-功能模块与文件结构)
4. [歌词解析与显示的实现思路](#4-歌词解析与显示的实现思路)
5. [关键改动点及修改前后对比](#5-关键改动点及修改前后对比)
6. [对外接口与数据结构变更](#6-对外接口与数据结构变更)
7. [依赖与配置调整](#7-依赖与配置调整)
8. [版本演进记录](#8-版本演进记录)
9. [新版上游移植指南（核心）](#9-新版上游移植指南核心)
10. [回归验证](#10-回归验证)
11. [已知限制与后续扩展点](#11-已知限制与后续扩展点)
12. [附录：文件级改动明细](#12-附录文件级改动明细)

---

## 1. 需求背景与目标

### 1.1 背景

Legado（阅读）的朗读功能只能在应用内查看进度。用户切到其它应用（微信、浏览器、地图）后，就看不到正在朗读到哪一句了。音乐类 App 普遍提供「桌面歌词」——一句悬浮在最上层、随播放进度推进的文字。

Legado **没有任何系统级悬浮窗实现**，但已经具备悬浮窗权限的申请基础设施（`lib/permission/`），因此本改动的定位是「只补车，不修路」。

### 1.2 功能目标（已验收）

| 目标 | 说明 |
|---|---|
| 系统级悬浮 | 用 `TYPE_APPLICATION_OVERLAY`，浮在**任何**应用之上 |
| 三段式 | 上一句 / 当前句 / 下一句，当前句最大 |
| 逐字进度 | 当前句按朗读进度逐字着色（卡拉OK 效果） |
| 仅在后台显示 | 阅读界面在前台时自动隐藏，避免与应用内浮条重复 |
| 可拖动 | 竖向拖动换位置，位置持久化 |
| 控制按钮 | 上一句 / 播放暂停 / 下一句 / 关闭 |
| 双击暂停 | 双击悬浮窗 = 暂停 / 恢复朗读 |
| 暂停即隐藏 | 暂停时窗口收起，恢复播放自动回来 |
| 熄屏省电 | 熄屏停止刷新并隐藏，亮屏自动恢复 |
| 外观可调 | 字号 / 颜色 / 背景透明度 / 上下句开关 / 按钮开关 |
| 默认即刻可用 | 不改任何设置也有正确观感 |

### 1.3 非目标（明确不做）

- 不做横向滚动歌词（用户明确表示不需要）
- 不新建独立前台服务（避开 targetSdk 36 的 FGS 限制，见 §2.3）
- 不修改原版 `com.legado.app.release` 的任何数据（全程共存，数据走 App 内备份/恢复）

---

## 2. 总体设计

### 2.1 架构分层与数据流

```
┌─────────────────────────────────────────────────────────────┐
│  朗读引擎层                                                   │
│  BaseReadAloudService（抽象基类，前台服务）                    │
│    ├─ TTSReadAloudService    （系统 TTS）                     │
│    └─ HttpReadAloudService   （在线语音合成）                  │
│                                                              │
│    状态源：                                                    │
│      contentList: List<String>   整章句子列表                  │
│      nowSpeak: Int               当前句下标                    │
│      readAloudNumber: Int        当前句在章内的字符起点         │
│      readAloudChapterStart: Int  由 upTtsProgress() 实时赋值   │
│      isPlay()                    全局播放状态                  │
└────────────────────────┬────────────────────────────────────┘
                         │  publishLyrics()（含 40ms 节流 + 切主线程）
                         ▼
┌─────────────────────────────────────────────────────────────┐
│  窗口层                                                       │
│  ReadAloudLyricsWindow（新增，~680 行）                        │
│    ├─ WindowManager.addView(TYPE_APPLICATION_OVERLAY)        │
│    ├─ canShow()          唯一可见性出口（五重门槛）             │
│    ├─ 手势：拖动 / 双击 / 长按                                 │
│    ├─ 渲染：三段式 TextView + SpannableString 逐字着色         │
│    └─ 熄屏：ACTION_SCREEN_ON/OFF 广播 + 轮询                   │
└────────────────────────┬────────────────────────────────────┘
                         │  反向下发（按钮 / 双击）
                         ▼
        prevParagraph() / nextParagraph() / pauseReadAloud() / resumeReadAloud()
                         （回到 BaseReadAloudService）

         配置通道：SharedPreferences（PreferKey.readAloudLyrics*）
         事件通道：LiveEventBus（EventBus.READ_ALOUD_LYRICS_CONFIG）
```

### 2.2 为什么挂在朗读服务上

| 方案 | 结论 |
|---|---|
| 新建独立前台服务托管悬浮窗 | ❌ 需再申请一个 FGS，targetSdk 36 下前台服务类型受限，且生命周期与朗读服务难以对齐 |
| **挂在 `BaseReadAloudService`** | ✅ 朗读开始即建窗、朗读结束即释放，生命周期天然一致；无需额外权限 |

### 2.3 关键设计决策

| 决策 | 理由 |
|---|---|
| 窗口在**朗读开始时懒创建**（`ensureLyricsWindow()`），服务 `onDestroy()` 释放 | 生命周期与朗读严格对齐，不产生悬空窗口 |
| 逐字进度**不用事件总线**，Service 直接调 `window.updateCharProgress()` | 逐字回调频率极高，走总线会放大开销；直接调用 + 节流更可控 |
| 可见性判定**只留一个出口** `canShow()` | 五个条件（开关/权限/未暂停/亮屏/在后台）集中管理，避免各调用点各写一遍 `hide()` 导致漏判 |
| 透明度**只允许出现在背景 drawable** | 前景文字一旦带 alpha，压在浅色壁纸上就糊成灰字（见 §5.4、§8 的 r4） |
| 上下文句靠**字号**区分（0.62×）而非降透明度 | 同 §5.4 |
| 改动密封在**新增文件 + 少量挂载点**内 | 上游升级时冲突面最小化（见 §9.3） |

---

## 3. 功能模块与文件结构

### 3.1 新增文件（5 个，自包含，可整体拷贝）

| 文件 | 行数 | 职责 |
|---|---|---|
| `app/src/main/java/io/legado/app/service/ReadAloudLyricsWindow.kt` | ~680 | **核心**。窗口创建/显隐/手势/渲染/熄屏省电 |
| `app/src/main/java/io/legado/app/ui/book/read/config/ReadAloudLyricsConfigDialog.kt` | ~100 | 「歌词外观」设置页（`BasePrefDialogFragment` + `LyricsPreferenceFragment`） |
| `app/src/main/res/layout/view_read_aloud_lyrics.xml` | ~100 | 三段式布局 + 4 个控制按钮 |
| `app/src/main/res/xml/pref_config_lyrics.xml` | ~80 | 外观设置项声明 |
| `app/src/main/res/values/colors_lyrics.xml` | 6 | 默认文字色 / 高亮色（**均为纯白 `#FFFFFFFF`**） |

### 3.2 修改文件（10 个，按挂载点合并）

| 文件 | 改动性质 |
|---|---|
| `app/build.gradle` | 共存版包名 + minSdk + versionCode 可覆盖 |
| `app/google-services.json` | 补 `com.legado.app.custom` 的 client 条目 |
| `app/src/main/java/io/legado/app/App.kt` | 启动时一次性颜色迁移 |
| `app/src/main/java/io/legado/app/constant/PreferKey.kt` | 新增 10 个配置键 |
| `app/src/main/java/io/legado/app/constant/EventBus.kt` | 新增 2 个事件名 |
| `app/src/main/java/io/legado/app/service/BaseReadAloudService.kt` | 窗口接线（§9.3 详列） |
| `app/src/main/java/io/legado/app/ui/book/read/config/ReadAloudConfigDialog.kt` | 入口项 + 权限引导 |
| `app/src/main/res/xml/pref_config_aloud.xml` | 朗读设置页新增开关与入口 |
| `app/src/main/res/values/strings.xml` | 英文文案 |
| `app/src/main/res/values-zh/strings.xml` | 中文文案 |

### 3.3 目录树（新增部分）

```
app/src/main/
├── java/io/legado/app/
│   ├── service/
│   │   ├── BaseReadAloudService.kt        ← 改（接线）
│   │   └── ReadAloudLyricsWindow.kt       ← 新增（核心）
│   ├── constant/
│   │   ├── PreferKey.kt                   ← 改（10 键）
│   │   └── EventBus.kt                    ← 改（2 事件）
│   ├── App.kt                             ← 改（颜色迁移）
│   └── ui/book/read/config/
│       ├── ReadAloudConfigDialog.kt       ← 改（入口）
│       └── ReadAloudLyricsConfigDialog.kt ← 新增（外观设置页）
└── res/
    ├── layout/view_read_aloud_lyrics.xml  ← 新增
    ├── xml/
    │   ├── pref_config_aloud.xml          ← 改（总开关）
    │   └── pref_config_lyrics.xml         ← 新增
    └── values/
        ├── colors_lyrics.xml              ← 新增
        ├── strings.xml                    ← 改
        └── values-zh/strings.xml          ← 改
```

---

## 4. 歌词解析与显示的实现思路

> 说明：本项目**不做歌词文件解析**。「歌词」= 朗读引擎已经切分好的句子，直接复用，无需任何新解析器。

### 4.1 数据来源

朗读引擎（`BaseReadAloudService`）内部已有：

| 数据 | 含义 |
|---|---|
| `contentList: List<String>` | 整章按句切分的文本列表 |
| `nowSpeak: Int` | 当前正在朗读的句下标 |
| `readAloudNumber: Int` | 当前句在**章内**的字符起点 |
| `readAloudChapterStart: Int` | 由 `upTtsProgress(progress)` 赋值的实时章内进度 |

### 4.2 句内已读字符的推导（唯一正确口径）

```kotlin
句内已读字符数 = readAloudChapterStart - readAloudNumber
```

原因：朗读**当前句期间** `readAloudNumber` 保持不变，而 `readAloudChapterStart` 随 TTS 逐字回调推进，两者之差就是句内已读字符数。

对应实现（`BaseReadAloudService.doPublishLyrics`）：

```kotlin
window.updateCharProgress(readAloudChapterStart - readAloudNumber)
```

### 4.3 三段式渲染

- 句子取自 `contentList.getOrNull(index - 1 / index / index + 1)`
- 换句判定：`index != lyricsSentenceIndex`（避免同一句重复设置文本）
- 窗口**未创建时**收到的句子先缓存到 `pendingPrev / pendingNext`，`createView()` 末尾补齐，避免首次显示缺行

### 4.4 逐字高亮的实现

用 `SpannableString` + 两个 `ForegroundColorSpan`：

```kotlin
val spannable = SpannableString(text)
if (progress > 0) {
    spannable.setSpan(ForegroundColorSpan(highlightColor), 0, progress, SPAN_EXCLUSIVE_EXCLUSIVE)
}
if (progress < text.length) {
    spannable.setSpan(ForegroundColorSpan(normalColor), progress, text.length, SPAN_EXCLUSIVE_EXCLUSIVE)
}
view.text = spannable
```

- `highlightColor` = 已读进度色（默认 = 歌词色 = 白）
- `normalColor` = 未读部分色（默认白）
- **两者都过 `opaque()` 强制不透明**（见 §4.5）

### 4.5 透明度所有权（本项目核心口径）

> **透明度只属于窗户，不属于字。**

| 对象 | 是否允许 alpha | 实现 |
|---|---|---|
| 窗口背景（圆角黑底） | ✅ **唯一允许** | `createBackground()` 里按 `readAloudLyricsBgTransparency` 计算 alpha |
| 当前句文字 | ❌ | 取色过 `opaque()` |
| 上下句文字 | ❌ | 取色过 `opaque()`，靠字号 0.62× 区分主次 |
| 控制按钮图标 | ❌ | `setColorFilter(opaque(color))` |

```kotlin
/** 强制前景色完全不透明(alpha=0xFF) */
private fun opaque(color: Int): Int =
    Color.argb(255, Color.red(color), Color.green(color), Color.blue(color))
```

这样设计还有个附带好处：即使 SharedPreferences 里被历史版本写进过半透明颜色，也不会生效。

### 4.6 显示时机与可见性门槛链

唯一的可见性出口 `canShow()`，顺序**不可乱**：

```kotlin
private fun canShow(): Boolean {
    val enabled    = isEnabled()                    // 1. 总开关
    val permitted  = enabled && hasPermission()     // 2. 悬浮窗权限
    val playing    = permitted && !paused           // 3. 未暂停（r3 新增）
    val screenOn   = playing && isScreenOn()        // 4. 亮屏（r2 新增）
    ...
    // 5. 仅在后台（可配置）
    val onlyBackground = getPrefBoolean(readAloudLyricsOnlyBackground, true)
    val top = if (onlyBackground) LifecycleHelp.getTopActivity() else null
    result = (top == null)
}
```

要点：

- 轮询（`foregroundPoller`，1500ms）与所有状态变更都只调 `syncVisibility()` → `canShow()`，**没有第二个判断点**
- 只在结果变化时打一条日志，避免轮询刷屏
- **暂停隐藏后用户无法再双击浮窗恢复** → 恢复入口：通知栏 ▶ / 耳机线控 / 回 App 点 ⏯（已在文档与设置文案中说明）

### 4.7 手势系统（拖动 + 长按 + 双击共存）

不依赖 `GestureDetector`（会与拖动逻辑抢事件），在 `setOnTouchListener` 里手工判定：

| 手势 | 判定条件 | 动作 |
|---|---|---|
| 拖动 | 竖向位移 > `DRAG_SLOP_PX`(14px) | 移动窗口，抬手时持久化 Y |
| 长按 | 按住 ≥ `LONG_PRESS_MS`(550ms) 且未拖动 | 切换控件显隐（纯文字模式） |
| 双击 | 两次抬手间隔 ≤ `ViewConfiguration.getDoubleTapTimeout()`(≈300ms) 且落点相差 ≤ `DOUBLE_TAP_SLOP_PX`(80px)、期间无拖动、未触发长按 | 暂停 / 恢复朗读 |

易错点（务必保留）：**长按触发后必须清 `lastTapUptime` 并置 `longPressFired = true`**，否则「长按 + 抬手」会被后续判定成双击的第一下。

### 4.8 熄屏省电

```kotlin
screenReceiver: ACTION_SCREEN_OFF → 停轮询 + hide()
                ACTION_SCREEN_ON  → attach()
```

- 熄屏时系统本就不合成悬浮窗，继续轮询/逐字刷新纯属浪费
- Android 13+ 注册广播需带 `Context.RECEIVER_NOT_EXPORTED`
- `canShow()` 里的 `isScreenOn()` 是第二道保险（防止广播被系统吞掉）

### 4.9 节流与线程模型

| 机制 | 值 / 做法 |
|---|---|
| 逐字刷新节流 | `LYRICS_THROTTLE_MS = 40L`（`force=true` 时绕过） |
| 前后台轮询 | `FOREGROUND_POLL_MS = 1500L` |
| 线程约束 | `ReadAloudLyricsWindow` 对外方法**必须在主线程**；`publishLyrics()` / `updatePlayState()` 内部自行判断并 `mainHandler.post` |
| 暂停状态 | `paused` 用 `@Volatile`（暂停可能来自音频焦点丢失、来电等**非主线程**路径） |

---

## 5. 关键改动点及修改前后对比

### 5.1 `app/build.gradle`

| 项 | 修改前（上游） | 修改后 | 原因 |
|---|---|---|---|
| `versionCode` | `def versionCodeValue = 30000` + `rev-list` 自动计数 | `project.findProperty("versionCode")?.toInteger() ?: 38200` | 浅克隆 `git rev-list` 计数失败；改为可被 `-PversionCode=` 覆盖 |
| `minSdk` | `23` | `26` | `htmlunit-core-js` 的 `MethodHandle` 需 min-api 26（官方 CI 也这么改） |
| `applicationIdSuffix`（release） | `.release` | `.custom` | 与原版共存，互不覆盖 |
| `app_name` 占位符 | 仅 `app_name_a` / `app_name` | 增加 `.custom → @string/app_name_custom` 分支 | 应用名显示为「阅读·歌词版」 |

### 5.2 `BaseReadAloudService.kt` 接线（摘要）

| 挂载点 | 修改前 | 修改后 |
|---|---|---|
| 常量区 | — | `private const val LYRICS_THROTTLE_MS = 40L` |
| 属性区 | 只到 `readAloudByPage` | 追加 `lyricsWindow` / `lyricsSentenceIndex` / `lyricsProgressStamp` / `mainHandler`（⚠️ 注意别割断 `private set`） |
| `observeLiveBus()` | — | `observeEvent<Boolean>(READ_ALOUD_LYRICS_CONFIG) { onLyricsConfigChanged() }` |
| `upTtsProgress()` | 末尾 `postEvent(TTS_PROGRESS, …)` | 末尾追加 `publishLyrics()` |
| `newReadAloud()` | `if (play) play() else pageChanged = true` | 之前插入 `ensureLyricsWindow()` → `updatePlayState(play)` → `publishLyrics(force=true)` |
| `play()` / `resumeReadAloudInternal()` | 无 | 末尾 `lyricsWindow?.let { it.updatePlayState(true); it.attach() }` |
| `pauseReadAloud()` | 无 | 追加 `lyricsWindow?.updatePlayState(false)` |
| `onDestroy()` | 直接 `super.onDestroy()` | 开头 `releaseLyricsWindow()` |
| 新方法 | — | `prevParagraph()` / `nextParagraph()` / `ensureLyricsWindow()` / `publishLyrics(force)` / `doPublishLyrics()` / `releaseLyricsWindow()` / `onLyricsConfigChanged()` |

> **顺序要点**：`newReadAloud()` 里必须先 `updatePlayState(play)` 再 `publishLyrics()`，否则「本次不自动播放」时窗口会闪一下再消失。

### 5.3 「暂停即隐藏」——r3 的落点选择（反面教材）

| 做法 | 评价 |
|---|---|
| 在 `pauseReadAloud()` / 按钮 / 通知栏 / 耳机线控各写一遍 `hide()` | ❌ 迟早漏，且来电打断等路径不在预期内 |
| **把 `未暂停` 并进 `canShow()` 门槛链，`updatePlayState()` 统一收敛** | ✅ 所有暂停入口自动生效 |

`updatePlayState()` 现在做两件事：置 `paused` + 切主线程 `applyPlayStateToViews()` → `syncVisibility()`。图标刷新与窗口显隐共用同一条路径。

### 5.4 「文字半透明看不清」——r4 的根因与修复

**症状**：用户反馈「播放中的文字也有透明度，导致看不清」。
**真实根因**：不是背景设置外溢，而是**前景自己带了 alpha**。历史版本有三处给前景降了透明度：

| 位置 | r3 及更早 | r4 |
|---|---|---|
| 当前句未读部分 | 歌词色 × 60% | 歌词色**原色，零透明** |
| 上一句 / 下一句 | 歌词色 × 55% | 歌词色**原色，零透明**（改靠字号） |
| 控制按钮图标 | 85% | 100% |

修复动作：删除 `UNREAD_ALPHA` / `CONTEXT_ALPHA_RATIO` / `withAlpha()`，新增 `opaque()`，**所有前景取色统一过它**。

**副作用（已告知用户）**：默认「已读进度色」= 「歌词色」= 白 → 默认看不到逐字分界；想看进度把「已读进度颜色」改成别的颜色即可。

### 5.5 颜色一次性迁移——r2 的必要性

Legado 的 `ColorPreference` **只要打开过一次设置页就把默认值落盘**（实测 `readAloudLyricsHighlightColor = -16718337` = `#00E5FF`）。
因此「改 `colors_lyrics.xml` 的默认值」**到不了老用户手机**，必须在 `App.onCreate()` 加一次性迁移：

```kotlin
private fun migrateReadAloudLyricsColor() {
    if (getPrefBoolean(PreferKey.readAloudLyricsColorMigrated, false)) return
    val legacy = 0xFF00E5FF.toInt(); val white = 0xFFFFFFFF.toInt()
    if (getPrefInt(PreferKey.readAloudLyricsHighlightColor, white) == legacy) {
        putPrefInt(PreferKey.readAloudLyricsHighlightColor, white)
    }
    putPrefBoolean(PreferKey.readAloudLyricsColorMigrated, true)
}
```

> 这条规律是通用的：**任何改默认值的行为都必须配一次性迁移**，否则只对新装用户生效。

---

## 6. 对外接口与数据结构变更

### 6.1 `PreferKey` 新增配置键（10 个）

| 键 | 类型 | 默认 | 含义 |
|---|---|---|---|
| `readAloudLyrics` | Boolean | `false` | 桌面歌词总开关 |
| `readAloudLyricsTextSize` | Int(sp) | `20` | 当前句字号（12–36） |
| `readAloudLyricsTextColor` | Int(color) | `#FFFFFFFF` | 歌词色（未读部分） |
| `readAloudLyricsHighlightColor` | Int(color) | `#FFFFFFFF` | 已读进度色 |
| `readAloudLyricsBgTransparency` | Int(%) | `45` | 背景透明度（0–100，**仅背景**） |
| `readAloudLyricsShowContext` | Boolean | `true` | 显示上下句 |
| `readAloudLyricsShowControls` | Boolean | `true` | 显示控制按钮 |
| `readAloudLyricsOnlyBackground` | Boolean | `true` | 仅应用在后台时显示 |
| `readAloudLyricsY` | Int(px) | `-1` | 悬浮窗 Y（`-1` = 未设置，回落默认） |
| `readAloudLyricsColorMigrated` | Boolean | `false` | 颜色迁移标记位（内部用） |

### 6.2 `EventBus` 新增事件（2 个）

| 常量 | 值 | 用途 |
|---|---|---|
| `READ_ALOUD_LYRICS_CONFIG` | `"readAloudLyricsConfig"` | 配置变更 → 通知 Service 刷新窗口 |
| `READ_ALOUD_LYRICS_TEXT` | `"readAloudLyricsText"` | 已定义（当前逐字进度走直接调用，未使用该事件，保留作扩展） |

### 6.3 `ReadAloudLyricsWindow` 对外 API

| 方法 | 说明 |
|---|---|
| `isEnabled()` | 总开关是否开启 |
| `hasPermission()` | 是否已获得悬浮窗权限 |
| `attach()` | 朗读开始时调用：注册熄屏监听 + 启动轮询 + 同步可见性 |
| `release()` | 朗读结束时调用：注销监听 + 停轮询 + 释放视图 |
| `syncVisibility()` | 按 `canShow()` 收敛显隐 |
| `refreshAppearance()` | 重读配置刷新（背景/文字/控件/位置） |
| `show()` / `hide()` | 底层显隐（一般不用直接调，走 `syncVisibility()`） |
| `updateSentence(index, prev, current, next)` | 换句 |
| `updateCharProgress(readChars)` | 逐字进度 |
| `updatePlayState(isPlaying)` | 播放态同步（并收敛显隐） |
| `isShowing`（只读属性） | 当前是否正在显示 |

反向下发（由窗口调 Service）：

| 调用 | 触发 |
|---|---|
| `service.prevParagraph()` | 点 ⏮ |
| `service.nextParagraph()` | 点 ⏭ |
| `service.pauseReadAloud()` / `resumeReadAloud()` | 点 ⏯ 或双击 |

### 6.4 布局 ID（供后续维护/扩展）

| ID | 类型 | 用途 |
|---|---|---|
| `lyrics_root` | LinearLayout | 根 |
| `tv_lyrics_prev` / `tv_lyrics_current` / `tv_lyrics_next` | TextView | 三段式 |
| `ll_lyrics_controls` | LinearLayout | 按钮容器 |
| `iv_lyrics_prev` / `iv_lyrics_play` / `iv_lyrics_next` / `iv_lyrics_close` | ImageButton | 四个控制按钮 |

> ⚠️ 布局由 Service 用 **Application Context** inflate，**不要引用 `?attr/xxx` 自定义主题属性**，颜色与背景一律在代码里设。

### 6.5 存储位置

| 数据 | 位置 |
|---|---|
| 歌词配置 | 应用默认 SharedPreferences（`com.legado.app.custom_preferences.xml`） |
| 悬浮位置 | 同上，键 `readAloudLyricsY` |

---

## 7. 依赖与配置调整

### 7.1 构建环境依赖

| 项 | 值 |
|---|---|
| JDK | `~/.cmfa-toolchain/jdk`（JDK 21） |
| Android SDK | `~/.cmfa-toolchain/android-sdk`（写在 `local.properties` 的 `sdk.dir`） |
| Gradle 用户目录 | 工作区 `.gradle-home`（**签名属性放这里，不进仓库**） |
| Gradle / AGP / Kotlin | 随上游（本次 8.14.4 / 8.13.2 / 2.4.10） |
| compileSdk / targetSdk | 36 / 36 |
| minSdk | **26**（改动项） |

### 7.2 构建命令（唯一正确姿势）

```bash
cd legado && JAVA_HOME=$HOME/.cmfa-toolchain/jdk \
  ./gradlew -g <本机Gradle用户目录> \
  -PversionCode=<递增> :app:assembleRelease
```

- `-g` **必须绝对路径**，否则跑去找 `~/.gradle`，读不到签名属性
- `-PversionCode` **逐版递增**（r4 = 38202）
- 产物：`app/build/outputs/apk/app/release/app-app-release.apk`

### 7.3 签名配置

`.gradle-home/gradle.properties`（**不在仓库内**）：

```
RELEASE_STORE_FILE=<你的keystore绝对路径>
RELEASE_STORE_PASSWORD=***
RELEASE_KEY_ALIAS=***
RELEASE_KEY_PASSWORD=***
```

> ⚠️ **debug 变体也会吃 `RELEASE_STORE_FILE`**，导致 debug/release 同签名——排查签名不一致时要想到这层。

### 7.4 `google-services.json`（易踩）

新增包名 `com.legado.app.custom` 后，**必须在该 json 里补一条 client 记录**（含 `package_name`），否则 Google Services 插件校验不过导致 release 构建失败。

> 工程里其实**完全没用 Firebase**，这是遗留配置。补条目即可，不要动插件配置。

---

## 8. 版本演进记录

| 版本 | 交付文件 | 关键内容 | versionCode |
|---|---|---|---|
| 首版（已废弃） | `Legado-3.26100723-朗读桌面歌词.apk` | 悬浮歌词（含青色高亮）；包名沿用原版，**无法共存** | 30000 |
| 共存版首版 | `...-3.26100808-朗读桌面歌词-共存版.apk` | `com.legado.app.custom` + 自建签名 | 38200 |
| r2 | `...-3.26100809-朗读桌面歌词-共存版.apk` | 逐字改同色、颜色一次性迁移、熄屏停刷新 | 38200 |
| r3 | `...-3.26100809-r3-朗读桌面歌词-共存版.apk` | 双击暂停/恢复、暂停即隐藏（并进 `canShow()`） | 38201 |
| **r4（当前）** | `...-3.26100809-r4-朗读桌面歌词-共存版.apk` | 文字恒不透明，透明度只作用于背景 | **38202** |

> versionCode 均为 `aapt2 dump badging` **实测值**，不是估计。**这类数字不要凭记忆写。**

---

## 9. 新版上游移植指南（核心）

> 触发条件：上游 `LegadoTeam/legado` 发布大版本更新，需要把桌面歌词重新打上新版。
> **铁律：先套补丁，别从零写。**

### 9.1 移植总流程

```bash
# 1) 拉新版上游
git clone --depth=1 https://github.com/LegadoTeam/legado.git legado-new && cd legado-new

# 2) 先试直接套补丁
git apply --3way /path/to/patch-lyrics-overlay/legado-lyrics-overlay.patch

# 3) 冲突处理（见 §9.2）
#    a. 5 个新增文件直接用快照覆盖
cp -R /path/to/patch-lyrics-overlay/new-files/* .
#    b. 10 个修改文件按 §9.3 挂载点清单手工合并

# 4) 重做构建配置（见 §9.4）

# 5) 构建 → 回归验证（见 §10）
JAVA_HOME=$HOME/.cmfa-toolchain/jdk ./gradlew -g <abs>/.gradle-home \
  -PversionCode=<比上一版大> :app:assembleRelease
```

### 9.2 冲突高发区与处理

| 文件 | 冲突概率 | 处理方式 |
|---|---|---|
| `res/values/strings.xml` | 🔴 高 | 上游新增字符串常落在同一区域。**只保留 `read_aloud_lyrics*` 与 `app_name_custom` 两类新增**，其余以上游为准 |
| `res/values-zh/strings.xml` | 🔴 高 | 同上 |
| `app/build.gradle` | 🔴 高 | 按 §9.4 逐项重做，不要期望 patch 干净落地 |
| `constant/PreferKey.kt` | 🟡 中 | 新增常量是**纯追加**，找到文件末尾的常量区插入即可 |
| `service/BaseReadAloudService.kt` | 🟡 中 | **按函数名锚定**（§9.3），不要按行号；上游改动朗读逻辑时需人工确认锚点仍在 |
| `App.kt` | 🟡 中 | 迁移函数与 `onCreate` 调用是纯追加 |
| `ReadAloudConfigDialog.kt` | 🟢 低 | `onPreferenceTreeClick` 加一个 `when` 分支 + `onSharedPreferenceChanged` 加一块 |
| `pref_config_aloud.xml` | 🟢 低 | 在 `readAloudControls` 之后追加两项 |
| `google-services.json` | 🟢 低 | 直接补条目 |
| 5 个新增文件 | 🟢 无 | 快照覆盖 |

### 9.3 挂载点清单（按函数名锚定，不写行号）

**`BaseReadAloudService.kt`**：

```
常量区            + LYRICS_THROTTLE_MS = 40L
属性区            + paragraphStartPos / lyricsWindow / lyricsSentenceIndex
                  + lyricsProgressStamp / mainHandler
                    ⚠️ 插属性时别割断 readAloudByPage 的 private set
observeLiveBus()  + observeEvent<Boolean>(READ_ALOUD_LYRICS_CONFIG) { onLyricsConfigChanged() }
upTtsProgress()   + publishLyrics()（追加在末尾）
newReadAloud()    + ensureLyricsWindow() / updatePlayState(play) / publishLyrics(force=true)
                    （插在 withContext(Main.immediate) 内，play() 之前）
play()            + lyricsWindow?.let { it.updatePlayState(true); it.attach() }
pauseReadAloud()  + lyricsWindow?.updatePlayState(false)
resumeReadAloudInternal() + 同 play()
onDestroy()       + releaseLyricsWindow()（放在开头）
新增方法区        + prevParagraph() / nextParagraph() / ensureLyricsWindow() /
                    publishLyrics(force) / doPublishLyrics() / releaseLyricsWindow() /
                    onLyricsConfigChanged()
```

**核心口径**（无论上游怎么改都要保持不变）：

```kotlin
句内已读字符数 = readAloudChapterStart - readAloudNumber
```

> 若上游重构了朗读进度相关的字段名，**先确认这套推导在新版里依然成立再改**；`contentList` / `nowSpeak` 若被重命名，需同步改 `doPublishLyrics()`。

### 9.4 构建配置重做清单

- [ ] `versionCode`：改成 `project.findProperty("versionCode")?.toInteger() ?: <兜底>`（浅克隆必须）
- [ ] `minSdk 26`（若上游已 ≥26 则无需改）
- [ ] release 变体 `applicationIdSuffix '.custom'`
- [ ] `manifestPlaceholders` 增加 `.custom → @string/app_name_custom`
- [ ] `strings.xml` / `values-zh/strings.xml` 保留 `app_name_custom`
- [ ] `google-services.json` 补 `com.legado.app.custom` 的 client 条目
- [ ] `local.properties` 写 `sdk.dir`
- [ ] `.gradle-home/gradle.properties` 写 4 个签名属性

### 9.5 移植后自检

```bash
# 补丁一致性（在已改好的仓库里）
git apply --check --reverse patch-lyrics-overlay/legado-lyrics-overlay.patch
# 通过 = 补丁与当前代码一致，可精确重放
```

> 补丁包已经过两重验证：① `git apply --check --reverse` 与当前工作树一致；② 在 `HEAD` 的纯净检出上 `git apply --check` **15/15 全部通过**。

---

## 10. 回归验证

### 10.1 构建验证

- `:app:assembleRelease` 成功
- `apksigner verify -v` → v2 / v3 通过，证书 `CN=Legado Custom`
- `aapt2 dump badging` 核对 `versionCode` / `package`

### 10.2 装机与覆盖安装

```bash
ADB=~/.cmfa-toolchain/android-sdk/platform-tools/adb
$ADB -s emulator-5554 install -r <apk>
$ADB -s emulator-5554 shell dumpsys package com.legado.app.custom | grep -E "versionCode|firstInstallTime"
# firstInstallTime 保持旧值 = 确实是覆盖安装而非重装
```

### 10.3 悬浮窗存在性判定（最容易自己坑自己）

```bash
$ADB shell dumpsys window windows | grep -c "package=com.legado.app.custom appop=SYSTEM_ALERT_WINDOW"
```

- 锚点用 **`appop=SYSTEM_ALERT_WINDOW`**
- `dumpsys` 里窗口类型字段**缩写为 `ty=APPLICATION_OVERLAY`**，grep `TYPE_APPLICATION_OVERLAY` 会一无所获 → 别据此误判「窗口没显示」

### 10.4 功能回归矩阵（每轮必跑）

| 验证项 | 预期 | 判定方式 |
|---|---|---|
| 覆盖安装 | 同签名升级，数据不丢 | `install -r` 成功 + `firstInstallTime` 不变 |
| 双击悬浮窗 | 暂停 + 窗口消失 | 窗口数 1 → 0 |
| 恢复播放 | 窗口自动回来，句子继续推进 | 窗口数 0 → 1 |
| 单击 | 无动作 | 窗口数保持 1 |
| 拖动 | 位置持久化 | `readAloudLyricsY` 变化 |
| 长按 | 控件隐藏，窗口保留，朗读继续 | 窗口数保持 1 |
| 熄屏 | 窗口隐藏 + 轮询停止 | 亮屏自动恢复 |
| 文字不透明 | 前景无半透明灰 | 像素统计（§10.5） |
| 朗读自然结束 | 服务销毁 → 窗口释放 | logcat `TTSReadAloudService onDestroy` |

### 10.5 像素级验证（观感类改动的硬证据）

同一壁纸、同一悬浮位置，修复前后各截一张，统计窗口内**中性色**前景像素：

```python
from PIL import Image
from collections import Counter
im = Image.open(path).convert("RGB"); W, H = im.size; px = im.load()
h = Counter()
for y in range(Y_TOP, Y_BOTTOM):
    for x in range(W):
        r, g, b = px[x, y]
        if max(r, g, b) - min(r, g, b) <= 14 and min(r, g, b) >= 60:  # 只统计中性色，排除彩色壁纸
            h[max(r, g, b)] += 1
pure = sum(n for v, n in h.items() if v >= 252)          # 纯白前景
dim  = sum(n for v, n in h.items() if 120 <= v <= 215)   # 半透明灰前景
```

判定口径：

| 灰度 | 含义 |
|---|---|
| 252–255 | 完全不透明前景（目标状态） |
| ~140 | 0.55 白 → 次要文字降透明（r4 已消除） |
| ~156 | 0.60 白 → 未读部分降透明（r4 已消除） |
| ~216 | 0.85 白 → 图标降透明（r4 已消除） |
| ~60–100 | 窗口背景合成色（**应保持不变**） |

> 残留的中间灰基本来自**字形抗锯齿边缘**，不是半透明前景，别误判成没修好。
> 字号变化会改变窗口高度，扫描 y 区间要按窗口实际位置重新框定，别写死坐标。

### 10.6 模拟器手势自动化技巧

- **截图坐标 × (真实宽度 / 展示宽度) = adb 坐标**（1080px 屏在 Read 工具里是 480px 宽 → 倍率 **2.25**）
- **双击**：`adb shell 'input tap X Y; input tap X Y'`（单条 shell 内连发，间隔 ≈200–300ms，落在 300ms 双击窗内）
- **长按**：`input swipe X Y X Y 800`
- **拖动**：`input swipe X1 Y1 X2 Y2 300`
- **恢复播放**：`input keyevent 126`（KEYCODE_MEDIA_PLAY，走 MediaSession，不用点 UI）
- **Legado 有多个 LAUNCHER Activity** → `monkey -c LAUNCHER` 会 exit -5，必须 `am start -n <pkg>/<Activity>`
- 从 `ReadBookActivity` 回书架要 `am force-stop` 后重开（`am start MainActivity` 清不掉栈顶阅读页）
- 版本变化会弹「更新日志」对话框挡住书架，脚本要先点掉 CLOSE

---

## 11. 已知限制与后续扩展点

### 11.1 已知限制

| 限制 | 说明 |
|---|---|
| 无法与原版覆盖安装 | Android 强制「同包名 + 同私钥」。官方私钥在 GitHub Secrets 拿不到 → 只能共存版，数据走 App 内备份/恢复 |
| 备份路径 | **必须改到 `Download` 目录**（默认在 `Android/data/<包名>/files`，卸载会被系统删） |
| 暂停后无法双击恢复 | 暂停即隐藏，恢复入口只剩通知栏 / 耳机线控 / 回 App |
| 默认看不到逐字分界 | 默认两色都是白（这是刻意设计）；想看进度就改「已读进度颜色」 |
| 单元测试 | 上游有 9 个既有失败用例（`android.util.Log not mocked`），与本次改动无关；`ReadAloud*` 相关全部通过 |

### 11.2 后续扩展点（已预留）

| 方向 | 落点 | 改动量 |
|---|---|---|
| **暂停时歌词留着、只变暗**（用户预留备选） | `canShow()` 里改一处判断 + `updatePlayState()` 里控制背景 alpha | 极小 |
| 单击动作 | `handleTap()` 的 single-tap 分支（当前留空） | 小 |
| 横向滚动歌词 / 多行 | `view_read_aloud_lyrics.xml` + `applySentenceTexts()` | 中 |
| 逐字进度走事件总线 | 已定义 `EventBus.READ_ALOUD_LYRICS_TEXT`，可直接启用 | 小 |
| 更多外观项 | `pref_config_lyrics.xml` 追加，`applyTextAppearance()` 消费 | 小 |

---

## 12. 附录：文件级改动明细

### 12.1 补丁包结构

```
patch-lyrics-overlay/
├── legado-lyrics-overlay.patch    # 15 文件统一 diff，1531 行，66 KB
├── new-files/                     # 5 个新增文件快照（冲突时 cp -R 覆盖）
│   └── app/src/main/...
└── README.md                      # 套用步骤 + 校验命令 + 环境依赖
```

### 12.2 生成与校验命令

```bash
# 生成（在已改好的仓库里）
git add -N . && git diff --binary > legado-lyrics-overlay.patch && git reset -q

# 校验一致性
git apply --check --reverse legado-lyrics-overlay.patch

# 在纯净上游上验证可应用（dry-run）
git worktree add -f --detach /tmp/legado-pristine HEAD
cd /tmp/legado-pristine && git apply --check --verbose <patch>
cd - && git worktree remove --force /tmp/legado-pristine
```

### 12.3 逐文件改动一览

| # | 文件 | 新增/修改 | 关键点 |
|---|---|---|---|
| 1 | `service/ReadAloudLyricsWindow.kt` | 新增 | 核心窗口类，~680 行 |
| 2 | `ui/book/read/config/ReadAloudLyricsConfigDialog.kt` | 新增 | 外观设置页 |
| 3 | `res/layout/view_read_aloud_lyrics.xml` | 新增 | 三段式 + 4 按钮 |
| 4 | `res/xml/pref_config_lyrics.xml` | 新增 | 外观设置项 |
| 5 | `res/values/colors_lyrics.xml` | 新增 | 默认色（均白） |
| 6 | `constant/PreferKey.kt` | 修改 | +10 常量 |
| 7 | `constant/EventBus.kt` | 修改 | +2 事件 |
| 8 | `service/BaseReadAloudService.kt` | 修改 | 窗口接线 |
| 9 | `App.kt` | 修改 | 颜色一次性迁移 |
| 10 | `ui/book/read/config/ReadAloudConfigDialog.kt` | 修改 | 入口 + 权限引导 |
| 11 | `res/xml/pref_config_aloud.xml` | 修改 | +开关 +入口 |
| 12 | `res/values/strings.xml` | 修改 | 英文文案 |
| 13 | `res/values-zh/strings.xml` | 修改 | 中文文案 |
| 14 | `app/build.gradle` | 修改 | 共存版 + 构建配置 |
| 15 | `app/google-services.json` | 修改 | +包名 client 条目 |

---

*文档基于基线 `5198645` 的实际代码与补丁生成，所有数值（versionCode、阈值、行数）均为实测。*
