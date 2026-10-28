package com.slopus.happy.ui.home

import android.content.Context
import android.view.View
import com.slopus.happy.AppGraph
import com.slopus.happy.R
import com.slopus.happy.ui.components.GroupedListView
import com.slopus.happy.ui.components.ItemGroupView
import com.slopus.happy.ui.components.ItemRowView
import com.slopus.happy.ui.nav.BarScreen

/** Signed in: the session list (happy-ios `HomeViewController`). Being ported; for now it signs out. */
class HomeScreen(context: Context, private val graph: AppGraph) : BarScreen(context) {
    private val list = GroupedListView(context)

    override fun createContent(): View {
        bar.title = context.getString(R.string.common_home)
        list.sections = listOf(
            ItemGroupView(context, rows = listOf(ItemRowView(context, context.getString(R.string.common_logout), isDestructive = true) {
                graph.logout()
            })),
        )
        return list
    }

    override fun onInsetsChanged() {
        super.onInsetsChanged()
        list.safeArea = contentSafeArea
    }
}
