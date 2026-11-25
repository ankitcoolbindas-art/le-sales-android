# LE Sales — Google Play release (internal testing)

Play developer account: **karidare.in@gmail.com** · Package: **in.lalitenterprises.sales** · versionCode 2 / versionName 1.1.0 (v1.0.0 = versionCode 1)

## Files
| What | Path on the build box |
|---|---|
| Signed Play bundle (upload this) | `/workspace/le-sales-android-out/le-sales-play-v1.1.aab` (v1.1.0, code 2; older: `le-sales-play-v1.aab`) |
| Sideload APK (direct install, not for Play) | `/workspace/le-sales-android-out/le-sales.apk` |
| Upload keystore + passwords (never commit) | `/workspace/le-sales-android-keys/` (`le-sales-release.jks`, `keystore.properties`) |

The AAB is the `play` flavor: identical app, but without `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
(a Play-restricted permission); the in-app "Battery: unrestricted" step opens the system battery list instead.
Privacy policy URL: **https://ankitcoolbindas-art.github.io/le-sales-app/privacy.html**

> Play App Signing re-signs the app with a Google key, so a Play-installed copy and a sideloaded APK cannot
> update each other. Pick one channel per phone (uninstall the sideloaded app before installing from Play).

## 1. Create the app
Play Console → **Create app** → App name "LE Sales – Lalit Enterprises" · Default language English (India) ·
App · Free · accept declarations.

## 2. Internal testing release
1. **Test and release → Testing → Internal testing → Create new release**.
2. App signing: choose **Use Google-generated key** (recommended). Our keystore becomes the *upload key*.
3. Upload `le-sales-play-v1.aab`. Release name `1.0.0 (1)`. Notes: "First internal release: all-day location sharing (Start day / End day)."
4. **Testers** tab → create an email list (salespeople's Gmail addresses) → save → copy the **opt-in link** and send it to them.
   Internal testing has no full review and allows up to 100 testers.

## 3. App content (Policy → App content) — required even for internal testing of location apps
- **Privacy policy:** URL above.
- **App access:** "All or some functionality is restricted" → add a demo login (create a salesperson account in
  LE Sales admin, e.g. `playreview@…` with a password) and instructions: "Sign in → tap Start day → allow location → Allow all the time."
- **Ads:** No ads.
- **Content rating:** questionnaire → Utility/Productivity, no objectionable content.
- **Target audience:** 18+ only. Not designed for children.
- **Data safety:**
  - Collects **Location → Precise location** and **Approximate location**: collected, not shared; purposes *App functionality* and *Analytics* (team visit tracking); required (not optional) for salespeople.
  - **Personal info → Name, Email address, Phone number**: collected, not shared; *Account management, App functionality*.
  - **App info and performance → Other (battery level, device model)**: collected, not shared; *App functionality*.
  - Data encrypted in transit: **Yes**. Users can request deletion: **Yes** (by contacting +91 70042 21338).
- **Government apps / Financial features / Health:** No.

### Location permissions declaration (Policy → App content → Sensitive permissions → Location permissions)
Background location (ACCESS_BACKGROUND_LOCATION) needs this declaration **and a video** before any track, including internal testing, can be published.

**Core feature that uses background location** (paste):
> LE Sales is an internal sales-force app used only by employees of Lalit Enterprises, an electrical distributor in Ranchi, India. After a salesperson taps "Start day", the app shares their location with the company every few minutes (and when they move about 100 m) so the owner can see the field team's live position and daily route on a map, plan customer visits, and verify visits. Salespeople keep the phone in their pocket with the screen off while travelling between customers, so location must be collected while the app is in the background. Tracking stops when they tap "End day", sign out, or automatically at 9 PM. A persistent notification "Lalit Enterprises — location sharing on" is shown the whole time.

**Prominent disclosure** (shown in-app before the location permission request, English + Hindi):
"LE Sales collects your location to share it with Lalit Enterprises during your work day — every few minutes and when you move,
even when the app is closed or not in use — until you tap End day (auto-stops at 9 PM)." The Start day sheet repeats this.
(Text lives in `js/native.js` of the web app, so it can be changed without a new build.)

**Foreground service type declaration** (Policy → App content → Foreground service permissions): type **Location** —
"Shares the salesperson's location with their employer during the work day after they tap Start day; user-initiated, shows an ongoing notification, stops on End day / 9 PM."

**Demo video** (required): 30–60 s screen recording on a real Android phone, uploaded to YouTube (unlisted) — link in the form. Show, in order:
1. Opening LE Sales and signing in as the demo salesperson.
2. The Start day sheet text (prominent disclosure) and tapping **Start day**.
3. The Android permission dialogs: location **While using the app**, then settings → **Allow all the time**; notifications.
4. The persistent notification "Lalit Enterprises — location sharing on".
5. Pressing Home / locking the screen, then the owner's live map (web admin) showing the new position.
6. Tapping **End day** and the notification disappearing.

## 4. Later: production / closed testing
For the public store you also need a store listing (short + full description, 512×512 icon, 1024×500 feature graphic,
2+ phone screenshots) and, for new personal developer accounts, 12+ testers in closed testing for 14 days.
Being an internal company app, internal testing (or an organisation account with *private app* via Managed Google Play) is usually enough.

## Updating
1. Bump `versionCode` (+1) and `versionName` in `android/app/build.gradle`.
2. `scripts/build-apk.sh` → upload the new `le-sales-play-vN.aab` to the same track.
Most changes (screens, texts) are in the web app and reach phones without a new build.


## v1.1.0 (versionCode 2) — upload `le-sales-play-v1.1.aab`
Release notes: "In-app selfie camera for attendance; blocks check-in when a fake-GPS / mock-location app is used."
Play Console changes for this version:
- **Data safety:** add *Photos* (collected, not shared, purpose: App functionality — attendance selfie) alongside Location.
- **Camera** permission: used only for the attendance selfie (in-app live preview).
- Fake-GPS check uses a `<queries>` list of known mock-location package names (no `QUERY_ALL_PACKAGES`), so no extra declaration is needed.
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` stays removed in the `play` flavor (verified absent in the bundle).
