package com.slopus.happy.stories

import com.slopus.happy.direct.ClientId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI

/**
 * happy-ios `StoryDraftTests`: the editor's story, the API's limits held
 * before the daemon is asked, and the frames sent the way `POST` and
 * `PATCH /v0/stories` take them.
 */
class StoryDraftTest {
    private fun photo(name: String = "IMG_1.jpg") = StoryDraft.Frame(kind = StoryFrame.Kind.Image, width = 1200, height = 1600, media = StoryDraft.Media.Local(File("/tmp/$name"), name))

    private fun sticker(id: String = ClientId.make()): StoryOverlay = StoryOverlay.Sticker(id, "shapes", "circle", StoryTransform(0.5, 0.5, 0.3, 0.0))

    private fun reaction(): StoryOverlay = StoryOverlay.Reaction(ClientId.make(), "🔥", null, null, StoryTransform(0.5, 0.5, 0.25, 0.0))

    private fun frame(id: String, toolCallId: String = "t") =
        StoryFrame(id, StoryFrame.Kind.Image, 100, 100, thumbhash = null, durationMs = null, asset = StoryAsset.ChatMedia("a", toolCallId, 0), available = true)

    private fun story(version: String, frames: List<StoryFrame>, caption: String? = null) = Story(
        id = "s1", status = StoryStatus.Draft, authorUserId = null, createdBy = StoryCreator.Agent("a"), caption = caption, frames = frames,
        artifactVersion = 2, createdAt = 0, updatedAt = 0, postedAt = null, expiresAt = null, seen = false, viewCount = null,
        myReaction = null, reactions = emptyList(), version = version,
    )

    @Test
    fun atMostTenFrames() {
        val draft = StoryDraft()
        assertFalse(draft.hasChanges)
        for (index in 0 until 10) assertTrue(draft.add(photo("$index.jpg")))
        assertFalse(draft.add(photo()))
        assertEquals(10, draft.frames.size)
        assertEquals(9, draft.selected)
        assertTrue(draft.hasChanges)
    }

    /**
     * A frame goes in after the one selected; removing one keeps the
     * selection on the frame it was on.
     */
    @Test
    fun framesInsertAfterTheSelection() {
        val draft = StoryDraft()
        val a = photo("a.jpg")
        val b = photo("b.jpg")
        val c = photo("c.jpg")
        draft.add(a); draft.add(b)
        draft.select(0)
        draft.add(c)
        assertEquals(listOf(a.id, c.id, b.id), draft.frames.map { it.id })
        assertEquals(1, draft.selected)
        draft.select(2)
        draft.removeFrame(a.id)
        assertEquals(b.id, draft.current?.id)
        draft.removeFrame(b.id)
        assertEquals(c.id, draft.current?.id)
    }

    @Test
    fun atMostFiveReactionsAndThirtyTwoOverlays() {
        val draft = StoryDraft()
        draft.add(photo())
        repeat(5) { assertTrue(draft.add(reaction())) }
        assertFalse(draft.add(reaction()))
        repeat(27) { assertTrue(draft.add(sticker())) }
        assertFalse(draft.add(sticker()))
        assertEquals(32, draft.current?.overlays?.size)
    }

    /** Touching an overlay lifts it to the top; moving it keeps the API's ranges. */
    @Test
    fun overlaysMoveAndLift() {
        val draft = StoryDraft()
        draft.add(photo())
        draft.add(sticker("one")); draft.add(sticker("two"))
        draft.bringToFront("one")
        assertEquals(listOf("two", "one"), draft.current?.overlays?.map { it.id })
        draft.move("one", StoryTransform(2.0, 0.4, 20.0, 4.0))
        assertEquals(StoryTransform(1.5, 0.4, 10.0, PI), draft.overlay("one")?.transform)
        draft.remove("two")
        assertEquals(listOf("one"), draft.current?.overlays?.map { it.id })
    }

    /**
     * Device files go as their uploads, conversation media by identity,
     * and an edited story's frames keep their media.
     */
    @Test
    fun inputs() {
        val draft = StoryDraft()
        val local = photo()
        draft.add(local)
        val asset = StoryAsset.ChatMedia("a1", "t1", 0)
        draft.add(StoryDraft.Frame(kind = StoryFrame.Kind.Image, width = 10, height = 10, media = StoryDraft.Media.Asset(asset)))
        assertThrows(StoryDraft.NotUploaded::class.java) { draft.inputs(emptyMap()) }
        assertEquals(listOf(local.id), draft.uploads.map { it.frameId })
        val inputs = draft.inputs(mapOf(local.id to "u1"))
        assertEquals(listOf(StoryFrameInput.Media.Upload("u1", "IMG_1.jpg"), StoryFrameInput.Media.Asset(asset)), inputs.map { it.media })
        assertEquals(draft.frames.map { it.id }, inputs.map { it.id })
    }

    @Test
    fun editingAStory() {
        val frame = frame("f1")
        val draft = StoryDraft(story("v", listOf(frame), caption = "Hi"))
        assertFalse(draft.isNew)
        assertFalse(draft.hasChanges)
        draft.add(StoryOverlay.Emoji("e1", "🚀", StoryTransform.FITTED))
        assertTrue(draft.hasChanges)
        val inputs = draft.inputs(emptyMap())
        assertEquals(StoryFrameInput.Media.Keep, inputs.first().media)
        assertEquals(1, inputs.first().overlays?.size)
        draft.caption = "  "
        assertNull(draft.sentCaption)
        val (preview, shown) = draft.preview(draft.frames[0])
        assertEquals("s1", preview.id)
        assertEquals(frame.asset, shown.asset)
    }

    /**
     * Keeping edits over a newer version: it becomes the base, the edits
     * stay, and a kept frame it no longer has is sent by its asset.
     */
    @Test
    fun rebasingOnANewerVersion() {
        val draft = StoryDraft(story("v1", listOf(frame("f1", "f1"), frame("f2", "f2"))))
        draft.select(1)
        draft.add(StoryOverlay.Emoji("e1", "🚀", StoryTransform.FITTED))
        draft.add(photo())
        draft.rebase(story("v2", listOf(frame("f2", "f2"))))
        assertEquals("v2", draft.original?.version)
        assertEquals(listOf("f1", "f2"), draft.frames.map { it.id }.take(2))
        assertEquals(StoryDraft.Media.Asset(StoryAsset.ChatMedia("a", "f1", 0)), draft.frames[0].media)
        assertEquals(StoryDraft.Media.Kept, draft.frames[1].media)
        assertEquals(3, draft.frames.size)
        assertEquals(2, draft.selected)
        assertEquals(1, draft.frames[1].overlays.size)
        assertTrue(draft.hasChanges)
        draft.inputs(mapOf(draft.frames[2].id to "u1"))
    }
}
