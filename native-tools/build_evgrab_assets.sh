#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ZIG=${ZIG:-zig}
OUT="$ROOT/app/src/main/assets"
SRC="$ROOT/native-tools/evgrab.c"
mkdir -p "$OUT"
"$ZIG" cc -target aarch64-linux-musl -O2 -static -s "$SRC" -o "$OUT/tc_evgrab_arm64"
"$ZIG" cc -target arm-linux-musleabihf -O2 -static -s "$SRC" -o "$OUT/tc_evgrab_armv7"
"$ZIG" cc -target x86_64-linux-musl -O2 -static -s "$SRC" -o "$OUT/tc_evgrab_x86_64"
"$ZIG" cc -target x86-linux-musl -O2 -static -s "$SRC" -o "$OUT/tc_evgrab_x86"
