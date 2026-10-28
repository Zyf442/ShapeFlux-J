#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
java tools/Build.java "$@"
