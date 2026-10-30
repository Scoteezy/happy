package com.slopus.happy.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slopus.happy.R
import com.slopus.happy.troubleshoot.TroubleshootRow
import com.slopus.happy.troubleshoot.TroubleshootState
import com.slopus.happy.ui.components.ItemRowView
import com.slopus.happy.ui.nav.Screen
import com.slopus.happy.ui.text.TextLayouts
import com.slopus.happy.ui.theme.Theme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class TroubleshootScreenTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun children(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(children(view.getChildAt(index)))
    }
    private fun machine() = object : Screen(context) { override fun createView() = View(context) }
    private val initial = TroubleshootState(listOf(TroubleshootRow("happy", "Laptop", false)), "In /fixture/.happy, diagnose why Happy cannot reach \"Laptop\" for project \"Project\".")

    @Test fun copiesCurrentPromptAndResetsFeedbackAfterExactlyTwoSeconds() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        Theme.install(context)
        val source = MutableStateFlow(initial)
        val screen = TroubleshootScreen(context, { source }, { true }, { machine() })
        try {
            screen.view
            screen.onAppear(); runCurrent()
            screen.prompt.button.performClick()
            assertEquals(initial.aiPrompt, context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
            assertEquals(context.getString(R.string.troubleshoot_copied), screen.prompt.button.title)
            advanceTimeBy(1999); runCurrent()
            assertEquals(context.getString(R.string.troubleshoot_copied), screen.prompt.button.title)
            advanceTimeBy(1); runCurrent()
            assertEquals(context.getString(R.string.troubleshoot_copy_ai_prompt), screen.prompt.button.title)
            source.value = initial.copy(aiPrompt = "new prompt")
            runCurrent(); screen.prompt.button.performClick()
            assertEquals("new prompt", context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        } finally { screen.onDisappear(); screen.destroy(); Dispatchers.resetMain() }
    }

    @Test fun observesOnlyWhileVisibleAndStaleAccountCannotCopyOrOpenMachine() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        Theme.install(context)
        var current = true
        var active = 0
        val opened = mutableListOf<String>()
        val source = MutableStateFlow(initial)
        val screen = TroubleshootScreen(context, { source.onStart { active++ }.onCompletion { active-- } }, { current }, { opened += it; machine() })
        try {
            screen.view
            assertFalse(screen.prompt.button.isEnabled)
            screen.onAppear(); runCurrent()
            assertEquals(1, active)
            val row = children(screen.view).filterIsInstance<ItemRowView>().single()
            assertEquals("Laptop", row.title)
            assertEquals(context.getString(R.string.troubleshoot_machine_offline), row.subtitle)
            row.performClick()
            assertEquals(listOf("happy"), opened)
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("sentinel", "unchanged"))
            current = false
            row.performClick(); screen.prompt.button.performClick()
            assertEquals(listOf("happy"), opened)
            assertEquals("unchanged", clipboard.primaryClip!!.getItemAt(0).text)
            source.value = initial.copy(aiPrompt = "other account")
            runCurrent()
            assertFalse(screen.prompt.button.isEnabled)
            assertFalse(children(screen.view).any { it is ItemRowView })
            screen.onDisappear(); runCurrent()
            assertEquals(0, active)
            screen.onAppear(); runCurrent()
            assertEquals(0, active)
            current = true
            screen.onAppear(); runCurrent()
            assertEquals(1, active)
        } finally { screen.onDisappear(); screen.destroy(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun emptyAccountGetsGenericPromptWithoutMachineGroupAndDisappearanceCancelsFeedback() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        Theme.install(context)
        val screen = TroubleshootScreen(context, { flowOf(TroubleshootState(emptyList(), "generic prompt")) }, { true }, { machine() })
        try {
            screen.view; screen.onAppear(); runCurrent()
            assertFalse(children(screen.view).any { it is ItemRowView })
            screen.prompt.button.performClick(); runCurrent()
            screen.onDisappear()
            assertFalse(screen.prompt.button.isEnabled)
            assertEquals(context.getString(R.string.troubleshoot_copy_ai_prompt), screen.prompt.button.title)
            advanceTimeBy(3000); runCurrent()
            assertEquals(context.getString(R.string.troubleshoot_copy_ai_prompt), screen.prompt.button.title)
        } finally { screen.destroy(); Dispatchers.resetMain() }
    }

    @Test fun guideUsesNativeCommandsCommentAndAllThreeChecksAtPhoneAndTabletWidths() {
        Theme.install(context)
        val copy = TroubleshootGuideCopy(context)
        for (width in listOf(320.0, 393.0, 800.0)) {
            val plan = TroubleshootGuidePlan.make(copy, width, TextLayouts.Env(Theme.density, Theme.textScale))
            assertEquals(3, plan.layout.steps.size)
            assertEquals(context.getString(R.string.troubleshoot_terminal_comment), plan.terminal[0].string)
            assertEquals("$ " + context.getString(R.string.onboarding_terminal_install), plan.terminal[1].string)
            assertEquals("$ " + context.getString(R.string.onboarding_terminal_run), plan.terminal[2].string)
            assertTrue(plan.layout.steps.last().terminal!!.maxY < plan.layout.size.height)
            assertTrue(plan.terminalLayout.lines.all { it.maxX <= plan.terminalLayout.size.width })
        }
    }

    @Test fun terminalSelectionAndCopyNeverIncludeTheShellPrompt() {
        Theme.install(context)
        val plan = TroubleshootGuidePlan.make(TroubleshootGuideCopy(context), 393.0, TextLayouts.Env(Theme.density, Theme.textScale))
        val line = SelectableTerminalLine(context)
        line.bind(plan.terminal[2], plan.terminalLayout.lines[2], 2)
        assertTrue(line.isTextSelectable)
        android.text.Selection.setSelection(line.text as android.text.Spannable, 0, line.text.length)
        assertEquals(2, line.selectionStart)
        assertTrue(line.onTextContextMenuItem(android.R.id.copy))
        val expected = context.getString(R.string.onboarding_terminal_run)
        assertEquals(expected, context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        assertEquals(expected, line.contentDescription)
        line.bind(plan.terminal[0], plan.terminalLayout.lines[0], 0)
        android.text.Selection.setSelection(line.text as android.text.Spannable, 0, line.text.length)
        assertEquals(0, line.selectionStart)
    }

    @Test fun failedReadOnReturnKeepsTheSameAccountsLastValidPromptUsable() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        Theme.install(context)
        var first = true
        val screen = TroubleshootScreen(context, { if (first) flowOf(initial) else emptyFlow() }, { true }, { machine() })
        try {
            screen.view; screen.onAppear(); runCurrent()
            screen.onDisappear(); first = false
            screen.onAppear(); runCurrent()
            assertTrue(screen.prompt.button.isEnabled)
            screen.prompt.button.performClick()
            assertEquals(initial.aiPrompt, context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        } finally { screen.onDisappear(); screen.destroy(); Dispatchers.resetMain() }
    }
}