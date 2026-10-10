# Esports Reward — poora source code (A to Z)

| Kya | Kahan hai |
|---|---|
| **App ka poora code** (UI, games, wallet, admin panel, cloud sync, ads bridge) | `app/src/main/assets/index.html` (single-file HTML/JS app) |
| **Android wrapper** (WebView, AdMob, consent) | `app/src/main/java/com/mobile/esports/MainActivity.java`, `app/src/main/AndroidManifest.xml`, `app/app-build.gradle`, `settings.gradle`, `build.gradle`, `gradlew`, `gradle/wrapper/` |
| **Signed APK + AAB build** | `.github/workflows/build-aab.yml` (Actions tab → Run workflow) |
| **Cloud backend** (progress sync, SMS-OTP restore, score validation) | `backend/` — `server.js`, `Dockerfile`, `.env.example`, `README.md` |
| **Terms & Privacy hosted page** | `privacy-terms-page.html` |

## Build chalane ke liye (Settings → Secrets and variables → Actions)
- **Secrets:** `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`
- **Variables:** `CLOUD_API_URL`, `ADMOB_APP_ID`, `ADMOB_REWARDED_ID` (test build ke liye `allow_test_config` ON)

Keystore (`*.jks`) kabhi is repo mein commit mat karna.
