package app.storepal.android

import android.app.Activity
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import app.storepal.core.ReleaseVersionTracker
import app.storepal.core.StorePalClient
import app.storepal.core.StorePalService
import app.storepal.core.VersionStorage
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

object StorePalFeedback {
    // initialize() retains only applicationContext; no Activity or View is retained.
    @SuppressLint("StaticFieldLeak")
    @Volatile internal var runtime: StorePalRuntime? = null
    internal fun requireRuntime(): StorePalRuntime = checkNotNull(runtime) {
        "Call StorePalFeedback.configure in Application.onCreate before presenting StorePal."
    }

    @JvmStatic @JvmOverloads
    fun configure(context: Context, apiKey: String, userEmail: String? = null, userName: String? = null,
        baseUrl: String = "https://storepal.app") {
        val app = context.applicationContext
        val languages = StorePalLanguages(app)
        languages.use(context.resources.configuration)
        runtime = StorePalRuntime(app, StorePalClient(apiKey, baseUrl, languages::acceptLanguage),
            "$baseUrl|$apiKey", userEmail, userName, languages)
    }

    /** For custom transports. scopeId must uniquely identify the StorePal app, never an end user. */
    @JvmStatic @JvmOverloads
    fun initialize(context: Context, service: StorePalService, scopeId: String,
        userEmail: String? = null, userName: String? = null) {
        require(scopeId.isNotBlank()) { "Use a distinct scopeId for each StorePal app." }
        runtime = StorePalRuntime(context.applicationContext, service, scopeId, userEmail, userName)
    }

    @JvmStatic
    fun show(activity: Activity): Boolean = launch(activity, "feedback")

    /** Call on sign-out, then configure the next user's identity before showing feedback. */
    @JvmStatic
    fun clearUserData() {
        val state = requireRuntime()
        state.clearHistory()
        state.userEmail = null
        state.userName = null
    }

    /** Restore a privately held conversation link; never pass an account-scoped API token. */
    @JvmStatic
    fun rememberConversation(token: String): Boolean = requireRuntime().saveToken(token)

    val client: StorePalService get() = requireRuntime().service

    internal fun launch(activity: Activity, feature: String, version: String? = null): Boolean {
        val state = requireRuntime()
        if (activity.isFinishing || activity.isDestroyed || !state.presenting.compareAndSet(false, true)) return false
        state.languages.use(activity.resources.configuration)
        return try {
            activity.startActivity(Intent(activity, StorePalActivity::class.java)
                .putExtra("feature", feature).putExtra("version", version)
                .putExtra("language", state.languages.acceptLanguage()))
            true
        } catch (e: RuntimeException) {
            state.presenting.set(false)
            throw e
        }
    }
}

object StorePalWhatsNew {
    @JvmStatic @JvmOverloads
    fun show(activity: Activity, version: String? = null): Boolean =
        StorePalFeedback.launch(activity, "release", version ?: StorePalFeedback.requireRuntime().appVersion)

    /** Invoke in onResume. Missing notes and network failures remain eligible on later checks. */
    @JvmStatic
    fun check(activity: Activity) {
        val owner = activity as? LifecycleOwner ?: return
        val state = StorePalFeedback.requireRuntime()
        state.languages.use(activity.resources.configuration)
        if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            state.presenting.get() || !state.tracker.needsPresentation(state.appVersion) ||
            !state.checking.compareAndSet(false, true)) return
        owner.lifecycleScope.launch {
            try {
                val note = state.service.getReleaseNote(state.appVersion)
                if (note != null && note.content.isNotBlank() && note.version == state.appVersion &&
                    StorePalFeedback.runtime === state && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    // The activity fetches again and records the version only after a rendered frame.
                    StorePalFeedback.launch(activity, "release", note.version)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Retry when the host next calls check. */ }
            finally { state.checking.set(false) }
        }
    }
}

