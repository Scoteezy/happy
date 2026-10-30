package com.slopus.happy.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import com.slopus.happy.AppGraph
import com.slopus.happy.R
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.GroupedListLayout
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Size
import com.slopus.happy.sync.AccountSync
import com.slopus.happy.troubleshoot.TroubleshootState
import com.slopus.happy.ui.components.*
import com.slopus.happy.ui.machine.MachineScreen
import com.slopus.happy.ui.nav.BarScreen
import com.slopus.happy.ui.text.TextLayouts
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.view.px
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow

/** The native Relay troubleshooting route, bound to the account that opened it. */
class TroubleshootScreen internal constructor(
    context: Context,
    private val snapshots: () -> Flow<TroubleshootState>,
    private val isCurrentAccount: () -> Boolean,
    private val machineScreen: (String) -> com.slopus.happy.ui.nav.Screen,
) : BarScreen(context) {
    constructor(context: Context, graph: AppGraph, account: AccountSync) : this(context,
        { account.troubleshooting.snapshots() }, { graph.account.value === account }, { MachineScreen(context, graph, it) })

    private val list = GroupedListView(context)
    private val guide = TroubleshootGuideView(context)
    private val guideCopy = TroubleshootGuideCopy(context)
    internal val prompt = CopyPromptView(context)
    private var aiPrompt = ""
    private var visible = false
    private var observation: Job? = null
    private var copiedReset: Job? = null
    private var planning: Job? = null
    private var planned: Pair<Double, TextLayouts.Env>? = null

    override fun createContent(): View {
        bar.title = context.getString(R.string.troubleshoot_title)
        guide.onOpenWebsite = { if (visible) Alerts.open(context, "https://happy.engineering") }
        prompt.button.isEnabled = false
        prompt.button.onTap = ::copyPrompt
        list.sections = listOf(guide, prompt)
        list.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> requestPlan() }
        return list
    }

    override fun onAppear() {
        super.onAppear()
        visible = true
        observation?.cancel()
        if (!isCurrentAccount()) { invalidateAccount(); return }
        prompt.button.isEnabled = aiPrompt.isNotEmpty()
        observation = scope.launch {
            snapshots().collect { if (visible && isCurrentAccount()) update(it) else invalidateAccount() }
            // A failed read retains the last applied content; a stopped account cannot copy it.
            if (!isCurrentAccount()) invalidateAccount()
        }
        requestPlan()
    }

    override fun onDisappear() {
        visible = false
        observation?.cancel(); observation = null
        copiedReset?.cancel(); copiedReset = null
        prompt.button.title = context.getString(R.string.troubleshoot_copy_ai_prompt)
        prompt.button.isEnabled = false
        super.onDisappear()
    }

    override fun onInsetsChanged() {
        super.onInsetsChanged()
        list.safeArea = contentSafeArea
        requestPlan()
    }

    private fun requestPlan() {
        if (list.width <= 0) return
        val input = GroupedListLayout.contentWidth(Size(px(list.width), px(list.height)), contentSafeArea) to TextLayouts.Env(Theme.density, Theme.textScale)
        if (input == planned) return
        planned = input
        planning?.cancel()
        planning = scope.launch {
            val plan = withContext(Dispatchers.Default) { TroubleshootGuidePlan.make(guideCopy, input.first, input.second) }
            guide.plan = plan
            list.requestLayout()
        }
    }

    private fun update(state: TroubleshootState) {
        aiPrompt = state.aiPrompt
        prompt.button.isEnabled = aiPrompt.isNotEmpty()
        list.sections = buildList {
            add(guide)
            if (state.computers.isNotEmpty()) add(ItemGroupView(context, context.getString(R.string.troubleshoot_machines), rows = state.computers.map { computer ->
                ItemRowView(context, computer.name, if (computer.online) null else context.getString(R.string.troubleshoot_machine_offline),
                    icon = ItemIcon.Glyph(Icon.ionicons("desktop-outline"), Palette.textSecondary, 28.0)) {
                    if (visible && isCurrentAccount()) push(machineScreen(computer.id))
                }
            }))
            add(prompt)
        }
    }

    private fun invalidateAccount() {
        aiPrompt = ""
        copiedReset?.cancel(); copiedReset = null
        prompt.button.title = context.getString(R.string.troubleshoot_copy_ai_prompt)
        prompt.button.isEnabled = false
        list.sections = listOf(guide, prompt)
    }

    private fun copyPrompt() {
        if (!visible || !isCurrentAccount() || aiPrompt.isEmpty()) return
        try {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(context.getString(R.string.troubleshoot_copy_ai_prompt), aiPrompt))
        } catch (_: Exception) {
            Alerts.show(context, context.getString(R.string.troubleshoot_copy_failed))
            return
        }
        prompt.button.title = context.getString(R.string.troubleshoot_copied)
        copiedReset?.cancel()
        copiedReset = scope.launch {
            delay(2_000)
            prompt.button.title = context.getString(R.string.troubleshoot_copy_ai_prompt)
        }
    }
}