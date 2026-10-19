# Legado 网页端（web 服务）OLED 纯黑主题 + 文字颜色 —— 实现说明

> 版本：r5（`versionCode 38203`，2026-10-19）
> 范围：只动 `modules/web`（内嵌 Web 前端），**Android 端零改动**
> 前置：项目基线见《Legado朗读桌面歌词-技术实现文档.md》，本文只记这次新增的部分

---

## 一、问题定位

| 现象 | 根因（源码位置） |
|---|---|
| 夜间背景不是真黑 | `config/themeConfig.ts` 的 `themes[6]` 由 `#ede7da` + 半透明纹理图叠加而成，永远是"灰"而非"黑" |
| 正文太灰、白天看不清 | `views/BookChapter.vue` 的 `.night { :deep(.chapter) { color: #666 } }`，写死 `#666` |
| 书架书名列黑底看不见 | `components/BookItems.vue` 里 `.name` 写死 `#33373D`、`.sub` `#6b6b6b`、`.intro` `#969ba3`，完全没有夜间分支 |
| 工具按钮 / 设置标签也灰 | `BookChapter.vue` `.night :deep(.tool-icon) { color:#666 }`、`ReadSettings.vue` `li i { color:#666 }` |
| 目录弹窗判断错误 | `PopCatalog.vue` `const isNight = computed(() => store.theme)` —— 拿到的是**主题序号**而不是布尔，除主题 0 外全是"夜间" |

## 二、设计取舍

1. **纯黑做成"新主题"而不是"开关"**
   阅读主题本来就是 `themes[index]` 的索引机制（背景由 `settings.themes[theme].body/content/popup` 三件套给出）。
   加第 8 项（index `7`）成本最低、可回退、和原有 7 个主题同构；
   若做成"对主题 6 加个开关"，要额外处理图片与纯色的互斥，反而更乱。

2. **文字颜色作为覆盖值，默认走"自动"**
   `textColor` 空串 = 自动（夜间 `#E8E2D6` / 白天 `#262626`），非空 = 用户指定色。
   好处：不选也好看；选了就全主题统一生效，不用为每个主题各配一套。

3. **UI 骨架色跟 `element-plus` 变量走，不写死**
   `BookItems.vue` 改 `var(--el-text-color-primary/regular/secondary)`、`var(--el-fill-color-light)`，
   然后在 `assets/oled.css` 里把纯黑主题下这套变量整体压到 `#000`/`#F2F2F2`。
   一处覆盖，全站（弹层、输入框、消息条、表格）同步变黑，避免逐个组件打补丁。

4. **`isNight` 只有一个出处**
   `store/bookStore.ts` 的 getter 改为 `isNightTheme(theme)`（6 或 7），
   `isOled` 单独一个 getter。**禁止**在组件里各自写 `theme == 6`。

## 三、改动清单

### 新增

| 文件 | 作用 |
|---|---|
| `src/utils/readerTheme.ts` | `isNightTheme()` / `isOledTheme()` / `resolveTextColor()` / `syncDocumentTheme()`（给 `<html>` 挂 `dark`、`oled` 类） |
| `src/assets/oled.css` | 纯黑主题下 element-plus 变量覆盖 + `html.oled, html.oled body { background:#000 }` |
| `preview-server.mjs` | 开发用：静态托管 `dist` + 伪造阅读接口，可无手机在浏览器里验 UI（含 `?probe=1` 计算样式探针） |

### 修改

