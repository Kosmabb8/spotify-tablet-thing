# Third-party notices

This repository contains original application code and does not redistribute Spotify software, audio, credentials, or proprietary APIs. Documentation screenshots incidentally display artwork and Spotify-provided interface metadata selected by the project maintainer.

## Build and test dependencies

- **Android SDK and Android Gradle Plugin** — provided by Google under their respective Android SDK and open-source license terms. The SDK is downloaded by each developer and is not committed here.
- **Kotlin Gradle plugin and Kotlin standard library** — JetBrains, Apache License 2.0.
- **Gradle Wrapper** — Gradle, Inc., Apache License 2.0. The wrapper JAR and launcher scripts are included so builds use the declared Gradle version.
- **JUnit 4.13.2** — JUnit team, Eclipse Public License 1.0. Test-only.

Dependency coordinates and versions are declared in `build.gradle.kts`, `app/build.gradle.kts`, and `gradle/wrapper/gradle-wrapper.properties`.

## Optional lyrics service

The app can query [LRCLIB](https://lrclib.net/) for synchronized or plain lyrics. LRCLIB is an independent service; its server source is published under the MIT License. Lyrics remain disabled until the tablet administrator accepts the disclosure in the app.

When enabled, the app sends the current track name, artist, album and duration to LRCLIB over HTTPS. It does not send Spotify access/refresh tokens, Client ID, account name or device identifier.

## Services and trademarks

The application interoperates with the official Spotify Accounts and Web APIs under Spotify's developer terms. Spotify, Spotify Connect, Car Thing, Android, Lenovo, Windows, and related names are trademarks of their respective owners. Their use here identifies compatibility only and does not imply endorsement.

Interface screenshots are real captures supplied by the project maintainer. Spotify metadata and album/playlist artwork visible in those screenshots remain the property of their respective rights holders and are included only to document the application interface.
