package com.slopus.happy.ui.stemcells

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.slopus.happy.R
import com.slopus.happy.layout.HomeDockLayout
import com.slopus.happy.layout.HomeScreenLayout
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.Rect
import com.slopus.happy.net.ConnectionStatus
import com.slopus.happy.stemcells.StemCellCreation
import com.slopus.happy.stemcells.StemCellsRow
import com.slopus.happy.stemcells.StemCellsRowActions
import com.slopus.happy.stemcells.StemCellsRowActions.Action.*
import com.slopus.happy.ui.home.HomeHeaderView
import com.slopus.happy.ui.nav.BackInterceptor
import com.slopus.happy.ui.nav.Screen
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.setFrame
import com.slopus.happy.ui.view.px

/**
 * Home for a paired computer in development and preview builds (happy-ios
 * `StemCellsDirectHomeViewController`): home's floating header over that
 * computer's Stem Cells. The computer's mark at the left opens settings;
 * its name is plain in the middle; search alone is the pill at the right.
 * A row's conversation and new work open over it; [open] and [newWork]
 * hear of them, and the host opens what else a row opens.
 */
class StemCellsDirectHomeScreen(
    context: Context,
    val session: StemCellsSession,
    private val computerName: String,
) : Screen(context), BackInterceptor {
    val header = HomeHeaderView(context)
    val stemCells = StemCellsHomeView(context)
    var open: ((StemCellsRow) -> Unit)? = null
    var newWork: ((StemCellCreation, Boolean) -> Unit)? = null
    var openSettings: (() -> Unit)? = null
    var openSearch: (() -> Unit)? = null
    private var hold: StemCellsHoldOverlay? = null

    override fun createView(): View = Root(context)

    override fun viewCreated(view: View) {
        header.stemCellsActions = true
        header.title = computerName
        header.searchButton.tag = "stem-search"
        header.searchButton.setOnClickListener { search() }
        header.logoControl.contentDescription = context.getString(R.string.stem_open_settings)
        header.logoControl.tag = "stem-settings-mark"
        header.logoControl.setOnClickListener { openSettings?.invoke() }
        stemCells.onSnapshot = { snapshot -> header.status = if (snapshot.live) ConnectionStatus.CONNECTED else ConnectionStatus.CONNECTING }
        stemCells.onOpen = { row ->
            open?.invoke(row)
            if (row.kind == StemCellsRow.Kind.Search) search()
            if (row.targetKind == "artifacts") push(DirectArtifactsScreen(context, session))
            val surfaceId = row.targetId
            if (row.targetKind == "surface" && surfaceId != null) push(StemCellsSurfaceScreen(context, session, surfaceId, row.surfaceType ?: "", row.label))
            StemCellChatScreen.forRow(context, session, row)?.let { push(it) }
        }
        stemCells.onNewWork = { creation, library ->
            newWork?.invoke(creation, library)
            push(StemCellChatScreen.forNewWork(context, session, creation, attaching = library))
        }
        stemCells.onBackStateChanged = { stack?.backStateChanged() }
        stemCells.onHold = { row, source -> showHold(row, source) }
        stemCells.sidebar.onEditingChanged = { stemCells.requestLayout(); stack?.backStateChanged() }
        stemCells.attach(session)
    }

    /** The sidebar's search, over home; what it finds opens as its row would. */
    private fun search() {
        openSearch?.invoke()
        push(StemCellsSearchScreen(context, session) { row -> stemCells.open(row) })
    }

    override fun onAppear() {
        stemCells.appear()
    }

    override fun onDisappear() {
        hold?.close(animated = false)
        stemCells.disappear()
    }

    override fun onBackPressed(): Boolean {
        hold?.let { it.close(); return true }
        return stemCells.back()
    }

    override val interceptsBack: Boolean get() = hold != null || stemCells.pickerOpen || stemCells.sidebar.isEditing

    override fun onDestroy() {
        hold?.close(animated = false)
        stemCells.close()
    }

    private fun showHold(row: StemCellsRow, source: StemCellsRowView): Boolean {
        if (hold != null) return false
        val actions = StemCellsRowActions.groups(row)
        if (actions.isEmpty()) return false
        source.isPressed = false
        source.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        val root = view as Root
        val at = IntArray(2).also(source::getLocationOnScreen)
        val base = IntArray(2).also(root::getLocationOnScreen)
        val rect = Rect(px(at[0] - base[0]), px(at[1] - base[1]), px(source.width), px(source.height))
        val preview = StemCellChatScreen.forRow(context, session, row, previewing = true)
        val snapshot = if (preview == null && source.width > 0 && source.height > 0) {
            android.graphics.Bitmap.createBitmap(source.width, source.height, android.graphics.Bitmap.Config.ARGB_8888).also { source.draw(android.graphics.Canvas(it)) }
        } else null
        val groups = actions.map { group -> group.map { action ->
            val title = when (action) {
                RenameSection -> R.string.stem_rename_section
                RenameTask -> R.string.stem_rename_task
                RenameBot -> R.string.stem_rename_bot
                DeleteSection -> R.string.stem_delete_section
                CloseNewWork -> R.string.stem_close_new_work
                LeaveTask -> R.string.stem_leave_task
                ArchiveTask -> R.string.stem_archive_task
                ArchiveBot -> R.string.stem_archive_bot
                ArchiveSubtask -> R.string.stem_archive_subtask
            }
            val icon = when (action) {
                RenameSection, RenameTask, RenameBot -> "pencil"
                DeleteSection -> "trash"
                CloseNewWork, LeaveTask -> "close"
                else -> "archive"
            }
            StemCellsHoldOverlay.Item(context.getString(title), icon, action in listOf(DeleteSection, CloseNewWork, ArchiveTask, ArchiveBot, ArchiveSubtask)) {
                val store = session.store
                store.attentionInput()
                when (action) {
                    RenameSection -> stemCells.sidebar.beginRename(row.id, row.label) { store.relabel(row.nodeId, it) }
                    RenameTask, RenameBot -> stemCells.sidebar.beginRename(row.id, row.label.takeUnless { it == context.getString(R.string.stem_untitled) } ?: "") { store.rename(row.spaceId!!, it) }
                    DeleteSection -> store.removeSection(row.nodeId)
                    CloseNewWork -> store.discard(row.spaceId!!)
                    LeaveTask -> store.leave(row.targetId!!)
                    ArchiveTask -> store.archive("task", row.targetId!!)
                    ArchiveBot -> store.archive("bot", row.targetId!!)
                    ArchiveSubtask -> store.archiveSubtask(row.agentId!!)
                }
                stack?.backStateChanged()
            }
        } }
        val overlay = StemCellsHoldOverlay(context, rect, groups, preview, snapshot)
        hold = overlay
        overlay.safeArea = safeArea
        overlay.onOpen = { stemCells.open(row) }
        val covered = listOf(stemCells, header).associateWith { it.importantForAccessibility }
        covered.keys.forEach { it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        root.blur(true)
        overlay.onGone = {
            if (hold === overlay) hold = null
            root.blur(false)
            covered.forEach { (view, importance) -> view.importantForAccessibility = importance }
            stack?.backStateChanged()
        }
        root.addView(overlay)
        overlay.setFrame(Rect(0.0, 0.0, px(root.width), px(root.height)))
        overlay.present()
        stack?.backStateChanged()
        return true
    }

    private inner class Root(context: Context) : ManualLayout(context) {
        fun blur(on: Boolean) {
            if (android.os.Build.VERSION.SDK_INT < 31) return
            val effect = if (on) android.graphics.RenderEffect.createBlurEffect(20 * resources.displayMetrics.density, 20 * resources.displayMetrics.density, android.graphics.Shader.TileMode.CLAMP) else null
            stemCells.setRenderEffect(effect)
            header.setRenderEffect(effect)
        }
        init {
            setBackgroundColor(Theme.color(Palette.surface))
            addView(stemCells)
            addView(header)
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean = (hold == null && header.dispatchFromParent(ev)) || super.dispatchTouchEvent(ev)

        override fun layoutSubviews(width: Double, height: Double) {
            val layout = HomeScreenLayout(Size(width, height), safeArea, HomeDockLayout.Metrics.contentInset)
            header.safeTop = safeArea.top
            header.setFrame(layout.header)
            stemCells.safeArea = safeArea
            stemCells.keyboardHeight = keyboard
            stemCells.contentInsets = layout.insets
            stemCells.setFrame(layout.content)
            hold?.let { it.safeArea = safeArea; it.setFrame(Rect(0.0, 0.0, width, height)) }
        }
    }
}
