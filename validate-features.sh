#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
SFX_CHECK_DIR="results/feature-checks-$(date +%Y%m%d-%H%M%S)-$$"
java -Dfile.encoding=UTF-8 tools/Build.java test
java -Dfile.encoding=UTF-8 -cp dist/shapeflux-j.jar tools/ValidateCompletionRelease.java "$SFX_CHECK_DIR"
printf '验证完成：%s\n' "$SFX_CHECK_DIR"
