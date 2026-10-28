#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
sfx_dataset_limit="${1:-65536}"
sfx_dataset_id="${2:-kdd99}"
sfx_dataset_output="results/dataset-${sfx_dataset_id}-${sfx_dataset_limit}-$(date +%Y%m%d-%H%M%S)-$$"
java -jar dist/shapeflux-j.jar dataset --dataset "$sfx_dataset_id" --max-rows "$sfx_dataset_limit" --file examples/rmsnorm.sfx --output "$sfx_dataset_output"
