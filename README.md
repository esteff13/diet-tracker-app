# Diet Tracker app

Public part of the Diet Tracker: the Android app shell and the launcher page. No client data and no app logic live here.

- **Launcher page** (GitHub Pages): `https://esteff13.github.io/diet-tracker-app/?id=CODE`
  - Android: "Open my tracker" opens the app with the client's code (downloads the APK first if it's missing).
  - iPhone / computer: opens the tracker full screen, with a tip to Add to Home Screen.
- **Android app** (`android/`): frames the web app, camera + gallery for photos, and an "Update ready" popup.
- **Builds**: every push to `android/` builds `DietTracker.apk` and publishes it under Releases.

## One-time: permanent signing key
Updates only install over the old app when every build uses the same key.
Add a repo secret **SIGNING_SEED** (Settings → Secrets and variables → Actions → New repository secret)
with any long random phrase (20+ characters). Keep it somewhere safe; never change it.
Builds without it are marked TEST and never trigger the update popup.
