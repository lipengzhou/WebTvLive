#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
GRADLEW=("$ROOT_DIR/gradlew" --no-configuration-cache --dry-run --console=plain)
EMPTY_SIGNING_ENV=(
  WEBTVLIVE_RELEASE_STORE_FILE=
  WEBTVLIVE_RELEASE_STORE_PASSWORD=
  WEBTVLIVE_RELEASE_KEY_ALIAS=
  WEBTVLIVE_RELEASE_KEY_PASSWORD=
)
OUTPUT_FILE=$(mktemp "${TMPDIR:-/tmp}/webtvlive-release-signing.XXXXXX")
trap 'rm -f -- "$OUTPUT_FILE"' EXIT

RELEASE_TASKS=(
  :app:assembleRelease
  :app:assembleWebviewRelease
  :app:bundleGeckoRelease
  :app:build
)

for release_task in "${RELEASE_TASKS[@]}"; do
  if env "${EMPTY_SIGNING_ENV[@]}" "${GRADLEW[@]}" "$release_task" \
      >"$OUTPUT_FILE" 2>&1; then
    echo "缺少签名配置时 $release_task 未失败" >&2
    exit 1
  fi

  if ! grep -q "正式构建缺少签名配置" "$OUTPUT_FILE"; then
    echo "$release_task 失败，但没有输出预期的签名配置错误" >&2
    cat "$OUTPUT_FILE" >&2
    exit 1
  fi
done

for property_name in \
    WEBTVLIVE_RELEASE_STORE_FILE \
    WEBTVLIVE_RELEASE_STORE_PASSWORD \
    WEBTVLIVE_RELEASE_KEY_ALIAS \
    WEBTVLIVE_RELEASE_KEY_PASSWORD; do
  grep -q "$property_name" "$OUTPUT_FILE" || {
    echo "签名配置错误未列出 $property_name" >&2
    exit 1
  }
done

env "${EMPTY_SIGNING_ENV[@]}" "${GRADLEW[@]}" :app:assembleWebviewDebug >/dev/null

echo "正式构建签名门禁验证通过"
