#!/usr/bin/env bash
# 此脚本用于把指定 base 镜像 pin 到 digest，避免 tag 被恶意覆盖。
# 用法：在 main 分支上 bash scripts/pin-digests.sh，将输出 Dockerfile / Dockerfile.debian 的 FROM 替换。
# 需要在有 docker CLI 的环境运行。

set -euo pipefail

IMAGES=(
  "node:20.19.0-bookworm-slim"
  "golang:1.25-alpine"
  "alpine:3.22"
)

# 检查 digest 是否已存在
pin() {
  local image="$1"
  echo "$image@$(docker buildx imagetools inspect "$image" 2>/dev/null | awk -F':' '/^Digest:/ {print $2}' | tr -d ' ')"
}

echo "请将以下 digest 替换到 Dockerfile / Dockerfile.debian 的 FROM 行："
echo
for image in "${IMAGES[@]}"; do
  digest=$(pin "$image" 2>/dev/null || echo "<未取到 digest，请检查网络或 buildx>")
  printf "  %-40s  %s\n" "$image" "$digest"
done

cat <<'NOTE'

替换示例：
  FROM node:20.19.0-bookworm-slim
  →
  FROM node:20.19.0-bookworm-slim@sha256:xxxxxxxxxxxxx

注意：跨平台镜像（multi-arch）要分别 pin。每 6 个月重新跑一次。
NOTE
