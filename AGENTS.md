# StorePal Android

Public Kotlin SDK for https://storepal.app. This repository is the canonical Android SDK source.

- `storepal-core`: Kotlin/JVM coroutine client, models, attachment rules, and version tracking.
- `storepal-android`: Android 6+ (API 23), Compose/Material 3, Activity entry points, localization.
- `sample`: offline demo by default. Never commit or release a real StorePal key.
- Use JDK 21 and Android platform/build tools 35; use the checked-in Gradle wrapper.
- Published group is `app.storepal`; artifacts are `storepal-core` and `storepal-android`.
- Maven repository: https://xiao99xiao.github.io/StorePalAndroid/maven
- Never overwrite a published version. Increase the version in root `build.gradle.kts` for changes.
- Before a release, run unit tests, Android lint, sample/release builds, and emulator UI tests. See `PUBLISHING.md`.
- Keep all nine locales complete. The localization contract tests check keys and format arguments.
- App-scoped `sp_live_` keys are for runtime integration. Never put account-scoped `sp_user_` tokens in native apps.
- SDK and API access are available on Free. Pro only adds unlimited apps.
