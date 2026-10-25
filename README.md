# StorePal Android SDK

Native feedback forms, attachments, conversations, and “What’s new” for Android.
Written in Kotlin with coroutines and Jetpack Compose / Material 3. Android 6.0
(API 23) and later. Existing Java and XML/View apps can open the SDK Activity
without migrating their own UI to Compose.

[![Verify SDK](https://github.com/xiao99xiao/StorePalAndroid/actions/workflows/verify.yml/badge.svg)](https://github.com/xiao99xiao/StorePalAndroid/actions/workflows/verify.yml)

**Version 0.1.0** · [Release downloads](https://github.com/xiao99xiao/StorePalAndroid/releases/tag/v0.1.0) · [StorePal](https://storepal.app)

## Install

Add the StorePal Maven repository to `dependencyResolutionManagement.repositories`
in your app’s `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://xiao99xiao.github.io/StorePalAndroid/maven")
            content { includeGroup("app.storepal") }
        }
    }
}
```

Then add the SDK to your app module:

```kotlin
dependencies {
    implementation("app.storepal:storepal-android:0.1.0")
}
```

No GitHub token is required. For your own UI or a JVM client, use
`app.storepal:storepal-core:0.1.0` instead. The artifacts are hosted in the
StorePal Maven repository; they are not published to Maven Central.

For a quick look, install the [offline example APK](https://github.com/xiao99xiao/StorePalAndroid/releases/download/v0.1.0/storepal-example-0.1.0.apk).

## Modules

| Module | Purpose |
| --- | --- |
| `storepal-core` | Coroutine REST client and public models; no Android or UI dependency |
| `storepal-android` | Android setup, device context, persistence, Compose screens, and Activity entry points |
| `sample` | Runnable example; uses clearly labeled offline demo data unless you provide a key |

The core transport uses OkHttp and kotlinx.serialization. Android screens use
Material 3 and native text rendering (Markwon for release-note Markdown), rather
than a WebView. Embedded screens inherit your app’s MaterialTheme. The standalone
Activity follows the system light/dark theme and dynamic colors on Android 12+.
UI strings cover all nine StorePal languages: English, Simplified Chinese,
Traditional Chinese, Japanese, Korean, French, German, Spanish, and Portuguese.
This includes validation, network errors, empty states, attachment controls, and
draft confirmations. Reply counts follow each locale’s plural rules; numeric
counts and conversation dates use the UI’s locale. Regional variants such as
French (Canada), Spanish (Mexico), and Traditional Chinese (Hong Kong) resolve
through Android’s resource matching. The SDK selects the first supported language
in the user’s preference list on Android 7+, or English if none is supported.
This selection also controls numeric/date formatting, diagnostic locale, and the
Accept-Language header for release notes; unrelated host-library translations do
not force an English fallback.

The SDK follows the host app’s resource configuration. Embedded Compose screens
also update when the host changes locale, keeping the draft intact. The sample
includes translated example content and declares these nine languages for
Android 13+ per-app language settings. The library does not change the host’s
language settings or advertise languages on its behalf. The standalone presenter
also carries the host Activity’s language on Android 12 and earlier, including
AndroidX per-app language overrides. Custom StorePalService implementations are
responsible for their own Accept-Language header.

If your host app changes language directly in-app, ensure its language resources
are installed before switching. For apps that ship all translations together,
use `bundle { language { enableSplit = false } }` in the app module, as the offline
sample does. Apps using Play language delivery can keep their existing download
flow. The SDK leaves this host-level packaging choice to your app.

## Build and run

Open the SDK root directory in Android Studio, or use JDK 17–23 (JDK 21 recommended)
and an Android SDK with platform/build tools 35:

```sh
./gradlew :storepal-core:test :storepal-android:testDebugUnitTest :storepal-android:lintDebug :sample:assembleDebug
./gradlew :sample:installDebug
```

Without a key the sample runs offline. Messages remain in memory and reset when
the process restarts. No demo messages are sent to StorePal. For real API access,
create an **app-scoped `sp_live_` key** in Dashboard → Integrations. SDK/API access
is available on Free; Pro adds unlimited apps.

Put a development key in your private `~/.gradle/gradle.properties`:

```properties
storepalApiKey=sp_live_your_app_key
```

Rebuild the sample to connect to that app. Never embed a `sp_user_` management
token in a distributed application.

## Build locally

Clone this repository and publish to your private local Maven repository:

```sh
git clone https://github.com/xiao99xiao/StorePalAndroid.git
cd StorePalAndroid
./gradlew :storepal-core:publishToMavenLocal :storepal-android:publishToMavenLocal
```

For local development only, add `mavenLocal()` to your app’s dependency
repositories. Production integration should use the public repository above.
See [PUBLISHING.md](PUBLISHING.md) for the maintainer release process.

## Configure once

Configure in `Application.onCreate`, so Android can restore the SDK Activity after
process recreation. Register your Application class in your app’s manifest.

```kotlin
import android.app.Application
import app.storepal.android.StorePalFeedback

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        StorePalFeedback.configure(
            context = this,
            apiKey = "sp_live_...",
            userEmail = "reader@example.com", // optional prefill
            userName = "Alex",                // optional prefill
        )
    }
}
```

The library manifest supplies INTERNET permission and a non-exported presenter
Activity. It requests no broad storage permission: the system file picker grants
access only to files the user chooses.

## Compose

```kotlin
import app.storepal.android.*
import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column

@Composable
fun SettingsScreen() {
    // Add once at your root: checks on resume, and skips first install.
    StorePalWhatsNewOnUpdate()

    val activity = LocalContext.current as Activity
    Column {
        FeedbackButton()
        Button(onClick = { StorePalWhatsNew.show(activity) }) {
            Text("What’s new")
        }
    }
}
```

For your own navigation and theme, embed `StorePalFeedbackScreen(onClose = ...)`
or `StorePalWhatsNewScreen(onClose = ..., version = "1.2.0")`. Present
`StorePalFeedbackSheet(onDismissRequest = ...)` conditionally for a bottom sheet.
Remove it from composition on dismissal. The feedback UI asks before discarding
an unsent draft and holds navigation while a message is being sent. File bytes
stay in the ViewModel, not in saved-instance state; reselect attachments after
process death. Draft text survives normal Activity recreation.

## Existing Views / Java apps

No Compose calls are required in your own screens:

```kotlin
// Inside a visible Activity:
feedbackButton.setOnClickListener { StorePalFeedback.show(this) }
whatsNewButton.setOnClickListener { StorePalWhatsNew.show(this) }

override fun onResume() {
    super.onResume()
    StorePalWhatsNew.check(this)
}
```

```java
StorePalFeedback.configure(getApplicationContext(), "sp_live_...");
StorePalFeedback.show(this);
StorePalWhatsNew.show(this);
```

Automatic checks require an AndroidX LifecycleOwner, such as ComponentActivity or
AppCompatActivity. They compare your app’s **versionName** exactly with the release
version published in StorePal. First use records a baseline without a prompt.
Subsequent version changes show a note only if that exact version has content.
Missing notes, failed requests, and interrupted presentations remain eligible.
The version is recorded after the content renders in a resumed screen; manually
opening an older release does not suppress a current-version prompt.

## Custom UI / networking

```kotlin
import app.storepal.core.*

val client = StorePalClient("sp_live_...")
// Call from a coroutine. Requests run off the main thread and support cancellation.
val uploaded = client.uploadAttachment(bytes, "screenshot.png", "image/png")
val feedback = client.submitFeedback(
    name = "Alex",
    email = "reader+android@example.com",
    category = FeedbackCategory.BUG,
    message = "The reader closes when I rotate the screen.",
    attachmentIds = listOf(uploaded.attachmentId),
)
val inbox = client.listConversations("reader+android@example.com", page = 1)
val conversation = client.getConversation(feedback.conversationToken) // marks read
val reply = client.postReply(feedback.conversationToken, "Here is more context.")
val unread = client.getUnreadCount("reader+android@example.com")
val note = client.getReleaseNote("1.2.0") // null only when no matching release exists
```

Implement `StorePalService` and use
`StorePalFeedback.initialize(context, service, scopeId, userEmail, userName)` to
replace the transport. Use a stable, distinct scopeId per StorePal app. This also
makes previews and tests deterministic.

## Feedback and errors

- The form requires a name, valid email, and 10–5,000 characters. The lower-level
  client follows the server’s 1–5,000-character message limit. Replies accept 1–5,000.
- Up to 3 files, 5 MB per file, 10 MB total: PNG, JPEG, GIF, WebP, PDF, TXT, LOG.
  Files are read with a hard byte limit and uploaded only when sending. Successful
  uploads are reused for a retry for up to 50 minutes; unbound server uploads
  expire after one hour.
- `StorePalException` exposes `code`, `statusCode`, and `retryAfterSeconds`.
  `COOLDOWN` means wait 30 seconds; `REPLY_LIMIT` means start a new conversation;
  `RATE_LIMITED` means back off (the API allows 60 requests/minute per token).
- A `207 PARTIAL_BIND` response means the message **was saved**. Feedback/reply
  results expose `partialAttachmentFailure`; show the warning without resending.
- Mutating requests have no automatic retry. After an uncertain network failure,
  check the inbox before sending again; the API has no idempotency-key support.
- Inbox refresh is explicit, with pagination. No background polling or analytics
  tracking is included.

## Privacy and account changes

The built-in form includes app version/build/name, Android version, locale, and
device manufacturer/model. Name, email, message, and chosen attachments are sent
only through feedback actions. The core client adds no metadata automatically.

The SDK stores the last displayed app version and the last submitted name/email
in app-private SharedPreferences, scoped to the StorePal configuration. File data
stays in memory. No API key or conversation token is written to those preferences.
Host apps control Android backup policy; exclude `storepal_*.xml` preferences if
your policy disallows backing up contact information. See the sample’s disabled
backup setting as one option.

On sign-out, dismiss SDK UI, call `StorePalFeedback.clearUserData()`, then configure
the next identity before reopening. Configure and present on the main thread.
Do not switch StorePal app/identity while a feedback screen is open.

## Verification and packaging

```sh
./gradlew :storepal-core:test :storepal-android:testDebugUnitTest :storepal-android:lintDebug :sample:assembleDebug
# With a booted emulator:
./gradlew :storepal-android:connectedDebugAndroidTest
# Release artifacts and local Maven metadata:
./gradlew :storepal-android:assembleRelease :storepal-core:publishToMavenLocal :storepal-android:publishToMavenLocal
```

CI builds the library/sample, checks all translation keys and format arguments,
runs transport and version-tracking tests, performs Android lint, and runs the UI
and locale-resolution suites on an API 35 emulator. Locale checks cover all nine
languages, Chinese scripts, regional and secondary-language fallback, plural
counts, localized dates/numbers, and changing the UI language with an unsent draft.
The Gradle wrapper pins the distribution checksum.
