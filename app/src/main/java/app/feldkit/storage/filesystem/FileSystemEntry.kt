package app.feldkit.storage.filesystem

import app.feldkit.storage.filesystem.apfs.ApfsExtent

enum class FileType {
    IMAGE, VIDEO, AUDIO, DOCUMENT, ARCHIVE, CODE, TEXT, PDF, OTHER, DIRECTORY
}

data class FileSystemEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val createdAt: Long,    // milliseconds since epoch
    val modifiedAt: Long,   // milliseconds since epoch
    // APFS-specific
    val inodeOid: Long = 0L,
    val parentOid: Long = 0L,
    val extents: List<ApfsExtent> = emptyList(),
    // HFS+-specific
    val hfsCatalogId: Int = 0,
    val hfsParentId: Int = 0,
    // Folder details (populated on demand)
    val childCount: Int? = null,
    val totalSize: Long? = null,
    // exFAT: data is stored contiguously (NoFatChain flag) and the allocated length of the stream
    val contiguous: Boolean = false,
    // which mounted partition the entry belongs to (multi-partition drives)
    val partitionId: Int = 0,
    // APFS: which volume of the container the entry belongs to (selects its decryption key)
    val volumeIndex: Int = 0,
    val allocLength: Long = 0L
) {
    val extension: String get() {
        val dot = name.lastIndexOf('.')
        return if (dot >= 0) name.substring(dot + 1).lowercase() else ""
    }

    val isRaw: Boolean get() = extension in RAW_EXT
    val isPsd: Boolean get() = extension == "psd"

    val formattedSize: String get() = formatBytes(size)

    val fileType: FileType get() = when {
        isDirectory -> FileType.DIRECTORY
        extension in IMAGE_EXT -> FileType.IMAGE
        extension in VIDEO_EXT -> FileType.VIDEO
        extension in AUDIO_EXT -> FileType.AUDIO
        extension == "pdf" -> FileType.PDF
        extension in TEXT_EXT -> FileType.TEXT
        extension in DOC_EXT -> FileType.DOCUMENT
        extension in ARCHIVE_EXT -> FileType.ARCHIVE
        extension in CODE_EXT -> FileType.CODE
        else -> FileType.OTHER
    }

    companion object {
        /** Camera RAW formats Android cannot decode itself; the embedded JPEG preview is shown instead (DNG decodes natively). */
        val RAW_EXT = setOf("cr2", "cr3", "crw", "nef", "nrw", "arw", "srf", "sr2", "orf", "rw2", "raf", "pef", "srw", "rwl", "3fr", "erf", "kdc", "mrw", "dcr", "raw", "x3f", "iiq", "mef", "mos")
        val IMAGE_EXT = setOf("jpg", "jpeg", "jpe", "png", "gif", "webp", "heic", "heif", "bmp", "svg", "avif", "dng", "ico", "wbmp", "jfif", "psd") + RAW_EXT
        val VIDEO_EXT = setOf("mp4", "m4v", "mov", "mkv", "webm", "avi", "3gp", "3g2", "mts", "m2ts", "mpg", "mpeg", "ogv", "flv", "wmv", "asf", "vob", "divx", "mxf", "dv", "m2v", "rm", "rmvb", "f4v", "mod")
        val AUDIO_EXT = setOf("mp3", "aac", "flac", "wav", "m4a", "ogg", "oga", "opus", "wma", "mka", "mid", "midi", "amr", "aif", "aiff", "ac3", "weba", "m4b", "caf")
        val TEXT_EXT = setOf("txt", "md", "markdown", "log", "csv", "tsv", "json", "xml", "yaml", "yml", "toml", "ini", "conf", "cfg", "properties", "html", "htm", "css", "srt", "vtt", "nfo", "tex", "plist", "gitignore", "env")
        val DOC_EXT = setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "pages", "numbers", "key", "epub", "mobi")
        val ARCHIVE_EXT = setOf("zip", "gz", "tgz", "tar", "7z", "rar", "bz2", "xz", "zst", "lz4", "cab", "dmg", "pkg", "iso", "jar", "apk", "aab", "deb", "rpm")
        val CODE_EXT = setOf("kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "swift", "c", "cc", "cpp", "h", "hpp", "rs", "go", "sh", "bash", "zsh", "rb", "php", "cs", "sql", "gradle", "lua", "dart", "m", "mm", "pl", "r", "scala")

        fun formatBytes(bytes: Long): String = when {
            bytes < 0 -> "Unknown"
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
            else -> "${"%.2f".format(bytes / (1024.0 * 1024 * 1024))} GB"
        }
    }
}
