package com.slopus.happy.model.settings

import com.slopus.happy.auth.AccountDisplay
import com.slopus.happy.auth.AccountRegistryEntry
import com.slopus.happy.auth.CredentialLocation
import com.slopus.happy.config.AppVariant
import com.slopus.happy.model.GitHubProfile
import com.slopus.happy.model.ImageRef
import com.slopus.happy.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Ported from happy-ios Tests/HappyCoreTests/SettingsPagesTests.swift.

private fun github(login: String, name: String = "", avatarUrl: String = "", bio: String? = null) =
    GitHubProfile(1.0, login, name, avatarUrl, null, bio)

private fun profile(
    firstName: String? = null,
    lastName: String? = null,
    github: GitHubProfile? = null,
    avatar: ImageRef? = null,
    services: List<String> = emptyList(),
) = Profile("p1", 1, firstName, lastName, avatar, github, services)

/** components/SettingsView.tsx. */
class SettingsIndexModelTest {
    @Test fun theLogoUntilTheAccountHasAFirstName() {
        assertEquals(SettingsIndexModel.Header.Logo, SettingsIndexModel(null).header)
        val githubOnly = profile(github = github("ada", name = "Ada L"))
        assertEquals("a GitHub name alone keeps the logo", SettingsIndexModel.Header.Logo, SettingsIndexModel(githubOnly).header)
        assertEquals(SettingsIndexModel.Header.Logo, SettingsIndexModel(profile(firstName = "")).header)
    }

    @Test fun theProfileHeader() {
        val model = SettingsIndexModel(
            profile(
                firstName = "Ada",
                lastName = "Lovelace",
                github = github("ada", avatarUrl = "https://a/ada.png", bio = "Math"),
                services = listOf("anthropic"),
            ),
        )
        assertEquals(SettingsIndexModel.Header.Profile("p1", "Ada Lovelace", "Math", "https://a/ada.png", null), model.header)
        assertTrue(model.claudeConnected)
        assertEquals("ada", model.githubLogin)
    }

    @Test fun connectionsWithoutAProfile() {
        val model = SettingsIndexModel(profile(services = listOf("openai")))
        assertFalse(model.claudeConnected)
        assertNull(model.githubLogin)
    }

    @Test fun versionDetail() {
        assertEquals("2026.10.1", SettingsIndexModel.versionDetail("2026.10.1", AppVariant.Production))
        assertEquals("2026.10.1 / preview", SettingsIndexModel.versionDetail("2026.10.1", AppVariant.Preview))
    }
}

/** hooks/useMultiClick.ts with Settings › Version's options. */
class MultiClickCounterTest {
    @Test fun theTenthTapFiresAndStartsOver() {
        val counter = MultiClickCounter()
        for (second in 0 until 9) assertFalse(counter.click(second * 1000L))
        assertTrue(counter.click(9_000))
        assertEquals(0, counter.count)
        assertFalse(counter.click(10_000))
    }

    @Test fun aPauseOfTwoSecondsResetsTheCount() {
        val counter = MultiClickCounter()
        for (second in 0 until 5) counter.click(second * 1000L)
        assertFalse(counter.click(6_000))
        assertEquals(1, counter.count)
        for (tap in 1 until 9) assertFalse(counter.click(6_000 + tap * 500L))
        assertTrue(counter.click(11_000))
    }
}

/** app/(app)/settings/account.tsx. */
class AccountPageModelTest {
    @Test fun profileGroupAndKnownServices() {
        assertFalse(AccountPageModel(null).showsProfile)
        val page = AccountPageModel(profile(github = github("ada"), services = listOf("openai", "somethingelse", "anthropic")))
        assertTrue(page.showsProfile)
        assertEquals("ada", page.displayName)
        assertEquals("ada", page.githubLogin)
        assertEquals("unknown services are left out, order kept", listOf(AccountPageModel.Service.OpenAI, AccountPageModel.Service.Anthropic), page.services)
        assertEquals(listOf("OpenAI Codex", "Claude Code"), page.services.map { it.title })
        assertEquals(listOf(true, false), page.services.map { it.tintsWithText })
    }

    @Test fun aPictureAloneShowsTheProfileGroup() {
        val avatar = ImageRef(1.0, 1.0, "HwgK", "p", "https://a/p.png")
        val page = AccountPageModel(profile(avatar = avatar))
        assertTrue(page.showsProfile)
        assertNull(page.displayName)
        assertEquals("https://a/p.png", page.avatarUrl)
        assertEquals("HwgK", page.avatarThumbhash)
    }
}

class AccountSwitcherEntryTest {
    @Test fun titlesSubtitlesAndTheActiveProfile() {
        val base = "https://api.cluster-fluster.com"
        val accounts = listOf(
            AccountRegistryEntry("aaaaaaaaaaaaaaaa", base, CredentialLocation.Shared, 0),
            AccountRegistryEntry("bbbbbbbbbbbbbbbb", "http://10.0.0.2:3005", CredentialLocation.Own, 1, AccountDisplay(name = "Bea", username = "bea")),
            AccountRegistryEntry("cccccccccccccccc", base, CredentialLocation.Own, 2, AccountDisplay(username = "cee")),
            AccountRegistryEntry("0123abcdef012345", "https://happy.example.com", CredentialLocation.Own, 3),
        )
        val active = profile(firstName = "Ada", github = github("ada", avatarUrl = "https://a/ada.png"))
        val entries = AccountSwitcherEntry.entries(accounts, "aaaaaaaaaaaaaaaa", active, base, "Account")
        assertEquals(listOf("Ada", "Bea", "@cee", "Account 0123abcd"), entries.map { it.title })
        assertEquals(listOf("@ada", "@bea • 10.0.0.2:3005", null, "happy.example.com"), entries.map { it.subtitle })
        assertEquals("https://a/ada.png", entries[0].avatarUrl)
        assertNull("another account's profile is not this one's", entries[1].avatarUrl)
        assertEquals(listOf(true, false, false, false), entries.map { it.isActive })
    }
}
