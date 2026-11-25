# LE Sales — iOS build (Mac + Xcode)

The iOS project is in `ios/App` (Capacitor 8, Swift Package Manager, bundle id **in.lalitenterprises.sales**,
iOS 15+). It loads the same live web app (`https://ankitcoolbindas-art.github.io/le-sales-app/`) and uses
`@capacitor-community/background-geolocation` for all-day location after the salesperson taps **Start day**.
It cannot be built on Linux — you need a Mac.

## Already configured
- `Info.plist`: `NSLocationWhenInUseUsageDescription`, `NSLocationAlwaysAndWhenInUseUsageDescription`,
  `NSLocationAlwaysUsageDescription` (English + Hindi), `UIBackgroundModes = [location]`, portrait only,
  `ITSAppUsesNonExemptEncryption = false` (skips the export-compliance question in TestFlight).
- App icon + splash (amber bolt on dark) in `ios/App/App/Assets.xcassets`.
- Plugin: `ios/App/CapApp-SPM/Package.swift` includes `CapacitorCommunityBackgroundGeolocation`.
- JS side: `js/native.js` in the web app repo (iOS engine: background watcher + native HTTP writes to Firestore).

## What you need
1. A Mac with **Xcode 16+** (App Store) and **Node 20+** (`brew install node`).
2. An **Apple Developer Program** membership (₹8,700 / US$99 per year) — https://developer.apple.com/programs/
   Enrol as an organisation (needs a D-U-N-S number for Lalit Enterprises) or as an individual (Ankit Kumar Mahto).
   *Without it you can only run on your own iPhone for 7 days at a time.*

## Build steps
```bash
git clone https://github.com/ankitcoolbindas-art/le-sales-android.git
cd le-sales-android
npm install
npx cap sync ios
npx cap open ios          # opens ios/App/App.xcodeproj in Xcode
```
In Xcode:
1. Select the **App** target → **Signing & Capabilities** → tick *Automatically manage signing* → choose your
   Team. Bundle identifier must stay `in.lalitenterprises.sales`.
2. Still in Signing & Capabilities, confirm **Background Modes → Location updates** is ticked
   (it comes from Info.plist; if the capability is missing, click **+ Capability → Background Modes**).
3. Pick a connected iPhone (or "Any iOS Device (arm64)") and press **▶ Run** to test on a device.
4. For distribution: **Product → Archive** → in the Organizer click **Distribute App → App Store Connect → Upload**.

Bump the version for each upload: target → General → *Version* (e.g. 1.0.1) and *Build* (1, 2, 3 …).

## TestFlight (recommended way to give the app to salespeople)
1. https://appstoreconnect.apple.com → **Apps → +** → New App: platform iOS, name "LE Sales" (or
   "LE Sales – Lalit Enterprises" if taken), bundle id `in.lalitenterprises.sales`, SKU `le-sales`.
2. After the archive upload finishes processing (10–30 min) it appears under **TestFlight**.
3. **Internal testing** (up to 100 people who are users of your App Store Connect team — no Apple review):
   add each salesperson under *Users and Access* first, then add them to an internal group.
   **External testing** (up to 10,000 by email or public link): needs a short Beta App Review (usually < 1 day).
   Fill *Test Information* (what to test, contact email, and a demo login — create a salesperson account in the
   LE Sales admin for Apple's reviewer).
4. Each salesperson installs **TestFlight** from the App Store, opens the invite, taps **Install**.
   TestFlight builds expire after 90 days — upload a new build before then.

For a permanent install, submit to the App Store as an *Unlisted* app, or use Apple Business Manager
custom apps. Apple reviews background location carefully: keep the Start day / End day controls and the
location purpose strings; explain in review notes that it is an internal sales-team app.

## Salesperson setup on iPhone
1. Open LE Sales → sign in → tap **▶ Start day** → allow location **While Using the App**.
2. When iOS later asks, choose **Change to Always Allow** (or Settings → LE Sales → Location → **Always**,
   and keep **Precise Location** ON).
3. Settings → General → **Background App Refresh** → ON for LE Sales. Do not use Low Power Mode during work.
4. A blue location indicator appears in the status bar while sharing. Tap **End day** when finished
   (auto-stops at 9 PM).

## iOS limitations
- If the salesperson force-quits the app (swipes it away), iOS stops background location until it is opened
  again. Tell them to leave it in the background.
- iOS sends points from JavaScript; this is reliable in the background with the location background mode,
  but is untested on a real iPhone so far — test with one person for a day before rolling out.
- Firestore rules accept `source: 'web' | 'android'`, so iPhone points are written as `source: 'android'`
  with `deviceId: 'iPhone'` and `appVersion: 'ios-…'`. To label them `ios`, add `'ios'` to the `source in [...]`
  list in `firestore.rules` and change `source` in `js/native.js`.
