package com.slopus.happy.model

import com.slopus.happy.home.HomeListState

/**
 * What the tablet sidebar shows, from the same home list the phone home
 * reads (happy-ios `TabletSidebarState`): SidebarView.tsx's top controls and
 * MainView.tsx's sidebar variant.
 */
data class TabletSidebarState(
    val content: Content,
    /** `useHasArchivedSessions`: the archive button shows. */
    val hasArchived: Boolean,
    /** `hideInactiveSessions`: the button reads "Show archived". */
    val hidesArchived: Boolean,
    /**
     * The first-run screen owns the window and the shell steps aside
     * (`shouldShowFirstRunInstall`). It follows home's own decision, so the
     * sidebar disappears exactly when home shows Link Your Computer.
     */
    val showsInstallStep: Boolean,
) {
    sealed interface Content {
        /** The spinner, before machines are known. */
        data object Loading : Content

        /**
         * Nothing listed: EmptySessionsTablet. An archive alone lists nothing
         * here; the archive button above opens it.
         */
        data class Empty(val sessions: TabletEmptySessions) : Content

        /** The session list, as home derives it. */
        data class List(val state: HomeListState) : Content
    }

    companion object {
        /** [state] is null until the first read is in. */
        fun of(state: HomeListState?): TabletSidebarState {
            if (state == null) return TabletSidebarState(Content.Loading, hasArchived = false, hidesArchived = true, showsInstallStep = false)
            val content = if (state.listsSessions) Content.List(state) else Content.Empty(TabletEmptySessions.of(state.computers, state.onlineComputers))
            return TabletSidebarState(content, state.hasArchivedSessions, state.archiveHidden, state.showsInstallStep)
        }
    }
}

/** EmptySessionsTablet.tsx's three faces. */
sealed interface TabletEmptySessions {
    /** A computer is reachable: "No sessions yet", and Start New Session. */
    data object Online : TabletEmptySessions

    /** Computers, none reachable: "<name> is unreachable" for one, "No machines are reachable" for more, and Troubleshoot. */
    data class Offline(val names: List<String>) : TabletEmptySessions

    /** No computer: "No sessions yet" and how to connect one, no button. */
    data object NoMachines : TabletEmptySessions

    companion object {
        fun of(computers: List<String>, online: Int): TabletEmptySessions = when {
            online > 0 -> Online
            computers.isEmpty() -> NoMachines
            else -> Offline(computers)
        }
    }
}
