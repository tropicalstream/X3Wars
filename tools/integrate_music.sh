#!/bin/sh
# Copy authored tracks from the repo's music/ workspace into the app's
# assets so they ship inside the APK. Run from anywhere; then rebuild.
cd "$(dirname "$0")/.." || exit 1
SRC=music
DST=app/src/main/assets/music
count=0
for d in "$SRC"/*/; do
  scene=$(basename "$d")
  for f in "$d"*.mp3 "$d"*.ogg "$d"*.m4a "$d"*.MP3; do
    [ -f "$f" ] || continue
    mkdir -p "$DST/$scene"
    cp -f "$f" "$DST/$scene/"
    count=$((count+1))
    echo "  + $scene/$(basename "$f")"
  done
done
echo "integrated $count track(s) into $DST"
echo "now: ./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk"
