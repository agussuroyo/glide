# Glide — Plan

Status 2026-10-29: Mac → Android selesai (input, TLS + pairing, mDNS, heartbeat, clipboard, transfer file dua arah, atur posisi HP seperti panel Displays). Berikutnya: Mac → Mac target, DMG.

Geser kursor ke tepi layar → kontrol mouse + keyboard pindah ke perangkat lain di WiFi yang sama. Tanpa kabel, tanpa hardware tambahan.

MVP: Mac → Android, Mac → Mac. Windows/Linux menyusul (desktop app sudah Rust, tinggal tambah backend input per OS).

iPhone: tidak didukung. iOS tidak mengizinkan app menyuntikkan input sistem.

## Arsitektur

```
Host (punya mouse/keyboard)                Target
┌──────────────────────────┐   TCP+TLS    ┌───────────────────────────┐
│ capture: CGEventTap      │ ───────────▶ │ desktop: CGEventPost       │
│ edge detect → grab input │   LAN, mDNS  │ android: AccessibilityService│
│ kirim delta relatif      │ ◀─────────── │   + IME untuk keyboard     │
└──────────────────────────┘   Leave(y)   └───────────────────────────┘
```

- Satu binary desktop `glide` (Rust) bisa jadi host maupun target.
- Host kirim **delta relatif**. Target pegang posisi kursornya sendiri. Kalau kursor target nabrak tepi yang mengarah balik ke host, target kirim `Leave{edge, pos}` dan kontrol kembali ke host. Protokolnya sama untuk Mac dan Android.

### Protokol (TCP, frame length-prefixed, `TCP_NODELAY`)

| Pesan | Arah | Isi |
|---|---|---|
| `Hello` | dua arah | versi, nama, OS, ukuran layar |
| `Enter` | host→target | edge masuk, posisi 0..1 sepanjang edge |
| `Move` | host→target | dx, dy (i16) |
| `Button` | host→target | tombol, down/up |
| `Scroll` | host→target | dx, dy |
| `Key` | host→target | keycode (USB HID usage), modifier, down/up |
| `Leave` | target→host | edge keluar, posisi 0..1 |
| `Clipboard` | dua arah | teks (fase 5) |

Keycode dikirim sebagai USB HID usage supaya netral OS. Tiap target memetakannya ke keycode lokal.

### Keamanan (wajib, karena app ini setara keylogger + remote control)

- TLS (rustls / Android `SSLSocket`), sertifikat self-signed per perangkat.
- Pairing pakai numeric comparison: kedua layar menampilkan 6 digit dari hash kedua fingerprint, user konfirmasi sama. Fingerprint lalu di-pin.
- Koneksi dari perangkat yang belum dipairing ditolak.

### Mac (host + target)

- Capture: `CGEventTap` untuk mouse + keyboard. Saat kursor kena edge yang dikonfigurasi: `CGDisplayHideCursor`, `CGAssociateMouseAndMouseCursorPosition(false)`, event di-swallow (callback return NULL), delta dikirim.
- Inject: `CGEventPost`.
- Izin yang dibutuhkan: Accessibility + Input Monitoring.

### Android (target saja)

- `AccessibilityService`:
  - overlay kursor (`TYPE_ACCESSIBILITY_OVERLAY`)
  - klik, drag, scroll lewat `dispatchGesture` (MVP: satu gesture diputar ulang saat tombol dilepas; `continueStroke` untuk drag live menyusul)
  - aksi global back/home/recents via `performGlobalAction`
- `InputMethodService` (keyboard "Glide"): `commitText` + `sendKeyEvent`. AccessibilityService tidak bisa menyuntikkan ketikan.
- Discovery: `NsdManager`. Min SDK 30 (Android 11+).

### Discovery & layout

- mDNS `_glide._tcp` (crate `mdns-sd` di Rust, `NsdManager` di Android).
- MVP: layout di `~/.config/glide/config.toml`, misalnya `right = "Pixel"`. UI drag-drop untuk mengatur layout menyusul.

## Struktur repo

```
desktop/   Rust: main.rs, proto.rs, net.rs, mac.rs
android/   Kotlin: GlideService (accessibility), GlideIme, Net, Proto
```

## Fase (masing-masing harus terverifikasi sebelum lanjut)

0. **Setup**: rustup, JDK 17, `git init`.
1. **Protokol + Mac→Mac tanpa TLS**: encode/decode + unit test round-trip. Host capture + target inject.
   - Cek: kursor menyeberang ke Mac kedua dan kembali, klik + ketik jalan.
2. **Android target**: kursor overlay, tap, drag, scroll, back/home, keyboard via IME.
   - Cek di HP: buka app, scroll list, ketik di search bar, lalu balik ke Mac.
3. **TLS + pairing**.
   - Cek: perangkat yang belum dipairing ditolak, pairing ulang jalan.
4. **mDNS discovery**: tidak perlu ketik IP lagi.
5. **Clipboard teks** dua arah.

Belakangan, kalau dibutuhkan: Windows (`SendInput` / `SetWindowsHookEx`), Linux (X11 `XTest`; Wayland lewat `libei`, terbatas), UI layout, clipboard gambar.

## Distribusi (supaya bisa dibagikan)

- Mac: `Glide.dmg` dibuat dengan `hdiutil`, app di-sign ad-hoc (`codesign -s -`), tanpa Apple Developer ID.
  - Pertama kali dibuka akan diblokir Gatekeeper. User buka System Settings → Privacy & Security → **Open Anyway**, atau jalankan `xattr -dr com.apple.quarantine /Applications/Glide.app`.
  - Signature ad-hoc berubah tiap build, jadi setelah update izin Accessibility + Input Monitoring harus diberikan ulang.
- Android: APK dari GitHub Releases / F-Droid. Play Store kemungkinan menolak karena policy AccessibilityService untuk non-aksesibilitas.

## Risiko yang sudah diketahui

- Android: gesture hasil `dispatchGesture` sedikit lebih lambat dari sentuhan asli (~puluhan ms). Hover dan klik kanan tidak ada padanannya; klik kanan dipetakan ke back.
- User Android harus mengaktifkan 2 hal secara manual: Accessibility service + keyboard Glide.
- Wayland membatasi capture/inject global.
