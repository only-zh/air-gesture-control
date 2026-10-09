#!/usr/bin/env bash
# 一键安装本地工具链（全部落在工程目录内，不污染系统）
#   - JDK 21 (Zulu, macOS arm64)
#   - Android cmdline-tools + platform-tools + platforms;android-35 + build-tools;35.0.0
#   - Gradle 8.9
#   - 模型资源：vosk-model-small-cn-0.22 + hand_landmarker.task -> app/src/main/assets
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TC="$ROOT/.toolchain"
JDK_DIR="$TC/jdk21"
SDK_DIR="$TC/android-sdk"
GRADLE_DIR="$TC/gradle"
ASSETS="$ROOT/app/src/main/assets"

CMDLINE_ZIP_URL="https://dl.google.com/android/repository/commandlinetools-mac-13114758_latest.zip"
# 注：Adoptium 走 GitHub Releases，实测在本机下载会停滞；Azul CDN 速度稳定（约 5MB/s）。
JDK_URL="https://cdn.azul.com/zulu/bin/zulu21.40.17-ca-jdk21.0.6-macosx_aarch64.tar.gz"
# 注：services.gradle.org 实测只有 ~50KB/s，腾讯镜像有 20MB+/s。
GRADLE_URL="https://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip"
# 注：官方源 alphacephei 只有 ~30KB/s，所以下面的 curl 都带 -C - 支持断点续传。
VOSK_MODEL_URL="https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"
HAND_MODEL_URL="https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"

mkdir -p "$TC" "$ASSETS"

log() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }

# ---------------------------------------------------------------- JDK 21
find_java_home() {
  local j
  j="$(find "$JDK_DIR" -type f -name java -perm -u+x -path '*/bin/java' 2>/dev/null | head -1 || true)"
  [ -n "$j" ] && dirname "$(dirname "$j")"
}

if [ -z "$(find_java_home)" ]; then
  log "下载 JDK 21 (Zulu)"
  rm -rf "$JDK_DIR" "$TC/jdk21.tmp"
  curl -fL --retry 3 --retry-delay 2 -o "$TC/jdk21.tar.gz" "$JDK_URL"
  mkdir -p "$TC/jdk21.tmp"
  tar -xzf "$TC/jdk21.tar.gz" -C "$TC/jdk21.tmp"
  inner="$(find "$TC/jdk21.tmp" -maxdepth 1 -mindepth 1 -type d | head -1)"
  mv "$inner" "$JDK_DIR"
  rm -rf "$TC/jdk21.tmp" "$TC/jdk21.tar.gz"
fi

export JAVA_HOME="$(find_java_home)"
if [ -z "$JAVA_HOME" ]; then echo "JDK 安装失败" >&2; exit 1; fi
log "JAVA_HOME=$JAVA_HOME"
"$JAVA_HOME/bin/java" -version

# ------------------------------------------------------- debug 签名密钥
# 不进版本库（公开仓库里放密钥文件不合适），由脚本在本地生成。
# 口令就是 Android 约定的 "android"，只用于 debug 构建。
KEYSTORE="$ROOT/app/keystore/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
  log "生成 debug 签名密钥"
  mkdir -p "$(dirname "$KEYSTORE")"
  "$JAVA_HOME/bin/keytool" -genkeypair -v -keystore "$KEYSTORE" \
    -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
  echo "  已生成 $KEYSTORE"
fi

# ------------------------------------------------------- Android SDK
if [ ! -x "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]; then
  log "下载 Android cmdline-tools"
  curl -fL --retry 3 --retry-delay 2 -o "$TC/cmdline-tools.zip" "$CMDLINE_ZIP_URL"
  rm -rf "$TC/cmdline-tools.tmp"
  mkdir -p "$TC/cmdline-tools.tmp"
  unzip -q -o "$TC/cmdline-tools.zip" -d "$TC/cmdline-tools.tmp"
  mkdir -p "$SDK_DIR/cmdline-tools"
  rm -rf "$SDK_DIR/cmdline-tools/latest"
  mv "$TC/cmdline-tools.tmp/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
  rm -rf "$TC/cmdline-tools.tmp" "$TC/cmdline-tools.zip"
fi

export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="$SDK_DIR"
export ANDROID_USER_HOME="$TC/android-user"
mkdir -p "$ANDROID_USER_HOME"

SDKMANAGER="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"

if [ ! -d "$SDK_DIR/platforms/android-35" ] || [ ! -d "$SDK_DIR/build-tools/35.0.0" ] || [ ! -d "$SDK_DIR/platform-tools" ]; then
  log "接受 SDK licenses"
  yes | "$SDKMANAGER" --sdk_root="$SDK_DIR" --licenses >/dev/null 2>&1 || true
  log "安装 platform-tools / platforms;android-35 / build-tools;35.0.0"
  "$SDKMANAGER" --sdk_root="$SDK_DIR" "platform-tools" "platforms;android-35" "build-tools;35.0.0"
fi

# ---------------------------------------------------------- Gradle 8.9
if [ ! -x "$GRADLE_DIR/gradle-8.9/bin/gradle" ]; then
  log "下载 Gradle 8.9"
  curl -fL -C - --retry 5 --retry-delay 3 -o "$TC/gradle.zip" "$GRADLE_URL"
  mkdir -p "$GRADLE_DIR"
  unzip -q -o "$TC/gradle.zip" -d "$GRADLE_DIR"
  rm -f "$TC/gradle.zip"
fi
export GRADLE_USER_HOME="$TC/gradle-home"
mkdir -p "$GRADLE_USER_HOME"
"$GRADLE_DIR/gradle-8.9/bin/gradle" --version

# ------------------------------------------------------------- 模型资源
if [ ! -f "$ASSETS/hand_landmarker.task" ]; then
  log "下载 hand_landmarker.task"
  curl -fL --retry 3 --retry-delay 2 -o "$ASSETS/hand_landmarker.task" "$HAND_MODEL_URL"
fi
if [ ! -d "$ASSETS/model-cn" ]; then
  log "下载 Vosk 中文小模型 (~44MB)"
  curl -fL -C - --retry 8 --retry-delay 5 -o "$TC/vosk-cn.zip" "$VOSK_MODEL_URL"
  rm -rf "$TC/vosk.tmp"
  mkdir -p "$TC/vosk.tmp"
  unzip -q -o "$TC/vosk-cn.zip" -d "$TC/vosk.tmp"
  inner="$(find "$TC/vosk.tmp" -maxdepth 1 -mindepth 1 -type d | head -1)"
  mv "$inner" "$ASSETS/model-cn"
  rm -rf "$TC/vosk.tmp" "$TC/vosk-cn.zip"
fi

log "工具链就绪"
printf 'JAVA_HOME=%s\nANDROID_HOME=%s\nGRADLE_USER_HOME=%s\n' "$JAVA_HOME" "$ANDROID_HOME" "$GRADLE_USER_HOME"
ls -d "$ASSETS"/* 2>/dev/null || true
