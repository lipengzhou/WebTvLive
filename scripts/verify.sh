#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

require_command() {
  local command_name=$1
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "缺少验证依赖：$command_name" >&2
    exit 1
  }
}

echo "==> 检查验证环境"
require_command bash
require_command git
require_command jq
require_command node

if [[ -z ${JAVA_HOME:-} && -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
require_command java

echo "==> 输出构建运行时"
"$ROOT_DIR/gradlew" -version

echo "==> 检查 Shell 语法"
shell_scripts=("$ROOT_DIR"/scripts/*.sh)
for script in "${shell_scripts[@]}"; do
  bash -n "$script"
done

echo "==> 检查 JavaScript 语法"
while IFS= read -r script; do
  node --check "$script"
done < <(find "$ROOT_DIR/app/src" -type f -name '*.js' -print)
node --test "$ROOT_DIR"/scripts/*.test.js

echo "==> 检查 JSON 语法"
jq empty "$ROOT_DIR/release/update.json"
while IFS= read -r json_file; do
  jq empty "$json_file"
done < <(find "$ROOT_DIR/app/src" -type f -name '*.json' -print)

echo "==> 校验发布标签与 versionName 一致"
# 仅当 HEAD 上存在版本 tag 时才校验（普通提交是 no-op）；覆盖/重打 tag 也只要求名字与
# build.gradle.kts 的 versionName 对齐，不关心版本高低。防的是「tag 名与包内版本对不上」的手滑。
version_name=$(sed -n 's/^val webtvliveVersionName = "\(.*\)"$/\1/p' \
  "$ROOT_DIR/app/build.gradle.kts" | head -1)
[[ -n "$version_name" ]] || {
  echo "无法从 app/build.gradle.kts 解析 versionName" >&2
  exit 1
}
while IFS= read -r tag; do
  [[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || continue
  [[ "$tag" == "v$version_name" ]] || {
    echo "发布标签 $tag 与 versionName（$version_name）不一致，应为 v$version_name" >&2
    exit 1
  }
done < <(git -C "$ROOT_DIR" tag --points-at HEAD 2>/dev/null)

echo "==> 验证正式构建签名门禁"
"$ROOT_DIR/scripts/verify-release-signing.sh"

echo "==> 运行双内核单元测试、Lint 和 Debug 构建"
cd "$ROOT_DIR"
./gradlew \
  :app:testGeckoDebugUnitTest \
  :app:testWebviewDebugUnitTest \
  :app:lintGeckoDebug \
  :app:lintWebviewDebug \
  :app:assembleDebug \
  --continue \
  "$@"

echo "==> 检查 Git diff 空白错误"
git diff --check HEAD --
if [[ -n ${VERIFY_DIFF_BASE:-} ]] &&
    git rev-parse --verify --quiet "$VERIFY_DIFF_BASE^{commit}" >/dev/null; then
  git diff --check "$VERIFY_DIFF_BASE" HEAD --
fi

echo "==> 验证通过"
