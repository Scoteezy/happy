package com.slopus.happy.model.settings

import com.slopus.happy.R
import com.slopus.happy.auth.AccountRegistryEntry
import com.slopus.happy.config.AppVariant
import com.slopus.happy.model.Profile
import java.net.URI
import java.net.URISyntaxException

/** What the Settings index shows (components/SettingsView.tsx), worked out from the stored profile (happy-ios `SettingsIndexModel`). */
data class SettingsIndexModel(
    val header: Header,
    /** `profile.connectedServices` includes `anthropic`. */
    val claudeConnected: Boolean,
    /** The GitHub login, when GitHub is connected. */
    val githubLogin: String?,
) {
    sealed interface Header {
        /** The logotype, until the account has a first name. */
        data object Logo : Header

        /** `profile.firstName` is set: the avatar, the display name and the GitHub bio. The thumbhash is the uploaded picture's placeholder. */
        data class Profile(val id: String, val name: String, val bio: String?, val avatarUrl: String?, val thumbhash: String?) : Header
    }

    companion object {
        operator fun invoke(profile: Profile?): SettingsIndexModel {
            // SettingsView.tsx branches on firstName, not on the display name,
            // so an account named only through GitHub keeps the logo.
            val firstName = profile?.firstName
            val header = if (profile != null && !firstName.isNullOrEmpty()) {
                Header.Profile(profile.id, profile.displayName ?: firstName, profile.bio, profile.avatarUrl, profile.avatar?.thumbhash)
            } else {
                Header.Logo
            }
            return SettingsIndexModel(
                header,
                claudeConnected = profile?.connectedServices?.contains("anthropic") ?: false,
                githubLogin = profile?.github?.login,
            )
        }

        /** The Version row's detail. happy-app shows `version / runtime N`; this app has no OTA runtime, so it names the variant outside production. */
        fun versionDetail(version: String, variant: AppVariant): String =
            if (variant == AppVariant.Production) version else "$version / ${variant.name.lowercase()}"
    }
}

/** hooks/useMultiClick.ts: fires on the Nth tap when each tap comes within [resetTimeoutMs] of the one before. */
class MultiClickCounter(
    /** Settings › Version: ten taps, two seconds apart at most. */
    val requiredClicks: Int = 10,
    val resetTimeoutMs: Long = 2_000,
) {
    var count = 0
        private set
    private var lastClick: Long? = null

    /** Counts a tap at [now] (ms); true when it is the one that fires. */
    fun click(now: Long): Boolean {
        val last = lastClick
        if (last != null && now - last >= resetTimeoutMs) count = 0
        lastClick = now
        count += 1
        if (count < requiredClicks) return false
        count = 0
        lastClick = null
        return true
    }
}

/** Settings › Account's profile and connected services (app/(app)/settings/account.tsx). */
data class AccountPageModel(
    val displayName: String?,
    val githubLogin: String?,
    /** The uploaded picture, which the GitHub row shows instead of the logo, and its placeholder. */
    val avatarUrl: String?,
    val avatarThumbhash: String?,
    /** The Profile group shows when there is a name, a GitHub login or a picture. */
    val showsProfile: Boolean,
    /** Known services in the profile's order; unknown ones are left out. */
    val services: List<Service>,
) {
    /** A service account.tsx knows how to show. */
    enum class Service(val wire: String, val title: String, val image: Int) {
        Anthropic("anthropic", "Claude Code", R.drawable.icon_claude),
        Gemini("gemini", "Google Gemini", R.drawable.icon_gemini),
        OpenAI("openai", "OpenAI Codex", R.drawable.icon_gpt);

        /** OpenAI's mark is black and follows the text colour; the others keep their own colours. */
        val tintsWithText: Boolean get() = this == OpenAI
    }

    companion object {
        operator fun invoke(profile: Profile?): AccountPageModel {
            val displayName = profile?.displayName
            val githubLogin = profile?.github?.login
            return AccountPageModel(
                displayName = displayName,
                githubLogin = githubLogin,
                avatarUrl = profile?.avatar?.url,
                avatarThumbhash = profile?.avatar?.thumbhash,
                showsProfile = displayName != null || githubLogin != null || profile?.avatar != null,
                services = profile?.connectedServices.orEmpty().mapNotNull { wire -> Service.entries.firstOrNull { it.wire == wire } },
            )
        }
    }
}

/** One row of Settings › Account's account switcher (happy-ios `AccountSwitcherEntry`). */
data class AccountSwitcherEntry(
    val id: String,
    val title: String,
    val subtitle: String?,
    val avatarUrl: String?,
    val avatarThumbhash: String?,
    val isActive: Boolean,
) {
    companion object {
        /**
         * Entries in added order. The active account's own profile fills in
         * what the registry has not stored yet. The title is the name, else
         * the GitHub login, else [fallbackTitle] with the start of the id; the
         * subtitle adds the login under a name and the server when it is not
         * the default one.
         */
        fun entries(
            accounts: List<AccountRegistryEntry>,
            activeId: String?,
            activeProfile: Profile?,
            defaultServer: String,
            fallbackTitle: String,
        ): List<AccountSwitcherEntry> = accounts.sortedBy { it.addedOrder }.map { account ->
            val isActive = account.id == activeId
            var name = account.display?.name
            var username = account.display?.username
            var avatar = account.display?.avatarUrl
            var thumbhash = account.display?.avatarThumbhash
            if (isActive && activeProfile != null) {
                name = name ?: activeProfile.displayName
                username = username ?: activeProfile.github?.login
                if (avatar == null) {
                    avatar = activeProfile.avatarUrl
                    thumbhash = activeProfile.avatar?.thumbhash
                }
            }
            val title = name ?: username?.let { "@$it" } ?: "$fallbackTitle ${account.id.take(8)}"
            val details = buildList {
                if (name != null && username != null) add("@$username")
                if (account.serverUrl.trimEnd('/') != defaultServer.trimEnd('/')) add(displayHost(account.serverUrl))
            }
            AccountSwitcherEntry(account.id, title, details.joinToString(" • ").ifEmpty { null }, avatar, thumbhash, isActive)
        }

        /** Host and port, as `ServerConfig.displayHost` shows them. */
        fun displayHost(url: String): String = try {
            val uri = URI(url)
            val host = uri.host ?: return url
            if (uri.port >= 0) "$host:${uri.port}" else host
        } catch (_: URISyntaxException) {
            url
        }
    }
}
