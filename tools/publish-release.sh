#!/usr/bin/env bash
# 发布定制版：上传 APK 到 GitHub Release，并同步仓库根目录的 updates.json（应用内更新数据源）
#
# 用法:
#   tools/publish-release.sh \
#     --apk dist/xxx.apk \
#     --version-code 38204 \
#     --version-name 3.26101921-r6 \
#     --tag r6 \
#     --notes-file /tmp/notes.md
#
# 依赖: curl, python3, git（工作目录须是已配置 remote 的仓库副本）
# Token: 环境变量 GITHUB_TOKEN，或 ~/WorkBuddy/keys/github-pat.txt
set -euo pipefail

REPO="${REPO:-byl669/legado-lyrics-overlay}"
BRANCH="${BRANCH:-master}"
ASSET_NAME="${ASSET_NAME:-Legado-lyrics-overlay-latest.apk}"
GH_API="https://api.github.com"

APK=""; VERSION_CODE=""; VERSION_NAME=""; TAG=""; NOTES_FILE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --apk) APK="$2"; shift 2 ;;
    --version-code) VERSION_CODE="$2"; shift 2 ;;
    --version-name) VERSION_NAME="$2"; shift 2 ;;
    --tag) TAG="$2"; shift 2 ;;
    --notes-file) NOTES_FILE="$2"; shift 2 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

[[ -n "$APK" && -f "$APK" ]] || { echo "APK 不存在: $APK" >&2; exit 2; }
[[ -n "$VERSION_CODE" ]] || { echo "缺少 --version-code" >&2; exit 2; }
[[ -n "$VERSION_NAME" ]] || { echo "缺少 --version-name" >&2; exit 2; }
[[ -n "$TAG" ]] || { echo "缺少 --tag" >&2; exit 2; }

TOKEN="${GITHUB_TOKEN:-}"
if [[ -z "$TOKEN" && -f "$HOME/WorkBuddy/keys/github-pat.txt" ]]; then
  TOKEN="$(tr -d '\n\r' < "$HOME/WorkBuddy/keys/github-pat.txt")"
fi
[[ -n "$TOKEN" ]] || { echo "缺少 GITHUB_TOKEN" >&2; exit 2; }

api() { # method path [data] [content-type]
  local method="$1" path="$2" data="${3:-}" ctype="${4:-application/json}"
  if [[ -n "$data" ]]; then
    curl -sS -X "$method" -H "Authorization: Bearer $TOKEN" \
      -H "Accept: application/vnd.github+json" -H "Content-Type: $ctype" \
      --data-binary "$data" "$GH_API$path"
  else
    curl -sS -X "$method" -H "Authorization: Bearer $TOKEN" \
      -H "Accept: application/vnd.github+json" "$GH_API$path"
  fi
}

SIZE=$(wc -c < "$APK" | tr -d ' ')
SHA=$(shasum -a 256 "$APK" | awk '{print $1}')
NOTES="$(cat "${NOTES_FILE:-/dev/null}" 2>/dev/null || true)"
[[ -n "$NOTES" ]] || NOTES="更新到 $VERSION_NAME"
PUB_DATE="$(date +%Y-%m-%dT%H:%M:%S%z | sed -E 's/([0-9]{2})([0-9]{2})$/\1:\2/')"

echo "==> 目标仓库 $REPO  版本 $VERSION_NAME ($VERSION_CODE)  tag $TAG"
echo "==> APK $APK  $((SIZE/1024/1024))MB  sha256=${SHA:0:16}…"

# 1) 创建或复用 Release
RELEASE_JSON=$(api POST "/repos/$REPO/releases" "$(python3 - "$TAG" "$VERSION_NAME" "$NOTES" <<'PY'
import json,sys
tag, name, notes = sys.argv[1], sys.argv[2], sys.argv[3]
print(json.dumps({
    "tag_name": tag,
    "name": f"{name}",
    "body": notes,
    "draft": False,
    "prerelease": False,
}, ensure_ascii=False))
PY
)")
RELEASE_ID=$(python3 -c 'import json,sys;d=json.load(sys.stdin);print(d.get("id",""))' <<<"$RELEASE_JSON")
if [[ -z "$RELEASE_ID" ]]; then
  echo "!! 创建 Release 失败，尝试复用已存在的 tag:" >&2
  echo "$RELEASE_JSON" | python3 -c 'import json,sys;d=json.load(sys.stdin);print(d.get("message",d))' >&2
  RELEASE_JSON=$(api GET "/repos/$REPO/releases/tags/$TAG")
  RELEASE_ID=$(python3 -c 'import json,sys;d=json.load(sys.stdin);print(d.get("id",""))' <<<"$RELEASE_JSON")
  [[ -n "$RELEASE_ID" ]] || { echo "无法获取 Release id" >&2; exit 1; }
