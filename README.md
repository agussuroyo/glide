# Glide

Move your Mac's cursor and keyboard onto an Android phone by pushing the cursor off the screen edge. Over WiFi, no cable, no extra hardware. Files and clipboard come along.

## Install

**Phone** (Android 13 or newer): install `glide.apk`, open Glide, tap **Open Accessibility settings** and turn Glide on.
If the switch is greyed out: App info → ⋮ → **Allow restricted settings**, then try again.

**Mac** (macOS 13 or newer): open `Glide.dmg`, drag Glide into Applications and open it. It lives in the menu bar.
Glide is not notarized, so the first time macOS blocks it: System Settings → Privacy & Security → **Open Anyway**.
Then turn Glide on in **Accessibility** and **Input Monitoring** when asked; it starts working as soon as you do, no restart.
The first time a phone connects, a dialog shows a 6-digit code; tap Pair only if the phone shows the same code, then Pair on the phone.

Drag the phone in the Glide app to where it sits next to your Mac, like arranging displays in System Settings. The cursor crosses only where the two screens touch, at the same height it left.

The menu bar shows who you're connected to and has **Open at Login**. Prefer a terminal? `glide` (or `glide pixel`, `glide 192.168.1.20`) runs the same thing there.

## Use

| On the Mac | On the phone |
|---|---|
| Push the cursor off the edge | Cursor appears on the phone |
| Click, drag, scroll | Tap, drag, scroll (long-press works) |
| Type, Cmd C / X / V / A, Option ⌫ | Same, in any text field; your normal phone keyboard stays |
| Esc or right click | Back |
| Cmd H, Cmd Tab, Cmd N, Ctrl Cmd Q | Home, recent apps, notifications, lock |
| Left Ctrl + Option + Cmd | Cursor comes back to the Mac |

**Files, Mac → phone**: drag files or folders past the edge and let go on the phone, or copy them in Finder and press Cmd V while the cursor is on the phone. They land in Downloads › Glide.
**Files, phone → Mac**: Share → Glide in any app. They land in ~/Downloads. Shared text goes to the Mac clipboard.
Transfers stream on their own connection with a fixed buffer, so multi-GB files don't slow the cursor or use more memory.

## Security

Every connection is TLS 1.3. Devices trust each other only after you compare the pairing code on both screens; after that the phone's and Mac's keys are pinned. Anything else on the network is refused. Paired Macs can be forgotten in the app.

## Limits

- iPhone is not supported: iOS does not let apps control the screen.
- Android blocks apps from reading the clipboard in the background, so phone → Mac copy works with Cmd C in a text field or Share, not with copies made by finger.
- Files dropped on the phone are saved to Downloads; Android does not let one app drop files into another.
- Banking apps may refuse to run while any non-Play-Store accessibility service is on. Turn Glide off in Accessibility before banking.

## Build

```
cd desktop && cargo build --release          # target/release/glide (terminal)
cd desktop && ./macos/build-app.sh           # dist/Glide.app and dist/Glide.dmg
cd android && ./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```
