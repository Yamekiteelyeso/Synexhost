#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/android-app"
if ! command -v gradle >/dev/null 2>&1; then
  echo "No se encontró Gradle. Usa Android Studio o GitHub Actions (ver COMPILAR_APK.md)." >&2
  exit 1
fi
gradle :app:assembleDebug --no-daemon
printf '\nAPK compilada: %s\n' "$(pwd)/app/build/outputs/apk/debug/app-debug.apk"
