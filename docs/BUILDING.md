# Building ZENITH

ZENITH is built remotely by **GitHub Actions** — no Android Studio or local Android SDK is needed.

## Get the APK on your phone

1. Open https://github.com/melhive/ZENITH/actions in your phone's browser (sign in to GitHub).
2. Tap the latest **Android Build** run with a green check on the `develop` branch.
3. Scroll to **Artifacts** and tap **zenith-debug-apk**. GitHub downloads a `.zip`.
4. Open the zip with your file manager and tap `app-debug.apk`.
5. Allow "Install unknown apps" for your browser/file manager when Android asks.
6. The debug build installs as **ZENITH** with package `com.melhive.zenith.debug`, so it can sit next to a future release build.

Tip: from Termux you can also run `gh run download --name zenith-debug-apk` in the project folder.

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

## Release builds

`.github/workflows/release.yml` (manual or on `v*` tags) builds a minified release APK and AAB.

To sign it, create a keystore once (on any machine with a JDK):

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

Never commit the keystore or passwords; `.gitignore` blocks `*.jks`, `*.keystore` and `keystore.properties`. Without secrets the release job still runs and produces an unsigned APK with a warning.

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
