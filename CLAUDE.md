# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Glide: move a Mac's cursor/keyboard onto an Android phone by pushing the cursor past the screen edge, over WiFi. Two apps share one hand-written protocol: `desktop/` (Rust, macOS host) and `android/` (Kotlin, phone target). See README.md for user-facing behaviour, PLAN.md for roadmap.

## Commands

Toolchains on this machine are not on the default PATH:

```
export PATH="/opt/homebrew/opt/rustup/bin:$HOME/Library/Android/sdk/platform-tools:$PATH"
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
```

Desktop (run in `desktop/`):

```
cargo build --release                 # target/release/glide
./macos/build-app.sh                  # dist/Glide.app + dist/Glide.dmg (ad-hoc signed menu bar app)
cargo test                            # unit tests
cargo test crossing_follows           # single test by name
GLIDE_E2E_ADDR=127.0.0.1:24242 GLIDE_E2E_FILES=/path/a,/path/dir \
  cargo test --release e2e_push -- --ignored --nocapture   # real push to a paired phone
./target/release/glide [name|ip|ip:port]                   # run host
```

Android (run in `android/`):

```
./gradlew assembleDebug               # app/build/outputs/apk/debug/app-debug.apk
./gradlew bundleRelease               # signed AAB for Play (key in ~/.config/glide/release.properties); see android/PLAY.md
./gradlew lintDebug                   # must stay at 0 errors
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell settings put secure enabled_accessibility_services io.github.agussuroyo.glide/dev.glide.GlideService
adb forward tcp:24242 tcp:24242       # then: glide 127.0.0.1:24242 to drive the emulator
adb logcat -s GlideCursor
```

There are no Android unit tests; Android behaviour is verified on the `bro-test` emulator (`emulator -avd bro-test`).

## Architecture

**Protocol** (`desktop/src/proto.rs` ⇄ `android/.../Proto.kt`): frames are `[u16 len][u8 type][payload]`, big-endian. Both files must change together, and `VERSION` must be bumped on both sides when semantics change. Mac points and Android dp are treated as the same unit (Hello sizes, Layout offsets, cursor deltas).

**Connections**: the phone is the TLS 1.3 server on port 24242 (advertised via mDNS `_glide._tcp` with TXT `id` = fingerprint prefix); the Mac always dials. The first frame decides what a connection is:
- `Hello` → input session. If the Mac doesn't know the phone it sends `PairStart` first. The phone answers either `PairProof` = HMAC-SHA256(QR secret, host cert ‖ phone cert) if it scanned this Mac's QR (`pairing.rs` / `Pairing.proof`, vector `e66dae59…`), or `PairAsk`, after which both sides show a SAS code (`sas()` / `Pairing.sas`, vector `348 117`). Fingerprints are then pinned (`~/.config/glide/known` on Mac, SharedPreferences on phone). After Hello the phone always sends `Layout`.
- The Mac only dials an unknown phone while a pairing window is open (`pairing::active()`), or when given an explicit address. A phone that scanned a QR advertises TXT `pair=<mac fp prefix>` so the right Mac picks it.
- `Push` / `Pull` → a file-transfer connection, only from an already-paired fingerprint. Raw file bytes follow each `FileHeader` outside framing; receiver ends with `FileDone`. Transfers never share the input connection so big files can't stall the cursor.

**Control handoff**: the Mac sends relative `Move` deltas; the phone owns its cursor position. Crossing happens only within the stretch of the Mac edge the phone covers (`Layout`, set by dragging in the phone's `DeskView`); `to_phone`/`to_mac` in `mac.rs` map positions 1:1. The phone hands back with `Leave` when pushed past its entry edge; the Mac can force it back (Left Ctrl+Option+Cmd) and then sends `Leave` to the phone. Both sides Ping every 1s and drop after 4s of silence.

**Mac host**:
- `mac.rs` holds all input state in one `Mutex<Host>` touched by the CGEventTap callback (main run loop) and the network threads.
- Events Glide posts itself carry `OWN_EVENT` in `EVENT_SOURCE_USER_DATA` so the tap passes them through. File drag-across uses this: it posts Esc to cancel the Mac drag, then pushes on mouse-up.
- `net.rs` splits the rustls connection: the reader thread and writer thread share `Mutex<ClientConnection>` over cloned `TcpStream`s.
- `clip.rs` uses NSPasteboard via objc2, both the general and the drag pasteboard.
- One binary, two modes. Inside `Glide.app` (`app::in_bundle()`), `app.rs` runs an NSApplication menu bar item. A single main-thread NSTimer does all UI work: status text, the pairing NSAlert, Open at Login state, and retrying the event tap until permissions are granted. Network threads only write to a mutex. From a terminal, `mac::run` just runs the CFRunLoop and pairing asks on stdin.

**Android target** (everything hangs off `GlideService`, an AccessibilityService):
- Network messages are merged in an inbox (consecutive Move/Scroll summed) and drained on the main thread.
- `Finger` turns clicks/drags/scroll into live gestures by chaining `continueStroke`; only one gesture in flight.
- `Keyboard` types through `AccessibilityService.getInputMethod()` (API 33+), so no IME switch is needed. Mac habits are mapped: Cmd→Ctrl shortcuts, Option→word moves.
- `CursorLayer` is a small overlay window moved with `updateViewLayout` once per vsync, never redrawn. This replaced a full-screen redraw that used about 70% CPU.
- Overlay windows from a service need `FLAG_HARDWARE_ACCELERATED` explicitly.
- `Glide.State` is the single observable state, used by MainActivity, ShareActivity and the overlays. `Store` persists paired Macs, arrangement per Mac, and pointer/scroll speed.
- UI is plain platform Views built in code (no AndroidX, no XML layouts), using the bundled Instrument Sans font. Shared helpers are in `Ui.kt`.

## Gotchas

- Android unbinds/rebinds accessibility services whenever accessibility settings change (including running `uiautomator dump`). `GlideService` must recreate its executor, server socket and overlays in `onServiceConnected`, and must not assume a single bind.
- Emulator frame timing is dominated by GPU emulation; judge rendering cost by CPU and frames rendered, not jank %.
- Fake hosts for testing need TLS 1.3 (system Python's LibreSSL lacks it; use `uv run --python 3.12`) and named-curve EC certs (BoringSSL rejects explicit curve params).
- Android forbids background clipboard reads: phone→Mac clipboard only works via Cmd+C/X in a text field (selection read from the input connection) or Share.
- Banking apps may flag any sideloaded accessibility service; don't enable ADB/developer options on the user's own phone.
- Play Protect blocks installing any APK that declares an Accessibility service from a browser/chat/file manager, so the user installs via Play internal testing. Play policy also requires the in-app disclosure (`Disclosure.kt`) before sending the user to Accessibility settings.
- App ID is `io.github.agussuroyo.glide`; the Kotlin namespace stays `dev.glide`. Apps cannot open the per-app Accessibility page (system-only permission), so use `accessibilitySettingsIntent`.
