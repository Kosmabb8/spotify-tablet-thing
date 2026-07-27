# Maintainer release guide

Release APKs must be signed with one stable private key. Never commit that key or its passwords.

## Local signed build

1. Create an Android signing keystore in a secure location outside this repository and back it up offline.
2. Run `scripts/build-release.ps1 -KeyStore C:\secure\controller.jks -KeyAlias controller`.
3. Enter passwords at the masked prompts.
4. Verify `app/build/outputs/apk/release/app-release.apk` and its checksum.

## GitHub Release workflow

The `android-release.yml` workflow requires these encrypted repository secrets:

- `ANDROID_KEYSTORE_BASE64`: base64 representation of the release keystore
- `CARTHING_STORE_PASSWORD`: keystore password
- `CARTHING_KEY_PASSWORD`: key password
- `CARTHING_KEY_ALIAS`: alias inside the keystore

Create the secrets in **Settings → Secrets and variables → Actions**. Values never belong in source, workflow YAML, issues, artifacts, or logs.

After the four secrets are configured, push a semantic-version tag such as `v1.0.0`. CI runs tests/lint, builds the signed minified APK, verifies its signature, creates a SHA-256 file, and attaches both to a GitHub Release.

If no long-term signing key is available, publish source code only. Do not publish an APK signed with a disposable key because users could not install future updates over it.
