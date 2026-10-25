package app.storepal.core

interface VersionStorage {
    var lastSeenVersion: String?
}

/** Scope storage to a single StorePal app, and mark only after UI presentation. */
class ReleaseVersionTracker(private val storage: VersionStorage) {
    @Synchronized
    fun needsPresentation(version: String): Boolean {
        if (version.isBlank()) return false
        if (storage.lastSeenVersion.isNullOrBlank()) {
            storage.lastSeenVersion = version
            return false
        }
        return storage.lastSeenVersion != version
    }

    @Synchronized
    fun markPresented(version: String) {
        if (version.isNotBlank()) storage.lastSeenVersion = version
    }
}
