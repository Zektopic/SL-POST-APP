# Releasing

Tagged builds (`v*`) publish a **signed release APK** to GitHub Releases.
Debug APKs are CI artifacts only and are never published.

## One-time setup

### 1. Generate a release keystore

Do this on a machine you control, and keep the file out of the repository —
`.gitignore` already excludes `*.jks` and `*.keystore`, but the keystore should
not live in the working tree at all.

```bash
keytool -genkeypair -v \
  -keystore release.jks \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -alias slpoststamps
```

Back the file up somewhere durable. If it is lost, no future build can update
an already-installed app — every user has to uninstall and reinstall.

### 2. Add the repository secrets

Settings → Secrets and variables → Actions:

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | `base64 -w0 release.jks` (on macOS: `base64 -i release.jks`) |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | `slpoststamps` |
| `KEY_PASSWORD` | key password |

The release job fails with a clear error if `KEYSTORE_BASE64` is missing,
rather than quietly producing an unsigned APK.

## Cutting a release

```bash
git tag v1.1.0
git push origin v1.1.0
```

The workflow runs tests and lint, builds `assembleRelease`, and attaches
`app-release.apk` to the release. `versionCode` comes from the workflow run
number so it always increases; `versionName` comes from the tag.

## Building a signed APK locally

```bash
KEYSTORE_FILE=/path/to/release.jks \
KEYSTORE_PASSWORD=… KEY_ALIAS=… KEY_PASSWORD=… \
./gradlew assembleRelease
```

Without `KEYSTORE_FILE` the release variant is built unsigned and cannot be
installed — that is intentional, so a dev machine cannot accidentally produce
something that looks distributable.
