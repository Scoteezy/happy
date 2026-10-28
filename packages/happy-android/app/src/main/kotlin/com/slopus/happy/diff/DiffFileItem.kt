package com.slopus.happy.diff

/** What a file header shows: a built [DiffFile] has it, and so does a git-status entry (`DiffFileSummary`). */
data class DiffFileSummary(val path: String, val kind: FileChangeKind, val additions: Int, val deletions: Int) {
    companion object {
        fun of(file: DiffFile) = DiffFileSummary(file.path, file.kind, file.additions, file.deletions)
    }
}

/** Both sides of an image file, as `data:` URIs. */
data class DiffImages(val before: String?, val after: String?)

/** DiffFilesList.tsx `DiffFileItem`: one changed file and, once fetched, what to draw for it. */
data class DiffFileItem(
    val path: String,
    val kind: FileChangeKind,
    val additions: Int,
    val deletions: Int,
    /** Null while the body is still being fetched. */
    val source: DiffSource? = null,
    /** Set instead of [source] for files that are pictures rather than text. */
    val image: DiffImages? = null,
    val error: String? = null,
    /** Why a file has no text body (empty, conflicted, ...). */
    val message: String? = null,
    /** Stable identity; [path] when null. */
    val key: String? = null,
) {
    val id: String get() = key ?: path
    val summary: DiffFileSummary get() = DiffFileSummary(path, kind, additions, deletions)
    val unavailable get() = source == null && image == null && error == null && message == null
}
