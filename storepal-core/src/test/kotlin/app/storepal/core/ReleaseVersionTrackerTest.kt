package app.storepal.core

import kotlin.test.*
import org.junit.Test

class ReleaseVersionTrackerTest {
    private val storage = object : VersionStorage { override var lastSeenVersion: String? = null }
    private val tracker = ReleaseVersionTracker(storage)
    @Test fun `first install records baseline and never prompts`() {
        assertFalse(tracker.needsPresentation("1.0"))
        assertEquals("1.0", storage.lastSeenVersion)
        assertFalse(tracker.needsPresentation("1.0"))
    }
    @Test fun `failed or missing release stays eligible until presented`() {
        tracker.needsPresentation("1.0")
        repeat(3) { assertTrue(tracker.needsPresentation("1.1")) }
        assertEquals("1.0", storage.lastSeenVersion)
        tracker.markPresented("1.1")
        assertFalse(tracker.needsPresentation("1.1"))
    }
    @Test fun `blank versions cannot replace the baseline`() {
        tracker.needsPresentation("1.0")
        tracker.markPresented("")
        assertFalse(tracker.needsPresentation(" "))
        assertEquals("1.0", storage.lastSeenVersion)
    }
    @Test fun `separate app storage cannot suppress another app update`() {
        tracker.needsPresentation("1.0")
        val second = ReleaseVersionTracker(object : VersionStorage { override var lastSeenVersion: String? = null })
        assertTrue(tracker.needsPresentation("1.1"))
        assertFalse(second.needsPresentation("1.1"))
    }
    @Test fun `attachment limits handle exact boundaries and rejected types`() {
        AttachmentLimits.validate("SCREEN.PNG", AttachmentLimits.MAX_FILE_BYTES.toLong())
        AttachmentLimits.validateSelection(listOf(5L * 1024 * 1024, 5L * 1024 * 1024))
        assertFailsWith<IllegalArgumentException> { AttachmentLimits.validate("empty.log", 0) }
        assertFailsWith<IllegalArgumentException> { AttachmentLimits.validate("large.pdf", AttachmentLimits.MAX_FILE_BYTES + 1L) }
        assertFailsWith<IllegalArgumentException> { AttachmentLimits.validate("script.exe", 100) }
        assertFailsWith<IllegalArgumentException> { AttachmentLimits.validateSelection(listOf(1, 1, 1, 1)) }
        assertFailsWith<IllegalArgumentException> { AttachmentLimits.validateSelection(List(3) { 4L * 1024 * 1024 }) }
    }
}
