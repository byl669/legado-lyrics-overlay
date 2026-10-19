# Legado 定制版补丁包

把**定制内容**打成一个可重放的补丁，覆盖两块功能：

1. **朗读桌面歌词悬浮窗**（系统级 overlay，逐字进度、拖动/双击/长按）
2. **网页端 OLED 纯黑主题 + 文字颜色**、**定制版应用内更新**（读本仓库 `updates.json`）

**用途**：上游发布大版本后，把本补丁套到新版源码上，快速重建定制版。

- 生成时间：2026-10-19（r6）
- 基线 commit：`5198645`（tag `beta`，`fix(cronet): 修复 WebDAV 跨站跳转认证冲突 (#1343)`）
- 对应产物：`Legado-3.26101921-r6…apk`（`versionCode 38204`）

## 目录

| 路径 | 内容 |
|---|---|
| `legado-lyrics-overlay.patch` | 全部源码改动的统一 diff（含新增文件），`git apply` 直接可用 |
| `new-files/` | 8 个新增文件的独立快照（patch 冲突时直接拷贝覆盖） |

**不含** `app/src/main/assets/web/vue/**`（前端构建产物）——需在 `modules/web` 下
`npm install && npx vite build`，再把 `dist` 整目录复制过去（详见 `docs/Legado网页端OLED主题-技术实现.md`）。

## 套用到新版上游

```bash
# 1) 拉新版上游
git clone --depth=1 https://github.com/LegadoTeam/legado.git legado-new && cd legado-new

# 2) 先试直接套补丁
git apply --3way /path/to/patch-lyrics-overlay/legado-lyrics-overlay.patch

# 3) 若报冲突（strings.xml / build.gradle / PreferKey.kt 最可能冲突）
#    用 new-files/ 覆盖 8 个新增文件，再按 docs/ 里的「挂载点清单」手工合并修改处
cp -R /path/to/patch-lyrics-overlay/new-files/* .

# 4) 环境：local.properties 指向本机 SDK
echo "sdk.dir=<你的AndroidSDK路径>" > local.properties

# 5) 签名属性（不在仓库内，避免外泄）
#    .gradle-home/gradle.properties 需含：
#      RELEASE_STORE_FILE / RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD

# 6) 构建
JAVA_HOME=<你的JDK21> ./gradlew \
  -g /path/to/.gradle-home -PversionCode=<比上一版大> -PversionName=<版本名> :app:assembleRelease
```

> 本仓库是 `--depth=1` 浅克隆（`grafted`），`git rev-list` 计数会失败，
> 因此 `app/build.gradle` 里的 versionCode/versionName **都支持 `-P` 显式覆盖**；
> 显式给了 `-PversionCode` 就**不再**用提交数推算。

## 补丁校验

```bash
cd <已改好的仓库> && git apply --check --reverse legado-lyrics-overlay.patch
# 通过 = 补丁与当前代码完全一致，可精确重放
```

## 完整设计文档

仓库 `docs/` 下：

- `Legado朗读桌面歌词-技术实现文档.md` —— 悬浮窗架构、挂载点、移植指南
- `Legado网页端OLED主题-技术实现.md` —— 网页端纯黑主题与文字颜色
- `应用内更新-发布与维护.md` —— 更新数据流与一键发布脚本
- `阅读-歌词版-安装与数据迁移指引.md` —— 面向使用者的安装/迁移/更新记录
