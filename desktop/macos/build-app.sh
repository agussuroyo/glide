#!/bin/sh
# Builds dist/Glide.app and dist/Glide.dmg, ad-hoc signed (no Apple Developer ID needed).
set -eu
cd "$(dirname "$0")/.."

cargo build --release

APP=dist/Glide.app
rm -rf dist
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp target/release/glide "$APP/Contents/MacOS/glide"
cp macos/Info.plist "$APP/Contents/Info.plist"

# App icon from the SVG, at every size macOS asks for.
WORK=$(mktemp -d)
ICONSET="$WORK/AppIcon.iconset"
mkdir "$ICONSET"
sips -s format png macos/icon.svg --out "$WORK/icon.png" >/dev/null
for s in 16 32 128 256 512; do
    sips -z $s $s "$WORK/icon.png" --out "$ICONSET/icon_${s}x${s}.png" >/dev/null
    sips -z $((s * 2)) $((s * 2)) "$WORK/icon.png" --out "$ICONSET/icon_${s}x${s}@2x.png" >/dev/null
done
iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/AppIcon.icns"

codesign --force --sign - "$APP"

# Disk image: the app, a shortcut to Applications, and what to do when macOS blocks an unnotarized app.
STAGE="$WORK/dmg"
mkdir "$STAGE"
cp -R "$APP" "$STAGE/"
ln -s /Applications "$STAGE/Applications"
cat > "$STAGE/If macOS blocks Glide.txt" <<'TXT'
Glide is not notarized by Apple, so the first time you open it macOS will refuse.

1. Drag Glide into Applications and open it once.
2. Open System Settings > Privacy & Security, scroll down and click "Open Anyway".
   (Or in Terminal: xattr -dr com.apple.quarantine /Applications/Glide.app)
3. Glide appears in the menu bar. Turn it on in Accessibility and Input Monitoring when asked.

After updating Glide, macOS may ask for Accessibility and Input Monitoring again.
TXT
hdiutil create -volname Glide -srcfolder "$STAGE" -ov -format UDZO dist/Glide.dmg >/dev/null
rm -rf "$WORK"
echo "Built dist/Glide.app and dist/Glide.dmg"
