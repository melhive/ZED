# Building ZENITH

ZENITH is built remotely by **GitHub Actions** — no Android Studio or local Android SDK is needed.

## Install or update the app (no GitHub account needed)

1. On your Android phone open **https://github.com/melhive/ZENITH/releases/latest/download/zenith.apk**
2. Open the downloaded `zenith.apk` and tap **Install**. The first time, Android asks you to allow installing apps from your browser — allow it.
3. To update later, open the same link again and tap **Install** — it replaces the old version and keeps your data. ZENITH shows an **Update available** card on the Dashboard when a newer release exists (Settings → App updates → Check now).

Share the same link with anyone. The releases page lists every version with its changes: https://github.com/melhive/ZENITH/releases

### How releases are made

- Every push to `main` runs `.github/workflows/release.yml`: tests → signed release APK → GitHub Release `v0.1.<N>` with `zenith.apk` attached and marked **Latest**.
- `N` is the workflow run number and is used as the `versionCode`, so each release is higher than the last.
- All releases are signed with the same key, which is what lets Android install them as updates.
- Work happens on `develop`; merging to `main` publishes.

### Debug builds (for development)

Each push to `develop` also builds a debug APK (Actions → Android Build → artifact `zenith-debug-apk`). It installs as a separate app (`com.melhive.zenith.debug`) and is signed with a throwaway key, so it cannot update itself — use the release link above for normal use.

## Trigger a build

- Push to `develop` or `main`, open a pull request, or
- Actions → Android Build → **Run workflow** (manual dispatch).

The workflow runs core tests, Android unit tests (Robolectric + MockWebServer), builds the debug APK, uploads it and the test reports.

## Run core tests on the phone (Termux/Ubuntu)

The pure-Kotlin `:core` module needs only a JDK:

```bash
apt install openjdk-17-jdk-headless   # once
./scripts/test-core.sh
```

The script copies the project to `~/zenith-build` (Android shared storage does not support Gradle's file locks) and runs `:core:test` with `-Pzenith.coreOnly=true`, which skips the Android module.

## Release signing

The release key was generated once and is stored as repository secrets. **Keep a private backup of the keystore and its password** — if it is lost, installed copies can no longer be updated (users would have to uninstall and reinstall, losing data).

To create a new key (only if starting over):

```bash
keytool -genkeypair -v -keystore zenith-release.jks -alias zenith -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 zenith-release.jks > keystore.b64
```

Add repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `ZENITH_KEYSTORE_BASE64` | contents of `keystore.b64` |
| `ZENITH_KEYSTORE_PASSWORD` | keystore password |
| `ZENITH_KEY_ALIAS` | `zenith` |
| `ZENITH_KEY_PASSWORD` | key password |

Never commit the keystore or passwords; `.gitignore` blocks `*.jks`, `*.keystore` and `keystore.properties`. Without the secrets the release job stops with an error instead of publishing an APK that could not update older installs.

## Toolchain

| Component | Version |
|---|---|
| Gradle | 8.14.3 (wrapper) |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.4.21 (Compose compiler plugin) |
| KSP | 2.3.13 |
| JDK | 17 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 |
| Compose BOM | 2026.06.01 |
| Room / WorkManager / DataStore | 2.8.4 / 2.11.0 / 1.1.7 |
| LiteRT-LM | 0.19.1 |

Versions were chosen for verified mutual compatibility (AAR metadata `minCompileSdk` / `minAndroidGradlePluginVersion` checked), not simply the newest.
