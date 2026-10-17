# Legado「朗读桌面歌词」补丁包

给官方 Legado 加**朗读桌面歌词悬浮窗**的全部改动，打成一个可重放的补丁。
**用途**：原版发布大版本更新后，把本补丁套到新版源码上，快速重建定制版。

- 生成时间：2026-10-08
- 基线 commit：`5198645`（tag `beta`，`fix(cronet): 修复 WebDAV 跨站跳转认证冲突 (#1343)`）
- 交付产物：`dist/Legado-3.26100809-r4-朗读桌面歌词-共存版.apk`（versionCode 38202）

## 目录

| 路径 | 内容 |
|---|---|
| `legado-lyrics-overlay.patch` | 15 个文件（10 改 + 5 新增）的统一 diff，`git apply` 直接可用 |
| `new-files/` | 5 个新增文件的独立快照（patch 冲突时直接拷贝覆盖） |

## 套用到新版上游

```bash
# 1) 拉新版上游
git clone --depth=1 https://github.com/LegadoTeam/legado.git legado-new && cd legado-new

# 2) 先试直接套补丁
git apply --3way /path/to/patch-lyrics-overlay/legado-lyrics-overlay.patch

# 3) 若报冲突（strings.xml / build.gradle / PreferKey.kt 最可能冲突）
#    用 new-files/ 覆盖 5 个新增文件，再按 SKILL.md 的「挂载点清单」手工合并 10 处修改
cp -R /path/to/patch-lyrics-overlay/new-files/* .

# 4) 环境：local.properties 指向本机 SDK
echo "sdk.dir=<你的AndroidSDK路径>" > local.properties

# 5) 签名属性（不在仓库内，避免外泄）
#    .gradle-home/gradle.properties 需含：
#      RELEASE_STORE_FILE / RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD
#    keystore: legado-signing/release-key.jks（自建，SHA-1 e7762e08...bbec2）

# 6) 构建
JAVA_HOME=$HOME/.cmfa-toolchain/jdk ./gradlew \
  -g /path/to/.gradle-home -PversionCode=<比上一版大> :app:assembleRelease
```

> 本仓库是 `--depth=1` 浅克隆（`grafted`），`git rev-list` 计数会失败，
> 因此 `app/build.gradle` 里的 versionCode **改为可被 `-PversionCode=` 覆盖的固定值**，别再依赖自动计数。

## 补丁校验

```bash
cd <已改好的仓库> && git apply --check --reverse legado-lyrics-overlay.patch
# 通过 = 补丁与当前代码完全一致，可精确重放
```

## 完整设计文档

- 需求与方案：`Legado-朗读桌面歌词-需求与方案.md`
- 安装 / 使用 / 数据迁移 / 各轮更新记录：`阅读-歌词版-安装与数据迁移指引.md`
- 交付总览（含像素级验证数据）：`overview.md`
