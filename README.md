# LE Sales — Android (and iOS) app for Lalit Enterprises

Native wrapper (Capacitor 8) around the LE Sales web app
(https://ankitcoolbindas-art.github.io/le-sales-app/, source: `ankitcoolbindas-art/le-sales-app`)
that adds **all-day background location sharing** for salespeople.

- Package / bundle id: `in.lalitenterprises.sales` · App name: **LE Sales**
- Loads the live web app (`server.url`), so web updates reach the app without reinstalling.
  `www/offline.html` is shown if the first load has no internet.
- **Android tracking** = native foreground service `TrackingService` (FusedLocationProvider, fix every ~60 s,
  sends every ~3 min or after ≥100 m movement) that writes `locations/{id}` + `lastLocation/{uid}` to Firestore
  over REST using the salesperson's Firebase refresh token → ID token. Persistent notification
  "Lalit Enterprises — location sharing on". Keeps running with the screen off or the app swiped away;
  offline points are queued (up to 1,500) and sent later.
- Runs only after **Start day** while signed in; stops on **End day**, sign-out, account deactivation,
  at **9 PM**, or at midnight.
- JS bridge: `web-hook/native.js` (deployed in the web repo as `js/native.js`) — Start/End day pill,
  permission checklist (English + Hindi), replaces the web app's open-app-only tracker inside the app.
- iOS: see [IOS_BUILD.md](IOS_BUILD.md).

## Build the APK (Linux box)
```bash
scripts/build-apk.sh     # → /workspace/le-sales-android-out/le-sales.apk + le-sales-play-v1.aab
```
Outputs a sideload APK and a Play AAB (see PLAY_RELEASE.md). Needs JDK 21, Android SDK (platform 36, build-tools 36) at `$ANDROID_HOME`, and the signing key at
`/workspace/le-sales-android-keys/keystore.properties` (NOT in this repo — back it up; every update must be
signed with the same key). Bump `versionCode`/`versionName` in `android/app/build.gradle` for each release.


## v1.1.0 (versionCode 2)
- **Camera:** `CAMERA` permission (camera hardware optional) → the attendance selfie uses the in-app live preview (Capacitor's WebChromeClient asks for the runtime permission). No "phone camera app" fallback in 1.1; if permission is refused the app shows *Open settings / Try again*.
- **Fake GPS block** (`MockCheck.java`): a fix is mock if `Location.isMock()` (API 31+) / `isFromMockProvider()`; also flags known fake-GPS apps installed (`<queries>` list, no `QUERY_ALL_PACKAGES`), apps currently granted the *mock location* app-op, and legacy `Settings.Secure.ALLOW_MOCK_LOCATION`.
  - Plugin methods: `mockStatus()`, `currentFix({timeoutMs,maxAgeMs})` (fused high-accuracy fix + mock info), `cameraStatus()`.
  - Attendance check-in/out is refused with "Fake GPS detected — turn off mock location app"; the attempt is saved as a `locations` point with `mock:true`, `reason:'checkin-blocked'|'checkout-blocked'`, `mockApp`.
  - `TrackingService` keeps writing points but adds `mock:true` (and sends immediately when mock starts); status exposes `mockAt` so the pill warns the salesperson.
  - Web code detects the app version (`LENative.status().appVersion >= 1.1.0`) so v1.0 installs keep working the old way.
- **Limits:** rooted phones with hide modules (Magisk/LSPosed) can strip the mock flag; unknown fake-GPS apps are only caught by the mock flag / app-op; web & iPhone cannot detect mock (they only get weak/old-GPS flags).
