#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
revision=e742db9f7ba98e63e502b310857d49d047888518
checksum=22eb0d312adf83a74ec5075602928e00768d10e4403d4ae7a0cbfdae95491346
mkdir -p target/upstream
archive=target/upstream/codex.tar.gz
if [ ! -f "$archive" ]; then
  curl --fail --location --retry 3 "https://codeload.github.com/openai/codex/tar.gz/$revision" -o "$archive.tmp"
  mv "$archive.tmp" "$archive"
fi
if command -v sha256sum >/dev/null 2>&1; then
  printf '%s  %s\n' "$checksum" "$archive" | sha256sum -c -
else
  printf '%s  %s\n' "$checksum" "$archive" | shasum -a 256 -c -
fi
if [ ! -d "target/upstream/codex-$revision" ]; then
  tar -xzf "$archive" -C target/upstream
fi
