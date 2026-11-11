#!/usr/bin/env bash
# Runs the pure-Kotlin core tests without an Android SDK.
# Copies the project to a local build directory first because Android shared
# storage (/sdcard) does not support the file locks Gradle needs.
set -euo pipefail
SRC="$(cd "$(dirname "$0")/.." && pwd)"
DEST="${ZED_BUILD_DIR:-$HOME/zed-build}"
mkdir -p "$DEST"
if command -v rsync >/dev/null; then
  rsync -a --delete --exclude '.git' --exclude 'build' --exclude '.gradle' --exclude 'app' "$SRC/" "$DEST/"
else
  find "$DEST" -mindepth 1 -maxdepth 1 ! -name build ! -name .gradle -exec rm -rf {} +
  (cd "$SRC" && tar --exclude=.git --exclude=build --exclude=.gradle --exclude=./app -cf - .) | (cd "$DEST" && tar -xf -)
fi
cd "$DEST"
chmod +x gradlew
./gradlew -Pzed.coreOnly=true -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs=-Xmx768m --no-daemon :core:test "$@"
