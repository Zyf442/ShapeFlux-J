#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
if [ ! -f dist/shapeflux-j.jar ]; then java tools/Build.java; fi
java -jar dist/shapeflux-j.jar "$@"
