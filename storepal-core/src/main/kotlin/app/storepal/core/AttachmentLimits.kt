package app.storepal.core

object AttachmentLimits {
    const val MAX_FILES = 3
    const val MAX_FILE_BYTES = 5 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 10 * 1024 * 1024

    private val extensions = mapOf(
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
        "gif" to "image/gif", "webp" to "image/webp", "pdf" to "application/pdf",
        "txt" to "text/plain", "log" to "text/plain",
    )

    fun mimeType(fileName: String): String? = extensions[fileName.substringAfterLast('.', "").lowercase()]

    fun validate(fileName: String, size: Long) {
        require(size in 1..MAX_FILE_BYTES.toLong()) { "Files must be between 1 byte and 5 MB." }
        require(mimeType(fileName) != null) { "Choose PNG, JPEG, GIF, WebP, PDF, TXT, or LOG files." }
    }

    fun validateSelection(sizes: List<Long>) {
        require(sizes.size <= MAX_FILES) { "Attach up to 3 files." }
        require(sizes.all { it in 1..MAX_FILE_BYTES.toLong() }) { "Each file must be 1 byte–5 MB." }
        require(sizes.sum() <= MAX_TOTAL_BYTES) { "Attachments must total 10 MB or less." }
    }
}
