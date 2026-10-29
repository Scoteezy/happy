package com.slopus.happy.model

import com.slopus.happy.home.HomeListInputs
import com.slopus.happy.home.HomeSnapshot
import com.slopus.happy.home.deriveHomeList
import com.slopus.happy.model.settings.LocalSettingsValues
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * What the tablet sidebar shows: SidebarView.tsx's archive button,
 * MainView.tsx's sidebar variant and EmptySessionsTablet.tsx's faces
 * (happy-ios TabletSidebarStateTests).
 */
class TabletSidebarStateTest {
    private fun machine(id: String, active: Boolean, name: String? = null) = HomeModelFixtures.machine(
        id,
        metadata = HomeModelFixtures.machineMetadata(host = id) { name?.let { put("displayName", it) } },
        active = active,
    )

    /** Live while [active]; a Happy CLI session that is not active is archived. */
    private fun session(id: String, active: Boolean) = HomeModelFixtures.session(id, active = active)

    private fun state(machines: List<Machine>, sessions: List<Session> = emptyList(), hideArchived: Boolean = true): TabletSidebarState {
        val inputs = HomeListInputs(HomeSnapshot(sessions, machines), SessionListGrouping.Flat, hideArchived, emptySet(), emptySet(), emptySet())
        return TabletSidebarState.of(deriveHomeList(inputs, ZoneOffset.UTC, LocalDate.of(2026, 1, 2)))
    }

    private val TabletSidebarState.sessionIds: List<String>
        get() = ((content as TabletSidebarState.Content.List).state.rows.mapNotNull { (it as? com.slopus.happy.home.HomeRow.Chat)?.row?.session?.id })

    @Test fun aSpinnerUntilMachinesAreKnown() {
        val sidebar = TabletSidebarState.of(null)
        assertEquals(TabletSidebarState.Content.Loading, sidebar.content)
        assertFalse(sidebar.showsInstallStep)
    }

    @Test fun noComputerIsTheInstallStepTheShellStepsAsideFor() {
        val sidebar = state(emptyList())
        assertTrue(sidebar.showsInstallStep)
        assertEquals(TabletSidebarState.Content.Empty(TabletEmptySessions.NoMachines), sidebar.content)
    }

    @Test fun liveSessionsList() {
        val sidebar = state(listOf(machine("m", active = true)), listOf(session("s", active = true)))
        assertEquals(listOf("s"), sidebar.sessionIds)
        assertFalse(sidebar.hasArchived)
        assertFalse(sidebar.showsInstallStep)
    }

    @Test fun theOfflinePlaqueRidesOnTheList() {
        val sidebar = state(listOf(machine("m", active = false)), listOf(session("s", active = true)))
        val list = (sidebar.content as TabletSidebarState.Content.List).state
        // One linked computer: the plaque names it (its host, with no display name).
        assertEquals("m", list.offlineBanner?.machineName)
    }

    @Test fun aHiddenArchiveListsNothingButOffersTheButton() {
        // Phone home lists the archive toggle here; the sidebar shows
        // EmptySessionsTablet and the archive button above it.
        val sidebar = state(listOf(machine("m", active = true)), listOf(session("old", active = false)))
        assertEquals(TabletSidebarState.Content.Empty(TabletEmptySessions.Online), sidebar.content)
        assertTrue(sidebar.hasArchived)
        assertTrue(sidebar.hidesArchived)
    }

    @Test fun aShownArchiveIsListed() {
        val sidebar = state(listOf(machine("m", active = true)), listOf(session("old", active = false)), hideArchived = false)
        assertEquals(listOf("old"), sidebar.sessionIds)
        assertFalse(sidebar.hidesArchived)
    }

    @Test fun everyComputerOfflineWithNothingListed() {
        val one = state(listOf(machine("m", active = false, name = "Studio")))
        assertEquals(TabletSidebarState.Content.Empty(TabletEmptySessions.Offline(listOf("Studio"))), one.content)
        val two = state(listOf(machine("a", active = false), machine("b", active = false)))
        assertEquals(2, ((two.content as TabletSidebarState.Content.Empty).sessions as TabletEmptySessions.Offline).names.size)
    }

    @Test fun aReachableComputerWithNoSessions() {
        assertEquals(TabletSidebarState.Content.Empty(TabletEmptySessions.Online), state(listOf(machine("m", active = true))).content)
    }

    @Test fun emptyFacesFromMachineChoices() {
        assertEquals(TabletEmptySessions.NoMachines, TabletEmptySessions.of(emptyList(), 0))
        assertEquals(TabletEmptySessions.Online, TabletEmptySessions.of(listOf("a", "b"), 1))
    }

    /** localSettings.ts `zenMode`: off until the zen control turns it on, and device-only. */
    @Test fun zenModeRoundTrips() {
        assertFalse(LocalSettingsValues().zenMode)
        val on = LocalSettingsValues(zenMode = true)
        assertTrue(LocalSettingsValues.parse(on.toJson()).zenMode)
    }
}
