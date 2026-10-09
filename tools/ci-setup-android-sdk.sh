#!/usr/bin/env bash
# CI 用的 Android SDK 安装脚本。
#
# 为什么不用 android-actions/setup-android：
# 它在本仓库的 CI 上连续两次失败在「安装 Android SDK」这一步，
# 而失败日志需要仓库管理员权限才能读（公开仓库也读不到），没法排查。
# 与其依赖一个我无法验证的黑盒，不如显式下载 cmdline-tools ——
# 这段逻辑和 tools/setup-toolchain.sh 里已经验证过的部分是一致的。
#
# 用法：
#   bash tools/ci-setup-android-sdk.sh
#
# 环境变量：
#   SDK_ROOT           安装目录。CI 里不传则用 $RUNNER_TEMP/android-sdk
#   ANDROID_USER_HOME  sdkmanager 的 home 目录（必须可写），默认放在 SDK 同级
#   GITHUB_ENV / GITHUB_PATH  存在时会写入，供 Actions 后续步骤使用
set -euo pipefail

CMDLINE_BUILD="13114758"
SDK_ROOT="${SDK_ROOT:-${RUNNER_TEMP:-/tmp}/android-sdk}"

case "$(uname -s)" in
  Darwin) HOST_TAG="mac" ;;
  Linux)  HOST_TAG="linux" ;;
  *)
    echo "不支持的系统：$(uname -s)" >&2
    exit 1
    ;;
esac

URL="https://dl.google.com/android/repository/commandlinetools-${HOST_TAG}-${CMDLINE_BUILD}_latest.zip"
SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"

mkdir -p "$SDK_ROOT"

if [ ! -x "$SDKMANAGER" ]; then
  echo "==> 下载 cmdline-tools ($HOST_TAG)"
  TMP="$(mktemp -d)"
  curl -fL --retry 3 --retry-delay 2 -o "$TMP/cmdline-tools.zip" "$URL"
  mkdir -p "$SDK_ROOT/cmdline-tools"
  rm -rf "$SDK_ROOT/cmdline-tools/latest" "$SDK_ROOT/cmdline-tools/cmdline-tools"
  unzip -q "$TMP/cmdline-tools.zip" -d "$SDK_ROOT/cmdline-tools"
  mv "$SDK_ROOT/cmdline-tools/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
  rm -rf "$TMP"
else
  echo "==> cmdline-tools 已存在，跳过下载"
fi

# sdkmanager 需要一个**可写的 home 目录**。
# 不设它、且 ~/.android 不可写时，它会报
# 「IO exception while downloading manifest」——看起来像网络问题，其实不是。
USER_HOME="${ANDROID_USER_HOME:-$SDK_ROOT/../android-user-home}"
mkdir -p "$USER_HOME"
export ANDROID_USER_HOME="$USER_HOME"
export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"

echo "==> 接受 licenses"
# yes 会在 sdkmanager 读完后收到 SIGPIPE，配合 pipefail 会让整条管道返回非零，
# 所以这里必须吞掉退出码
yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses > /dev/null 2>&1 || true

echo "==> 安装 platform-tools / platforms;android-35 / build-tools;34.0.0"
"$SDKMANAGER" --sdk_root="$SDK_ROOT" --install \
  "platform-tools" "platforms;android-35" "build-tools;34.0.0"

# 把环境变量导出给 Actions 的后续步骤
if [ -n "${GITHUB_ENV:-}" ]; then
  {
    echo "ANDROID_HOME=$SDK_ROOT"
    echo "ANDROID_SDK_ROOT=$SDK_ROOT"
    echo "ANDROID_USER_HOME=$USER_HOME"
  } >> "$GITHUB_ENV"
fi
if [ -n "${GITHUB_PATH:-}" ]; then
  {
    echo "$SDK_ROOT/cmdline-tools/latest/bin"
    echo "$SDK_ROOT/platform-tools"
  } >> "$GITHUB_PATH"
fi

echo "==> 完成：$SDK_ROOT"
ls "$SDK_ROOT"
