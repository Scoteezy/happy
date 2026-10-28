package com.slopus.happy.chat

import com.slopus.happy.home.resumeAvailability
import com.slopus.happy.model.AgentGoalAction
import com.slopus.happy.model.Machine
import com.slopus.happy.model.RigActivityIndicator
import com.slopus.happy.model.Session
import com.slopus.happy.model.isRig
import com.slopus.happy.model.resumeCommandBlock

// What the bars between the conversation and the composer say (happy-ios
// HappyCore/ChatDisplay/ChatDockBars.swift): InactiveArchivedHint, the goal
// bar's actions and RigActivityBar's counts.

/**
 * SessionView's InactiveArchivedHint, above the composer of a session that
 * has lost its agent (archived, or its CLI stopped): "This session is
 * inactive.", then Resume when it can be resumed from here, or else the
 * commands that resume it from a terminal. Not for Happy Agent.
 */
data class InactiveSessionHint(val action: Action) {
    sealed interface Action {
        data object Resume : Action

        /** resumeCommand.ts's block, one command a line. */
        data class Command(val lines: List<String>) : Action

        /** Neither: the notice alone. */
        data object NoticeOnly : Action
    }

    /** What the command block copies: its lines, one a line. */
    val copyText: String? get() = (action as? Action.Command)?.lines?.joinToString("\n")

    companion object {
        fun of(session: Session?, online: Boolean, machine: Machine?): InactiveSessionHint? {
            if (session == null || online || session.metadata.isRig()) return null
            if (resumeAvailability(session, machine).canResume) return InactiveSessionHint(Action.Resume)
            val block = session.metadata.resumeCommandBlock() ?: return InactiveSessionHint(Action.NoticeOnly)
            return InactiveSessionHint(Action.Command(block.lines))
        }
    }
}

/**
 * agentGoalActionHandler.ts: what a tapped goal button sends, if anything.
 * Stop sends nothing yet; Edit sends only a new, different objective
 * ([edited] is what the prompt returned, null when cancelled).
 */
fun goalDispatch(action: AgentGoalAction, current: String, edited: String?): Pair<AgentGoalAction, String?>? = when (action) {
    AgentGoalAction.Stop -> null
    AgentGoalAction.Clear -> AgentGoalAction.Clear to null
    AgentGoalAction.Edit -> edited?.trim()?.takeIf { it.isNotEmpty() && it != current.trim() }?.let { AgentGoalAction.Edit to it }
}

/** RigActivityBar's word for an indicator's kind, and its glyph. */
enum class RigActivityKind(val key: String, val icon: String) {
    Subagents("subagents", "people-outline"),
    Workflows("workflows", "git-network-outline"),
    Processes("processes", "terminal-outline"),
    Tasks("tasks", "checkbox-outline"),
    ;

    companion object {
        fun of(indicator: RigActivityIndicator): RigActivityKind? = entries.find { it.key == indicator.key }
    }
}
