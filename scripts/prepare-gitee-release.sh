#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
MANIFEST_PATH=${WEBTVLIVE_UPDATE_MANIFEST_OUTPUT:-"$ROOT_DIR/release/update.json"}
GITEE_RELEASE_BASE="https://gitee.com/lipengzhou/WebTvLive/releases/download"
CHANNELS=(
  gecko-arm64-v8a
  gecko-armeabi-v7a
  webview-arm64-v8a
  webview-armeabi-v7a
)

usage() {
  echo "用法："
  echo "  $0 --notes <更新说明文件>"
  echo "  $0 --verify-remote"
}

latest_build_tool() {
  local tool=$1
  local sdk_root=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
  if [[ -z "$sdk_root" ]]; then
    sdk_root="$HOME/Library/Android/sdk"
  fi
  find "$sdk_root/build-tools" -type f -name "$tool" 2>/dev/null | sort -V | tail -1
}

apk_path() {
  local channel=$1
  local engine=${channel%%-*}
  echo "$ROOT_DIR/app/build/outputs/apk/$engine/release/app-$channel-release.apk"
}

verify_remote() {
  [[ -f "$MANIFEST_PATH" ]] || { echo "缺少 $MANIFEST_PATH" >&2; exit 1; }
  jq -e '
    .schemaVersion == 1 and
    (.versionCode | type == "number") and
    (.versionName | type == "string") and
    (.releaseNotes | length > 0) and
    ([.assets | keys[]] | sort) == ([
      "gecko-arm64-v8a", "gecko-armeabi-v7a",
      "webview-arm64-v8a", "webview-armeabi-v7a"
    ] | sort)
  ' "$MANIFEST_PATH" >/dev/null

  local version_name version_code aapt apksigner temp_dir certificate_digest
  version_name=$(jq -r '.versionName' "$MANIFEST_PATH")
  version_code=$(jq -r '.versionCode' "$MANIFEST_PATH")
  aapt=$(latest_build_tool aapt)
  apksigner=$(latest_build_tool apksigner)
  [[ -x "$aapt" ]] || { echo "找不到 Android build-tools/aapt" >&2; exit 1; }
  [[ -x "$apksigner" ]] || { echo "找不到 Android build-tools/apksigner" >&2; exit 1; }
  temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/webtvlive-remote.XXXXXX")
  trap 'rm -rf -- "$temp_dir"' EXIT
  certificate_digest=""
  for channel in "${CHANNELS[@]}"; do
    local expected_url url expected_size expected_sha apk actual_size actual_sha
    local badging apk_package apk_version_code apk_version_name engine base_version_name apk_certificate
    expected_url="$GITEE_RELEASE_BASE/v$version_name/app-$channel-release.apk"
    url=$(jq -r --arg channel "$channel" '.assets[$channel].url' "$MANIFEST_PATH")
    [[ "$url" == "$expected_url" ]] || {
      echo "$channel 的 URL 不符合 Gitee Release 规则" >&2
      exit 1
    }
    expected_size=$(jq -r --arg channel "$channel" '.assets[$channel].sizeBytes' "$MANIFEST_PATH")
    expected_sha=$(jq -r --arg channel "$channel" '.assets[$channel].sha256' "$MANIFEST_PATH")
    apk="$temp_dir/app-$channel-release.apk"
    curl --fail --silent --show-error --location --max-redirs 5 --output "$apk" "$url"
    actual_size=$(wc -c < "$apk" | tr -d ' ')
    actual_sha=$(shasum -a 256 "$apk" | awk '{print $1}')
    [[ "$actual_size" == "$expected_size" ]] || {
      echo "$channel 的远端文件大小与清单不一致" >&2; exit 1
    }
    [[ "$actual_sha" == "$expected_sha" ]] || {
      echo "$channel 的远端 SHA-256 与清单不一致" >&2; exit 1
    }

    badging=$("$aapt" dump badging "$apk" | sed -n '1p')
    apk_package=$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$badging")
    apk_version_code=$(sed -n "s/.*versionCode='\([^']*\)'.*/\1/p" <<<"$badging")
    apk_version_name=$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$badging")
    engine=${channel%%-*}
    base_version_name=${apk_version_name%-$engine}
    [[ "$apk_package" == "com.lipengzhou.webtvlive" ]] || {
      echo "$channel 的远端 APK 包名无效：$apk_package" >&2; exit 1
    }
    [[ "$apk_version_code" == "$version_code" && "$base_version_name" == "$version_name" ]] || {
      echo "$channel 的远端 APK 版本与清单不一致" >&2; exit 1
    }
    "$apksigner" verify --verbose "$apk" >/dev/null
    apk_certificate=$("$apksigner" verify --print-certs "$apk" |
      sed -n \
        -e 's/^.*Signer[^:]*: certificate SHA-256 digest: //p' \
        -e 's/^Signer #1 certificate SHA-256 digest: //p' | head -1)
    [[ -z "$certificate_digest" || "$certificate_digest" == "$apk_certificate" ]] || {
      echo "四个远端 APK 的签名证书不一致" >&2; exit 1
    }
    certificate_digest=$apk_certificate
    rm -f "$apk"
    echo "已验证 URL、大小、哈希、版本和签名：$channel"
  done
  rmdir "$temp_dir"
  trap - EXIT
}

