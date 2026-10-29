#!/bin/sh
# Build the debug APK and serve it on the LAN so a phone can download it. Ctrl+C stops the server.
# No auth: anyone on the network can fetch the APK while this runs.
set -e
cd "$(dirname "$0")"
# Homebrew JDK is not on the default PATH here.
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"

./gradlew assembleDebug

ip=$(ipconfig getifaddr en0 || ipconfig getifaddr en1)
port=${PORT:-8000}
echo
echo "Open on the phone: http://$ip:$port/app-debug.apk"
echo "Or:                curl -O http://$ip:$port/app-debug.apk"
echo
exec python3 -m http.server "$port" --bind "$ip" --directory app/build/outputs/apk/debug
