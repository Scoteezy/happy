package com.slopus.happy.nav

/**
 * Every screen a signed-in account can push, after happy-ios `Route.swift`
 * (happy-app's `app/(app)` routes). Screens open each other through
 * `push(route)`, never by naming another feature's screen class.
 */
sealed interface Route {
    // Chat (session/[id]/…)
    data class Session(val id: String) : Route
    data class SessionInfo(val id: String) : Route
    data class SessionChanges(val id: String, val file: String? = null) : Route
    data class SessionFile(val id: String, val path: String, val line: Int? = null, val column: Int? = null) : Route
    /** [file]: of a patch's files, the one to show alone. */
    data class SessionMessage(val id: String, val messageId: String, val file: String? = null) : Route
    data class TextSelection(val text: String) : Route

    // Starting work
    data class NewSession(val machineId: String? = null, val path: String? = null, val prompt: String? = null) : Route
    data class Machine(val id: String) : Route

    // Settings
    data object Settings : Route
    data object SettingsAccount : Route
    data object SettingsAppearance : Route
    data object SettingsLanguage : Route
    data object SettingsAgents : Route
    data object SettingsConnectClaude : Route
    data object SettingsVoice : Route
    data object SettingsVoiceLanguage : Route
    data object SupportUs : Route
    data object Changelog : Route
    data object Server : Route
    data object Troubleshoot : Route

    // Signed out
    data object Restore : Route
    data object RestoreManual : Route
    data object OnboardingSettings : Route
}
