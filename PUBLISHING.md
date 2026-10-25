# Publishing a release

The public source is https://github.com/xiao99xiao/StorePalAndroid. Releases contain
the Android AAR, core JAR, source archives, an offline demo APK, SHA-256 checksums,
and a downloadable Maven repository. The public Gradle repository is served from
the repository’s `gh-pages` branch at:

```text
https://xiao99xiao.github.io/StorePalAndroid/maven
```

Consumers do not need a GitHub account or token. Maven Central publication is not
claimed; the documented coordinates are served by the StorePal Maven repository.

1. Update the version in root `build.gradle.kts` and the README examples. Run:

   ```sh
   ./gradlew :storepal-core:test :storepal-android:testDebugUnitTest :storepal-android:lintDebug :sample:assembleDebug :storepal-android:assembleRelease :storepal-core:publishReleasePublicationToStorePalRepository :storepal-android:publishReleasePublicationToStorePalRepository
   ./gradlew :storepal-android:connectedDebugAndroidTest
   ```

2. Check the generated sample `BuildConfig`: `STOREPAL_API_KEY` must be empty. The
   public APK must run in offline demo mode. Push the source commit to `main` and
   wait for the Verify SDK workflow to pass.

3. Stage the Maven repository in a `gh-pages` worktree, using a new orphan branch
   for the initial release, or the existing remote branch thereafter:

   ```sh
   python3 scripts/stage_repository.py --destination /absolute/path/to/pages-worktree
   ```

   The script preserves existing versions and refuses to overwrite a version
   with different bytes. It merges version metadata and updates its checksums.
   Commit and push the worktree to `gh-pages`. GitHub Pages is configured to build
   the root of that branch. Wait for its deployment before announcing the release.

4. Verify the public POM, AAR, JAR, and checksums, then build an independent Android
   consumer with only the public Maven repository, Google, and Maven Central.
   Do not include `mavenLocal()` in that test.

5. Create the matching `vX.Y.Z` source tag and GitHub Release. Attach the AAR/JAR,
   source JARs, offline sample APK, Maven repository ZIP, and `SHA256SUMS`. Include
   the Gradle installation snippets and requirements in the release notes.

Never force-push a published tag or overwrite a released Maven version. Fixes
require a new version. The SDK’s own Maven repository is intentionally scoped to
`app.storepal`; consuming apps should use the same content filter shown in README.
