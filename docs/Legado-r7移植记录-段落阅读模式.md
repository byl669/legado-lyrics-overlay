# r7 移植记录：跟随上游 3.26110117（段落阅读模式）

> 版本：r7（`versionCode 38600`，`3.26112051-r7`，2026-11-20）
> 新基线：上游 `330e606c`（tag `beta`，上游版本 `3.26110117`）
> 上一基线：`5198645`（2026-10-04，r6 及更早）
> 差距：**76 commits / 229 files**

---

## 一、为什么必须改代码结构

上游在这 76 个提交里新增了**段落阅读模式**（#1440 / #1445），朗读链路一分为二：

| 模式 | `contentList` 每一项是什么 | 触发方式 |
|---|---|---|
| 传统（按页/按句） | 一个自然段（网文常见一言一段） | 朗读面板 Play |
| **段落阅读模式** | 一个**朗读块** —— TTS 引擎可达数千字符（`TextToSpeech.getMaxSpeechInputLength()`），HTTP 引擎直接是整段 | 段落视图里 Play（`ACTION_PARAGRAPH_PLAY`） |

r6 的歌词窗口直接把 `contentList[nowSpeak]` 塞进 TextView。在段落模式下这会把整段（几百到几千字）甩进悬浮窗，**必然撑爆**。

## 二、本次改动

### 1. 新增 `LyricsSentence`（按句二次切分）

`app/src/main/java/io/legado/app/service/LyricsSentence.kt`

```
LyricsSentence.at(text, cursor) → Line(prev, current, next, progress)
```

- 按句末标点 `。！？!?；;…` + 换行切分；标点后的 `”’」』）)】》` 归入前一句
- `cursor`（块内已读字符数）定位当前句，并把进度换算成**句内偏移**
- 单句超 `160` 字（通篇无标点的长段）时围绕进度开窗 `±64` 字符，两端加省略号
- 上下句裁剪到 `60` 字

### 2. `doPublishLyrics` 分模式处理

```kotlin
if (paragraphMode) {
    // 段落块 → 按句切分
    val line = LyricsSentence.at(unit, readInUnit)
    window.updateSentence(index, line.prev, line.current, line.next)
    window.updateCharProgress(line.progress)
    return
}
// 传统模式若出现超长项（切模式瞬间残留段落块 / 无标点长段）同样裁剪
if (current != null && current.length > TRADITIONAL_MAX_LINE) { ... }
```

`TRADITIONAL_MAX_LINE = 120`。实测抓到 `len=340` 的段落块残留，裁剪后正常。

### 3. 修的移植期 Bug：段落模式不创建歌词窗

`ensureLyricsWindow()` 原本只在传统 `prepare` 回调里调用。段落模式走
`newParagraphReadAloud → prepareParagraphChapter → play()`，**永远不创建窗口**，
表现是「段落朗读能出声，但退到后台没有歌词」。

修复：`play()` / `resumeReadAloudInternal()` / `publishLyrics()` 三处都先 `ensureLyricsWindow()`（内部有 null 判断，零开销）。

### 4. 冲突合并点（`BaseReadAloudService.kt`，上游 +357/-40）

上游把 `onDestroy` 开头插了 `paragraphSession++`、`prepareParagraphChapter` 等，我们的 hook 逐点重贴：

| 位置 | 内容 |
|---|---|
| 成员区（`readAloudByPage` 之后） | `lyricsWindow` / `lyricsSentenceIndex` / `lyricsProgressStamp` / `mainHandler` |
| `observeLiveBus()` 末尾 | `observeEvent(READ_ALOUD_LYRICS_CONFIG)` + `onLyricsConfigChanged()` |
| `onDestroy()` 开头 | `releaseLyricsWindow()` |
| `play()` / `resumeReadAloudInternal()` | `ensureLyricsWindow()` + `updatePlayState(true)` + `attach()` |
| `pauseReadAloud()` | `updatePlayState(false)` |
| `upTtsProgress()` | `publishLyrics()` |
| 传统 prepare 回调 | `ensureLyricsWindow()` + `updatePlayState(play)` + `publishLyrics(force)` |

`PreferKey.kt` 11 个常量按上游新位置插入（`readAloudWakeLock` 之后）。
上游 `prevP()/nextP()` 已自带 `paragraphMode` 分流到 `seekParagraphItem`，歌词窗的上/下句按钮**无需改动**。

## 三、上游其他变化（不冲突但要知道）

| 项 | 变化 | 处理 |
|---|---|---|
| 数据库 schema | 111 → **113**（新增 `112.json`/`113.json` + `DatabaseMigrations.kt`） | 上游自带迁移，覆盖安装直接升 |
| Kotlin / KSP | Kotlin **2.4.20**、KSP 2.3.12 | 跟随上游 |
| web 依赖 | vue 3.5.44、vite 8.3.0、**@vueuse/core 14→15**、unplugin-icons 24、dompurify 3.4.15、hotkeys-js 4.0.8 | 直接取上游 `package.json`，OLED 改动是源码层，零冲突 |
| `AppUpdate.kt` | 上游**未改动** | 应用内更新零冲突 |

## 四、验证矩阵（Android 14 / arm64 模拟器）

| 用例 | 结果 |
|---|---|
| 覆盖安装（r6 → r7） | ✅ `versionCode 38600`，同签名升级成功 |
| 单元测试 `LyricsSentenceTest` | ✅ 7/7（切句、定位、引号归属、换行、超长开窗、越界钳制、空文本） |
| 编译 + 打包 | ✅ `assembleRelease` 成功 |
| web 产物 | ✅ `npm test` 20/20、`vue-tsc` 通过、`vite build` 成功 |
| web OLED 主题（新上游版本） | ✅ 8 个主题 / 7 档文字色 / 纯黑 `rgb(0,0,0)` / 正文 `rgb(232,226,214)` / 设置面板 `rgb(10,10,10)` |
| **段落模式朗读 + 歌词** | ✅ 窗口出现，按句显示（上一句 / 当前句 / 下一句），不甩整段 |
| 段落模式句子推进 | ✅ 逐句推进，进度正确 |
| 传统模式朗读 + 歌词 | ✅ 按句显示；`len=340` 超长项被裁剪（日志 `Lyrics 超长句已裁剪`） |
| 双击暂停 | ✅ 窗口收起（服务仍在） |
| 恢复播放 | ✅ 窗口自动回来 |
| 拖动 | ✅ 窗口 `mAttrs(0,264)` 位置变更并记忆 |
| 文字不透明 | ✅ 纯白像素 16%、半透明灰仅 4%（抗锯齿边缘） |
| 应用内更新 | ✅ 命中 raw 镜像，`已是最新：远端 38204 <= 本地 38600` |
| 悬浮窗权限 | ✅ 窗口 `fl=NOT_FOCUSABLE LAYOUT_NO_LIMITS`，可接收触摸 |

## 五、遗留 / 下一版可做

- 长按切换按钮显隐未做像素级复验（代码未改动，r4 已验收；本轮拖动/双击已验证同一套手势逻辑）
- 段落视图内的「朗读进度高亮」由上游 `ParagraphAloudHighlight` 负责，与歌词窗互不影响
- 若上游继续改 `BaseReadAloudService`，优先重贴本文「冲突合并点」表格中的 7 处
