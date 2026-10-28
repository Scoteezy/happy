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

    /** What an open FileSection shows, in DiffFilesList.tsx's order. */
    sealed interface Body {
        data class Error(val message: String) : Body
        data class Message(val message: String) : Body
        data class Image(val images: DiffImages) : Body
        data object Loading : Body
        /** No text changes ("diff.noChanges"). */
        data object NoChanges : Body
        data class Files(val files: List<DiffFile>) : Body
    }

    /** The body of an open section; [document] is [source] built. */
    fun body(document: DiffDocument): Body {
        error?.let { return Body.Error(it) }
        message?.let { return Body.Message(it) }
        image?.let { return Body.Image(it) }
        val source = source ?: return Body.Loading
        document.error?.let { return Body.Error(it) }
        val hasTextRows = document.files.any { file -> file.rows.any { it is DiffRow.Line } }
        if (source is DiffSource.Contents && !hasTextRows) return Body.NoChanges
        if (document.files.isEmpty()) return Body.NoChanges
        return Body.Files(document.files)
    }
}

/** DiffFilesList.tsx's collapse rules. */
object DiffFilesCollapse {
    /** Files with more changed lines than this start collapsed. */
    const val AUTO_COLLAPSE_ABOVE = 2000

    fun isTooBig(item: DiffFileItem, autoCollapseAbove: Int = AUTO_COLLAPSE_ABOVE) = item.additions + item.deletions > autoCollapseAbove

    /** A reader's toggle wins; otherwise collapsed when everything starts collapsed or the file is too big. */
    fun isCollapsed(item: DiffFileItem, override: Boolean?, defaultCollapsed: Boolean, autoCollapseAbove: Int = AUTO_COLLAPSE_ABOVE) =
        override ?: (defaultCollapsed || isTooBig(item, autoCollapseAbove))

    /** "N changed lines — tap to expand" only explains a size-forced close. */
    fun showsSizeHint(item: DiffFileItem, collapsed: Boolean, autoCollapseAbove: Int = AUTO_COLLAPSE_ABOVE) =
        collapsed && isTooBig(item, autoCollapseAbove)

    /** FileSection asks for a body once per expansion, while it has none. */
    fun needsContent(item: DiffFileItem, collapsed: Boolean) = !collapsed && item.unavailable
}
