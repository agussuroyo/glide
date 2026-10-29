# Releasing to Google Play (internal testing)

Internal testing is private: the app is not listed or searchable, only testers you add by email can install it.

## Before the first upload

1. **Back up the release key.** `~/.config/glide/release.keystore` and `~/.config/glide/release.properties` sign every update. Copy both somewhere safe (a password manager or encrypted drive). With Play App Signing this is your *upload* key: if it is lost, Play support can reset it, but that takes days and blocks updates meanwhile.
2. Create a Google Play developer account (one-time US$25, identity verification) at https://play.google.com/console.
3. The app ID is `io.github.agussuroyo.glide`. It can't be changed after the first upload.

## Build

```
cd android && ./gradlew bundleRelease     # app/build/outputs/bundle/release/app-release.aab
```

Bump `versionCode` (and `versionName`) in `app/build.gradle.kts` for every new upload.

## Play Console steps

1. **Create app**: name "Glide", app, free.
2. **Testing › Internal testing › Create new release**: accept Play App Signing, upload `app-release.aab`, add release notes.
3. **Testers**: create an email list with your own Google account, save, copy the opt-in link, open it on the phone, then install from Play.
4. **App content** (required before the release can go out):
   - Privacy policy URL: `https://github.com/agussuroyo/glide/blob/master/PRIVACY.md`
   - **Accessibility API declaration**: core feature is remote control of the phone from the user's own paired Mac (cursor, taps, scrolling, typing). Not an accessibility tool. Mention the in-app disclosure ("Before you turn on Glide"), that the service never reads window content (`canRetrieveWindowContent=false`), and that it uses click/window event package names only to turn itself off before banking apps open. A short screen recording of the disclosure → enabling the service → controlling the phone from the Mac is usually requested.
   - Data safety: no data is collected by the developer and nothing goes to any server; data moves only between the user's own devices, encrypted in transit. Answer the form from PRIVACY.md.
   - Content rating questionnaire, target audience (18+ is simplest), ads: none.
   - App access: explain that a Mac running Glide.app is needed, and that pairing uses the in-app QR scanner.

Internal testing releases are usually available within minutes; policy review of the Accessibility declaration can still take longer.
