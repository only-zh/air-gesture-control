#!/usr/bin/env bash
# 编译封装脚本。用法：
#   tools/build.sh              -> assembleDebug
#   tools/build.sh clean        -> clean
#   tools/build.sh <gradle任务>  -> 执行任意任务
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TC="$ROOT/.toolchain"

export JAVA_HOME="$TC/jdk21/Contents/Home"
[ -x "$JAVA_HOME/bin/java" ] || export JAVA_HOME="$TC/jdk21"
export ANDROID_HOME="$TC/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$TC/android-user"
export GRADLE_USER_HOME="$TC/gradle-home"
export PATH="$JAVA_HOME/bin:$PATH"

printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$ROOT/local.properties"

TASK="${1:-assembleDebug}"
cd "$ROOT"
exec "$TC/gradle/gradle-8.9/bin/gradle" --no-daemon "$TASK"
