# Glide Privacy Policy

Last updated: 29 October 2026

Glide lets a Mac you own control your Android phone over your local WiFi, and move files and clipboard text between the two. This policy covers the Glide Android app and the Glide Mac app.

## What Glide does not do

- Glide has no servers, accounts, analytics, ads or tracking. The developer never receives any of your data.
- The Android app does not read, record or upload what is on your screen. Its Accessibility service is used to perform input (cursor, taps, swipes, scrolling, typing, Back/Home/Recents) that comes from your paired Mac.
- To protect your bank account, the service also notices the name of the app you tap or open, only to turn itself off before banking apps such as Livin' by Mandiri start (they block accounts while such services are on). This is checked on the phone as it happens; nothing about the apps you use is stored or sent anywhere. The only thing kept is the time and reason of the last automatic turn-off, shown in the app.

## Data that moves between your own devices

All of it travels directly between your phone and your paired Mac on your local network, encrypted with TLS 1.3, and only after you pair the two devices.

| Data | When | Where it goes |
|---|---|---|
| Mouse and keyboard input | While your Mac's cursor is on the phone | Mac → phone, applied immediately, not stored |
| Clipboard text | When the cursor moves to the phone (Mac's clipboard); when you press Cmd C or Cmd X in a text field on the phone, or share text to Glide | Between your Mac and phone only |
| Files | When you drag or paste files to the phone, or share files to Glide on the phone | Saved to Downloads › Glide on the phone, or Downloads on the Mac |
| Device name, screen size, and arrangement | When the devices connect | Between your Mac and phone only |

## Stored on your devices

- Pairing keys and the list of paired devices (Android keystore and app storage on the phone; `~/.config/glide` on the Mac).
- Your settings: pointer speed, scroll speed, and where the phone sits next to each Mac.

Uninstalling the app removes this data. You can also forget a paired Mac in the app.

## Permissions (Android)

- **Accessibility service**: to show the cursor and perform input from your paired Mac. Off until you turn it on; you can turn it off any time from the Glide tile in Quick Settings or in Accessibility settings.
- **Network**: to connect to your Mac on your local WiFi.
- **Wake lock**: to keep the phone awake while you control it or a file transfer is running.
- **Camera (through Google Play services)**: only when you tap Scan pairing code; Glide never gets the camera feed, only the scanned code.

## Contact

Questions: open an issue at https://github.com/agussuroyo/glide/issues