internal class StorePalRuntime(
    val context: Context,
    val service: StorePalService,
    scopeId: String,
    var userEmail: String?,
    var userName: String?,
    val languages: StorePalLanguages = StorePalLanguages(context),
) {
    private val scopeHash = MessageDigest.getInstance("SHA-256").digest(scopeId.toByteArray())
        .take(16).joinToString("") { "%02x".format(it) }
    val preferences = context.getSharedPreferences("storepal_$scopeHash", Context.MODE_PRIVATE)
    val historyGeneration = AtomicLong(0)
    private val migrationKey = "tokenInboxMigrationV1"
    private val migrationEmailKey = "tokenInboxLegacyEmail"
    private val migrationPageKey = "tokenInboxLegacyPage"
    init {
        if (!preferences.contains(migrationKey)) {
            val oldEmail = preferences.getString("email", null)
            preferences.edit {
                putBoolean(migrationKey, oldEmail.isNullOrBlank())
                if (!oldEmail.isNullOrBlank()) putString(migrationEmailKey, oldEmail)
            }
        }
    }

    private fun canonicalToken(value: String): String? = runCatching {
        UUID.fromString(value).toString().takeIf { it.equals(value, ignoreCase = true) }
    }.getOrNull()

    @Synchronized fun tokens(): List<String> = preferences.getStringSet("conversationTokens", emptySet()).orEmpty()
        .mapNotNull(::canonicalToken).distinct()

    @Synchronized fun saveToken(value: String, generation: Long = historyGeneration.get()): Boolean {
        if (generation != historyGeneration.get()) return false
        val token = canonicalToken(value) ?: return false
        preferences.edit { putStringSet("conversationTokens", (tokens() + token).toSet()) }
        return true
    }

    @Synchronized fun clearHistory() {
        historyGeneration.incrementAndGet()
        preferences.edit {
            remove("email"); remove("name"); remove("conversationTokens")
            remove(migrationEmailKey); remove(migrationPageKey); putBoolean(migrationKey, true)
        }
    }

    @Synchronized fun saveSubmission(name: String, email: String, token: String, generation: Long): Boolean {
        if (!saveToken(token, generation)) return false
        saveIdentity(name, email)
        return true
    }

    @Synchronized private fun finishMigration(generation: Long) {
        if (generation != historyGeneration.get()) throw CancellationException("Account changed")
        preferences.edit { putBoolean(migrationKey, true); remove(migrationEmailKey); remove(migrationPageKey) }
    }

    @Suppress("DEPRECATION")
    suspend fun inbox(page: Int): app.storepal.core.ConversationsPage {
        val generation = historyGeneration.get()
        if (!preferences.getBoolean(migrationKey, true)) {
            val oldEmail = preferences.getString(migrationEmailKey, null)
            if (!oldEmail.isNullOrBlank()) {
                var next = preferences.getInt(migrationPageKey, 1)
                try {
                    while (true) {
                        val result = service.listConversations(oldEmail, next)
                        if (generation != historyGeneration.get()) throw CancellationException("Account changed")
                        check(result.page == next && result.perPage > 0) { "Invalid migration response" }
                        result.conversations.forEach { saveToken(it.conversationToken, generation) }
                        if (result.conversations.isEmpty() || !result.hasMore) { finishMigration(generation); break }
                        next++
                        preferences.edit { putInt(migrationPageKey, next) }
                    }
                } catch (error: app.storepal.core.StorePalException) {
                    if (error.code == "USER_TOKEN_REQUIRED") finishMigration(generation) else throw error
                }
            } else finishMigration(generation)
        }
        val result = service.listConversations(tokens(), page)
        if (generation != historyGeneration.get()) throw CancellationException("Account changed")
        return result
    }
    val presenting = AtomicBoolean(false)
    val checking = AtomicBoolean(false)
    val tracker = ReleaseVersionTracker(object : VersionStorage {
        override var lastSeenVersion: String?
            get() = preferences.getString("lastVersion", null)
            set(value) { preferences.edit { putString("lastVersion", value) } }
    })
    @Suppress("DEPRECATION")
    private val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    val appVersion: String = packageInfo.versionName.orEmpty()
    fun metadata(): Map<String, String> = mapOf(
        "app_version" to appVersion,
        "app_build" to if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode.toString() else @Suppress("DEPRECATION") packageInfo.versionCode.toString(),
        "app_name" to context.applicationInfo.loadLabel(context.packageManager).toString(),
        "os_version" to "Android ${Build.VERSION.RELEASE}",
        "locale" to languages.acceptLanguage(),
        "hardware" to "${Build.MANUFACTURER} ${Build.MODEL}",
    )
    fun saveIdentity(name: String, email: String) {
        preferences.edit { putString("name", name.trim()); putString("email", email.trim()) }
    }
}
