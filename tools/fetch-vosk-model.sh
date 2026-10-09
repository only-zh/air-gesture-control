#!/usr/bin/env bash
# Vosk 官方源对单连接限速到 ~30KB/s，但支持 Range。
# 这里用多路并行分段下载再拼接，实测能快 5-10 倍。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TC="$ROOT/.toolchain"
ASSETS="$ROOT/app/src/main/assets"
URL="https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"
CONNECTIONS="${CONNECTIONS:-8}"
ZIP="$TC/vosk-cn.zip"

mkdir -p "$TC" "$ASSETS"
[ -d "$ASSETS/model-cn" ] && { echo "模型已存在，跳过"; exit 0; }

TOTAL=$(curl -sIL "$URL" | tr -d '\r' | awk 'tolower($1)=="content-length:"{n=$2} END{print n}')
[ -n "${TOTAL:-}" ] && [ "$TOTAL" -gt 1000000 ] || { echo "拿不到文件大小" >&2; exit 1; }
echo "总大小 $((TOTAL/1024/1024)) MB，使用 $CONNECTIONS 路并行"

CHUNK=$(( (TOTAL + CONNECTIONS - 1) / CONNECTIONS ))

seg_start() { echo $(( $1 * CHUNK )); }
seg_end()   { local e=$(( $1 * CHUNK + CHUNK - 1 )); [ "$e" -ge "$TOTAL" ] && e=$(( TOTAL - 1 )); echo "$e"; }
# 用 wc -c 而不是 stat：BSD 和 GNU 的 stat 参数完全不同，
# 而这段脚本既要在 macOS 本地跑，也要在 Linux 的 CI 里跑。
file_size() { [ -f "$1" ] && wc -c < "$1" | tr -d ' ' || echo 0; }

seg_have()  { file_size "$TC/vosk.part$1"; }
seg_want()  { echo $(( $(seg_end "$1") - $(seg_start "$1") + 1 )); }

# 最多重试 12 轮，每轮只补下没下完的段（断点续传）
for round in $(seq 1 12); do
  pids=()
  for i in $(seq 0 $((CONNECTIONS-1))); do
    [ "$(seg_start "$i")" -ge "$TOTAL" ] && continue
    have=$(seg_have "$i"); want=$(seg_want "$i")
    [ "$have" -ge "$want" ] && continue
    from=$(( $(seg_start "$i") + have ))
    to=$(seg_end "$i")
    echo "  第 $round 轮：补下第 $i 段 $((have/1024))/$((want/1024)) KB"
    curl -sL --retry 20 --retry-delay 2 --retry-all-errors \
         -r "$from-$to" "$URL" >> "$TC/vosk.part$i" &
    pids+=($!)
  done
  [ ${#pids[@]} -eq 0 ] && break
  for p in "${pids[@]}"; do wait "$p" || true; done
done

for i in $(seq 0 $((CONNECTIONS-1))); do
  have=$(seg_have "$i"); want=$(seg_want "$i")
  if [ "$have" -ne "$want" ]; then
    echo "第 $i 段仍不完整（$have/$want 字节），请重跑本脚本" >&2
    exit 1
  fi
done

: > "$ZIP"
for i in $(seq 0 $((CONNECTIONS-1))); do
  [ -f "$TC/vosk.part$i" ] && cat "$TC/vosk.part$i" >> "$ZIP"
done
rm -f "$TC"/vosk.part*

GOT=$(file_size "$ZIP")
[ "$GOT" = "$TOTAL" ] || { echo "拼接后大小 $GOT 与 $TOTAL 不符" >&2; exit 1; }

rm -rf "$TC/vosk.tmp"; mkdir -p "$TC/vosk.tmp"
unzip -q -o "$ZIP" -d "$TC/vosk.tmp"
inner="$(find "$TC/vosk.tmp" -maxdepth 1 -mindepth 1 -type d | head -1)"
rm -rf "$ASSETS/model-cn"
mv "$inner" "$ASSETS/model-cn"
rm -rf "$TC/vosk.tmp" "$ZIP"
echo "模型已就位：$(du -sh "$ASSETS/model-cn" | cut -f1)"