fi
echo "==> Release id = $RELEASE_ID"

# 2) 上传 APK（同名资产先删，保证可重复执行）
EXISTING=$(api GET "/repos/$REPO/releases/$RELEASE_ID/assets" | python3 -c '
import json,sys
name=sys.argv[1]
for a in json.load(sys.stdin):
    if a.get("name")==name: print(a["id"]); break
' "$ASSET_NAME")
if [[ -n "$EXISTING" ]]; then
  echo "==> 删除同名旧资产 $EXISTING"
  api DELETE "/repos/$REPO/releases/assets/$EXISTING" > /dev/null
fi

echo "==> 上传 APK ..."
UPLOAD=$(curl -sS -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary "@$APK" \
  "https://uploads.github.com/repos/$REPO/releases/$RELEASE_ID/assets?name=$ASSET_NAME")
ASSET_URL=$(python3 -c 'import json,sys;d=json.load(sys.stdin);print(d.get("browser_download_url") or d.get("message",""))' <<<"$UPLOAD")
if [[ "$ASSET_URL" != https://* ]]; then
  echo "!! 上传失败: $ASSET_URL" >&2; exit 1
fi
echo "==> 已上传 $ASSET_URL"

# 3) 生成 updates.json（应用内更新唯一数据源）
BASE="https://github.com/$REPO"
LATEST_URL="$BASE/releases/latest/download/$ASSET_NAME"
PINNED_URL="$BASE/releases/download/$TAG/$ASSET_NAME"

python3 - "$VERSION_CODE" "$VERSION_NAME" "$LATEST_URL" "$PINNED_URL" "$SIZE" "$SHA" "$NOTES" "$PUB_DATE" > updates.json <<'PY'
import json,sys
vc, vn, url, pinned, size, sha, notes, pub = sys.argv[1:9]
gh = "https://github.com/"
rel = gh + "byl669/legado-lyrics-overlay/releases/latest/download/"
name = url.rsplit("/",1)[-1]
doc = {
    "versionCode": int(vc),
    "versionName": vn,
    "url": url,
    "backupUrl": pinned,
    "mirrorUrl": "https://ghfast.top/" + url,
    "alternateMirrorUrl": "https://gh-proxy.com/" + url,
    "size": int(size),
    "sha256": sha,
    "notes": notes,
    "pubDate": pub,
}
print(json.dumps(doc, ensure_ascii=False, indent=2))
PY
echo "==> updates.json 已生成"
cat updates.json

# 4) 提交并推送 updates.json
if git rev-parse --is-inside-work-tree > /dev/null 2>&1; then
  git add updates.json
  if ! git diff --cached --quiet; then
    git commit -q -m "chore: 更新应用内更新清单至 $VERSION_NAME ($VERSION_CODE)"
    git pull --rebase --autostash -q origin "$BRANCH" || true
    git push -q origin "$BRANCH"
    echo "==> 已推送 updates.json"
  else
    echo "==> updates.json 无变化"
  fi
else
  echo "!! 当前不是 git 仓库，未推送 updates.json" >&2
fi

# 5) 校验三个镜像
echo "==> 校验更新清单可读性（jsDelivr 可能有缓存延迟，raw 为准）"
for m in \
  "https://raw.githubusercontent.com/$REPO/$BRANCH/updates.json" \
  "https://cdn.jsdelivr.net/gh/$REPO@$BRANCH/updates.json" \
  "https://fastly.jsdelivr.net/gh/$REPO@$BRANCH/updates.json"; do
  code=$(curl -sS -o /tmp/_m.json -w "%{http_code}" "$m" || echo 000)
  printf "  %-95s %s" "$m" "$code"
  if [[ "$code" == "200" ]]; then
    printf "  vc=%s\n" "$(python3 -c 'import json;print(json.load(open("/tmp/_m.json")).get("versionCode"))' 2>/dev/null || echo '?')"
  else
    printf "\n"
  fi
done

echo "==> 完成：$VERSION_NAME 已发布，应用内「检查更新」将读取 versionCode=$VERSION_CODE"