if [[ ${1:-} == "--verify-remote" ]]; then
  verify_remote
  exit 0
fi

if [[ ${1:-} != "--notes" || -z ${2:-} || $# -ne 2 ]]; then
  usage >&2
  exit 2
fi

NOTES_FILE=$2
[[ -s "$NOTES_FILE" ]] || { echo "更新说明文件不存在或为空：$NOTES_FILE" >&2; exit 1; }
command -v jq >/dev/null || { echo "需要安装 jq" >&2; exit 1; }

if [[ -z ${JAVA_HOME:-} && -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi

AAPT=$(latest_build_tool aapt)
APKSIGNER=$(latest_build_tool apksigner)
[[ -x "$AAPT" ]] || { echo "找不到 Android build-tools/aapt" >&2; exit 1; }
[[ -x "$APKSIGNER" ]] || { echo "找不到 Android build-tools/apksigner" >&2; exit 1; }

cd "$ROOT_DIR"
./gradlew :app:testGeckoDebugUnitTest :app:testWebviewDebugUnitTest :app:assembleRelease -q

VERSION_CODE=""
VERSION_NAME=""
CERTIFICATE_DIGEST=""
ASSETS_JSON='{}'

for channel in "${CHANNELS[@]}"; do
  apk=$(apk_path "$channel")
  [[ -f "$apk" ]] || { echo "缺少 APK：$apk" >&2; exit 1; }

  badging=$("$AAPT" dump badging "$apk" | sed -n '1p')
  apk_package=$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$badging")
  apk_version_code=$(sed -n "s/.*versionCode='\([^']*\)'.*/\1/p" <<<"$badging")
  apk_version_name=$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$badging")
  engine=${channel%%-*}
  base_version_name=${apk_version_name%-$engine}
  [[ "$apk_version_name" == "$base_version_name-$engine" ]] || {
    echo "$channel 的 versionName 与 flavor 不匹配：$apk_version_name" >&2
    exit 1
  }
  [[ "$apk_package" == "com.lipengzhou.webtvlive" ]] || {
    echo "$channel 的 APK 包名无效：$apk_package" >&2; exit 1
  }

  [[ -z "$VERSION_CODE" || "$VERSION_CODE" == "$apk_version_code" ]] || {
    echo "四个 APK 的 versionCode 不一致" >&2; exit 1;
  }
  [[ -z "$VERSION_NAME" || "$VERSION_NAME" == "$base_version_name" ]] || {
    echo "四个 APK 的 versionName 不一致" >&2; exit 1;
  }
  VERSION_CODE=$apk_version_code
  VERSION_NAME=$base_version_name

  "$APKSIGNER" verify --verbose "$apk" >/dev/null
  certificate_digest=$("$APKSIGNER" verify --print-certs "$apk" |
    sed -n \
      -e 's/^.*Signer[^:]*: certificate SHA-256 digest: //p' \
      -e 's/^Signer #1 certificate SHA-256 digest: //p' | head -1)
  [[ -n "$certificate_digest" ]] || { echo "无法读取 $channel 的签名证书" >&2; exit 1; }
  [[ -z "$CERTIFICATE_DIGEST" || "$CERTIFICATE_DIGEST" == "$certificate_digest" ]] || {
    echo "四个 APK 的签名证书不一致" >&2; exit 1;
  }
  CERTIFICATE_DIGEST=$certificate_digest

  size_bytes=$(wc -c < "$apk" | tr -d ' ')
  sha256=$(shasum -a 256 "$apk" | awk '{print $1}')
  url="$GITEE_RELEASE_BASE/v$VERSION_NAME/app-$channel-release.apk"
  ASSETS_JSON=$(jq -c \
    --arg channel "$channel" \
    --arg url "$url" \
    --argjson sizeBytes "$size_bytes" \
    --arg sha256 "$sha256" \
    '. + {($channel): {url: $url, sizeBytes: $sizeBytes, sha256: $sha256}}' \
    <<<"$ASSETS_JSON")
done

mkdir -p "$(dirname "$MANIFEST_PATH")"
TEMP_MANIFEST=$(mktemp "${TMPDIR:-/tmp}/webtvlive-update.XXXXXX")
trap 'rm -f "$TEMP_MANIFEST"' EXIT
jq -n \
  --argjson schemaVersion 1 \
  --argjson versionCode "$VERSION_CODE" \
  --arg versionName "$VERSION_NAME" \
  --rawfile releaseNotes "$NOTES_FILE" \
  --argjson assets "$ASSETS_JSON" \
  '{
    schemaVersion: $schemaVersion,
    versionCode: $versionCode,
    versionName: $versionName,
    releaseNotes: ($releaseNotes | sub("[\\r\\n]+$"; "")),
    assets: $assets
  }' > "$TEMP_MANIFEST"
mv "$TEMP_MANIFEST" "$MANIFEST_PATH"
trap - EXIT

echo "已生成 $MANIFEST_PATH"
echo "版本：$VERSION_NAME ($VERSION_CODE)"
echo "证书 SHA-256：$CERTIFICATE_DIGEST"
echo "请先创建 Gitee Release v$VERSION_NAME 并上传四个 APK，再运行："
echo "  $0 --verify-remote"
