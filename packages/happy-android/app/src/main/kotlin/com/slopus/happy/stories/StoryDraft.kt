package com.slopus.happy.stories

import com.slopus.happy.direct.ClientId
import java.io.File

/**
 * A story as the editor holds it while the person makes it (happy-ios
 * `StoryDraft`): its frames, each with media from the device (sent once on
 * save), from a conversation, or kept from the story being edited, and
 * their overlays. Everything the editor changes goes through here, so the
 * limits the API keeps (ten frames, 32 overlays, five reaction bubbles)
 * hold before the daemon is asked.
 *
 * Swift's value type is a class here; `copy()` gives an independent one.
 */
class StoryDraft private constructor(
    /**
     * The story's ID: chosen here for a new one, so a retried save never
     * makes a second story.
     */
    val storyId: String,
    original: Story?,
    var caption: String,
    frames: List<Frame>,
    selected: Int,
) {
    sealed class Media {
        /** The frame's media in the story being edited. */
        data object Kept : Media()

        /** Media from a conversation, by identity. */
        data class Asset(val asset: StoryAsset) : Media()

        /** A file from the device, uploaded on save under `name`. */
        data class Local(val file: File, val name: String) : Media()
    }

    data class Frame(
        val id: String = ClientId.make(),
        val kind: StoryFrame.Kind,
        val width: Int?,
        val height: Int?,
        val durationMs: Int? = null,
        val media: Media,
        val placement: StoryTransform = StoryTransform.FITTED,
        /** Null lets the daemon pick it from the picture. */
        val background: List<String>? = null,
        val overlays: List<StoryOverlay> = emptyList(),
    )

    /** One device file to upload before saving. */
    data class Upload(val frameId: String, val file: File, val name: String)

    class NotUploaded(val frameId: String) : Exception("not uploaded: $frameId")

    /** The story being edited; null for a new one. */
    var original: Story? = original
        private set
    var frames: List<Frame> = frames
        private set
    var selected: Int = selected
        private set

    constructor(storyId: String = ClientId.make()) : this(storyId, null, "", emptyList(), 0)

    constructor(editing: Story) : this(editing.id, editing, editing.caption ?: "", framesOf(editing), 0)

    fun copy(): StoryDraft = StoryDraft(storyId, original, caption, frames, selected)

    /**
     * Keeps these edits over `latest`, a newer version saved elsewhere, so
     * the next save replaces it. A kept frame the newer version no longer
     * has is sent again by its media's identity.
     */
    fun rebase(latest: Story) {
        if (latest.id != storyId) return
        val held = latest.frames.map { it.id }.toSet()
        val was = original
        frames = frames.map { frame ->
            if (frame.media != Media.Kept || frame.id in held) return@map frame
            val asset = was?.frames?.firstOrNull { it.id == frame.id }?.asset ?: return@map frame
            frame.copy(media = Media.Asset(asset))
        }
        original = latest
    }

    val isNew: Boolean get() = original == null
    val current: Frame? get() = frames.getOrNull(selected)
    val canAddFrame: Boolean get() = frames.size < StoryCanvas.MAX_FRAMES

    /** Whether saving would change anything. */
    val hasChanges: Boolean
        get() {
            val original = original ?: return frames.isNotEmpty()
            if (caption != (original.caption ?: "")) return true
            return frames != framesOf(original)
        }

    // Frames

    /** Adds `frame` after the selected one and selects it; false at ten. */
    fun add(frame: Frame): Boolean {
        if (!canAddFrame) return false
        val index = if (frames.isEmpty()) 0 else selected + 1
        frames = frames.toMutableList().also { it.add(index, frame) }
        selected = index
        return true
    }

    fun removeFrame(id: String) {
        val index = frames.indexOfFirst { it.id == id }
        if (index < 0) return
        frames = frames.toMutableList().also { it.removeAt(index) }
        if (selected >= frames.size) selected = maxOf(0, frames.size - 1)
        else if (index < selected) selected -= 1
    }

    fun select(index: Int) {
        if (index !in frames.indices) return
        selected = index
    }

    fun placeMedia(transform: StoryTransform) {
        if (selected !in frames.indices) return
        updateSelected { it.copy(placement = transform.clamped) }
    }

    // Overlays

    /** Whether the selected frame has room for `overlay`. */
    fun fits(overlay: StoryOverlay): Boolean {
        val frame = current ?: return false
        if (frame.overlays.size >= StoryCanvas.MAX_OVERLAYS) return false
        if (overlay is StoryOverlay.Reaction) return frame.overlays.count { it is StoryOverlay.Reaction } < MAX_REACTIONS
        return true
    }

    /** Puts `overlay` on top of the selected frame; false when it has no room. */
    fun add(overlay: StoryOverlay): Boolean {
        if (!fits(overlay)) return false
        updateSelected { it.copy(overlays = it.overlays + overlay) }
        return true
    }

    fun overlay(id: String): StoryOverlay? = current?.overlays?.firstOrNull { it.id == id }

    /** Changes an overlay in place, keeping its spot in the drawing order. */
    fun replace(overlay: StoryOverlay) {
        val id = overlay.id ?: return
        val frame = current ?: return
        val index = frame.overlays.indexOfFirst { it.id == id }
        if (index < 0) return
        updateSelected { it.copy(overlays = it.overlays.toMutableList().also { list -> list[index] = overlay }) }
    }

    fun move(id: String, transform: StoryTransform) {
        val overlay = overlay(id) ?: return
        replace(overlay.withTransform(transform.clamped))
    }

    /** Lifts an overlay above the others, as touching it does. */
    fun bringToFront(id: String) {
        val frame = current ?: return
        val index = frame.overlays.indexOfFirst { it.id == id }
        if (index < 0) return
        updateSelected { f -> f.copy(overlays = f.overlays.toMutableList().also { it.add(it.removeAt(index)) }) }
    }

    fun remove(id: String) {
        if (selected !in frames.indices) return
        updateSelected { f -> f.copy(overlays = f.overlays.filter { it.id != id }) }
    }

    private inline fun updateSelected(change: (Frame) -> Frame) {
        frames = frames.toMutableList().also { it[selected] = change(it[selected]) }
    }

    // Saving

    /** The device files to upload before saving, by frame. */
    val uploads: List<Upload>
        get() = frames.mapNotNull { frame -> (frame.media as? Media.Local)?.let { Upload(frame.id, it.file, it.name) } }

    /**
     * The frames as the create or edit sends them, with each device file's
     * upload ID from `uploaded`.
     */
    fun inputs(uploaded: Map<String, String>): List<StoryFrameInput> = frames.map { frame ->
        val media = when (val m = frame.media) {
            Media.Kept -> StoryFrameInput.Media.Keep
            is Media.Asset -> StoryFrameInput.Media.Asset(m.asset)
            is Media.Local -> StoryFrameInput.Media.Upload(uploaded[frame.id] ?: throw NotUploaded(frame.id), m.name)
        }
        StoryFrameInput(frame.id, media, frame.placement, frame.background, frame.overlays)
    }

    /** The caption to send: null for none. */
    val sentCaption: String?
        get() {
            val trimmed = caption.trim()
            return if (trimmed.isEmpty()) null else trimmed.take(StoryCanvas.MAX_CAPTION)
        }

    /** The selected frame as a story the canvas draws, before it is saved. */
    fun preview(frame: Frame): Pair<Story, StoryFrame> {
        val asset = when (val m = frame.media) {
            is Media.Asset -> m.asset
            Media.Kept -> original?.frames?.firstOrNull { it.id == frame.id }?.asset ?: StoryAsset.ArtifactFile(storyId, 0, "")
            is Media.Local -> StoryAsset.ArtifactFile(storyId, 0, "media/${m.name}")
        }
        val shown = StoryFrame(
            id = frame.id, kind = frame.kind, width = frame.width, height = frame.height,
            thumbhash = original?.frames?.firstOrNull { it.id == frame.id }?.thumbhash,
            durationMs = frame.durationMs, asset = asset, available = true, placement = frame.placement,
            background = frame.background ?: listOf("#000000", "#000000"), overlays = frame.overlays,
        )
        val original = original
        val story = Story(
            id = storyId, status = StoryStatus.Draft, authorUserId = original?.authorUserId, createdBy = original?.createdBy ?: StoryCreator.User(null),
            caption = sentCaption, frames = listOf(shown), artifactVersion = original?.artifactVersion ?: 0,
            createdAt = original?.createdAt ?: 0, updatedAt = original?.updatedAt ?: 0, postedAt = null, expiresAt = null,
            seen = false, viewCount = null, myReaction = null, reactions = emptyList(), version = original?.version ?: "",
        )
        return story to shown
    }

    override fun equals(other: Any?): Boolean = other is StoryDraft && other.storyId == storyId && other.original == original &&
        other.caption == caption && other.frames == frames && other.selected == selected

    override fun hashCode(): Int = storyId.hashCode() * 31 + frames.hashCode()

    companion object {
        const val MAX_REACTIONS = 5

        private fun framesOf(story: Story): List<Frame> = story.frames.map {
            Frame(it.id, it.kind, it.width, it.height, it.durationMs, Media.Kept, it.placement, it.background, it.overlays)
        }
    }
}
