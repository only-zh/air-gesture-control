#!/usr/bin/env bash
# 编译封装脚本。用法：
#   tools/build.sh                              -> assembleDebug
#   tools/build.sh clean                        -> clean
#   tools/build.sh assembleDebug testDebugUnitTest
#                                               -> 按顺序执行**全部**给定的任务
#
# 注意：这里必须转发 "$@"，不能只取 $1。
# 之前只取 $1，导致 `tools/build.sh assembleDebug testDebugUnitTest`
# 静默地只跑了 assembleDebug，测试根本没执行 —— 这种「以为跑了其实没跑」
# 比直接报错更危险，所以特意把用法写在注释里。
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

# 没给任务就默认 assembleDebug；给了就原样全部转发
if [ "$#" -eq 0 ]; then
  set -- assembleDebug
fi

printf '\n==> gradle %s\n' "$*"
cd "$ROOT"
exec "$TC/gradle/gradle-8.9/bin/gradle" --no-daemon "$@"
