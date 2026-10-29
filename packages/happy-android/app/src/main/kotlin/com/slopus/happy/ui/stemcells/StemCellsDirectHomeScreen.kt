package com.slopus.happy.ui.stemcells

import android.content.Context
import android.view.MotionEvent
import android.view.View
import com.slopus.happy.R
import com.slopus.happy.layout.HomeDockLayout
import com.slopus.happy.layout.HomeScreenLayout
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Size
import com.slopus.happy.net.ConnectionStatus
import com.slopus.happy.stemcells.StemCellCreation
import com.slopus.happy.stemcells.StemCellsRow
import com.slopus.happy.ui.home.HomeHeaderView
import com.slopus.happy.ui.nav.Screen
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.setFrame

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
) : Screen(context) {
    val header = HomeHeaderView(context)
    val stemCells = StemCellsHomeView(context)
    var open: ((StemCellsRow) -> Unit)? = null
    var newWork: ((StemCellCreation, Boolean) -> Unit)? = null
    var openSettings: (() -> Unit)? = null
    var openSearch: (() -> Unit)? = null

    override fun createView(): View = Root(context)

    override fun viewCreated(view: View) {
        header.stemCellsActions = true
        header.title = computerName
        header.searchButton.tag = "stem-search"
        header.searchButton.setOnClickListener { openSearch?.invoke() }
        header.logoControl.contentDescription = context.getString(R.string.stem_open_settings)
        header.logoControl.tag = "stem-settings-mark"
        header.logoControl.setOnClickListener { openSettings?.invoke() }
        stemCells.onSnapshot = { snapshot -> header.status = if (snapshot.live) ConnectionStatus.CONNECTED else ConnectionStatus.CONNECTING }
        stemCells.onOpen = { row ->
            open?.invoke(row)
            StemCellChatScreen.forRow(context, session, row)?.let { push(it) }
        }
        stemCells.onNewWork = { creation, library ->
            newWork?.invoke(creation, library)
            push(StemCellChatScreen.forNewWork(context, session, creation))
        }
        stemCells.attach(session)
    }

    override fun onAppear() {
        stemCells.appear()
    }

    override fun onDisappear() {
        stemCells.disappear()
    }

    override fun onDestroy() {
        stemCells.close()
    }

    private inner class Root(context: Context) : ManualLayout(context) {
        init {
            setBackgroundColor(Theme.color(Palette.surface))
            addView(stemCells)
            addView(header)
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean = header.dispatchFromParent(ev) || super.dispatchTouchEvent(ev)

        override fun layoutSubviews(width: Double, height: Double) {
            val layout = HomeScreenLayout(Size(width, height), safeArea, HomeDockLayout.Metrics.contentInset)
            header.safeTop = safeArea.top
            header.setFrame(layout.header)
            stemCells.safeArea = safeArea
            stemCells.contentInsets = layout.insets
            stemCells.setFrame(layout.content)
        }
    }
}