| 文件 | 改动 |
|---|---|
| `src/config/themeConfig.ts` | `themes` 增加 index 7 纯黑；导出 `NIGHT_THEMES` / `OLED_THEME` / `defaultNightTextColor` / `defaultDayTextColor` / `textColorPresets` |
| `src/web.d.ts` | `webReadConfig` 增加 `textColor: string` |
| `src/store/bookStore.ts` | 默认配置加 `textColor: ''`；`isNight` 支持 6/7；新增 `isOled`、`textColor` getter |
| `src/main.ts`、`src/pages/bookshelf/main.js` | 引入 `oled.css`；改为 `watch(() => store.config.theme, theme => syncDocumentTheme(theme), { immediate: true })` |
| `src/views/BookChapter.vue` | 根节点加 `oled` 类；`chapterTheme` 注入 `color: resolveTextColor(config)`；夜间/纯黑两套骨架色 |
| `src/views/BookShelf.vue` | 根节点加 `oled` 类；新增 `.night.oled` 纯黑配色；侧栏 `1px #1C1C1C` 分隔 |
| `src/components/BookItems.vue` | 三处硬编码文字色 → element-plus 语义变量 |
| `src/components/ReadSettings.vue` | 第 8 个主题色块；新增「文字颜色」一行（7 个预设）；夜间/纯黑下标签与选项文字提亮 |
| `src/components/PopCatalog.vue` | 修正 `isNight`；夜间/纯黑章节文字与分隔线 |

### 后端

**无改动。** `getReadConfig` / `saveReadConfig`（`api/controller/BookController.kt`）把整段 JSON 存进
`CacheManager("webReadConfig")`，新增字段随 JSON 自动往返；老配置缺 `textColor` 时由
`setConfig()` 的 `Object.assign` 合并默认值兜住。

## 四、构建与验证

```bash
cd modules/web
npm install                 # 首次；仓库用 pnpm-lock，本地用 npm 亦可
npm test                    # node --test，19 项
npx vue-tsc --build --force # 类型检查必须过
npx vite build              # 产出 dist

cd ../..
rm -rf app/src/main/assets/web/vue && cp -R modules/web/dist app/src/main/assets/web/vue

JAVA_HOME=$HOME/.cmfa-toolchain/jdk ./gradlew \
  -g /Volumes/512SSD/ExtWorkbuddy/2026-10-07-23-04-57/.gradle-home \
  -PversionCode=<递增> :app:assembleRelease
```

> ⚠️ `scripts/sync.js` 只在 GitHub Actions 环境（存在 `GITHUB_ENV`）里才会复制 `dist → assets`，
> 本地必须**手动 `rm -rf` 后整目录 cp**，不要 `cp -R dist/*`（会残留旧哈希文件，白撑大 APK）。

### UI 验证方法（无需手机）

```bash
cd modules/web && node preview-server.mjs        # http://127.0.0.1:8123
# 计算样式探针（无头 Chrome，读真实 computed style，不靠肉眼）
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new --no-sandbox \
  --virtual-time-budget=12000 --dump-dom \
  "http://127.0.0.1:8123/?mock=chapter&theme=7&probe=1&sel=[...]#/chapter"
```

`theme` / `text` 参数通过 cookie 传给 mock 接口（页面 query 不会自动带到 XHR）。

## 五、踩过的坑

1. **`!important` 之前先算特异性**：`.settings-wrapper .setting-list ul li i`（0,3,3）会压过
   `.oled i`（0,2,1），必须写成 `.oled .setting-list ul li i` 才能赢（同分靠后即胜）。
2. **Vue scoped 的 `:global()` 不能包整块嵌套**：`:global(html.dark) { .a { .b {...} } }` 会被编译成
   `html.dark { color: ... }`，嵌套全部丢失。要覆盖就改用**语义变量**或把 `night`/`oled` 类绑到组件根。
3. **`getComputedStyle().backgroundColor` 只返回颜色**，主题 6 的 `#ede7da url(...)` 里的图片看不到，
   别据此判断图片丢失。
4. `npm install` 会重新生成 `src/auto-imports.d.ts`（版本差异带来的格式抖动），
   交付前 `git checkout` 掉，保持补丁干净。

## 六、后续可做

- 书源编辑器页（`pages/source`）目前用 `useDark()` 跟随系统；要跟着阅读主题走，
  把它换成读取 `webReadConfig.theme` + `syncDocumentTheme()` 即可（一处改动）。
- 若要再加「正文行高/边距」等纯黑专用微调，可继续扩 `themes[7]` 的派生规则，不影响其他主题。
