package com.slopus.happy.chat

import com.slopus.happy.model.SessionMetadata
import com.slopus.happy.model.SessionState
import com.slopus.happy.model.isRig
import com.slopus.happy.model.isRigV1
import com.slopus.happy.model.settings.ModeOptions
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.floor

// What SessionView.tsx and AgentInput.tsx decide about the chat's composer
// (happy-ios HappyCore/Composer/SessionComposerRules.swift and
// AgentInputPrimaryAction.swift): Stop, a refused send, attachments, the
// status row and the three chips.

object SessionComposerRules {
    /** rig.ts `rigCanAbort`: Happy Agent sessions must advertise it. */
    fun canAbortAgent(metadata: SessionMetadata?): Boolean {
        if (!metadata.isRigV1()) return true
        val capabilities = metadata?.capabilities ?: return false
        return capabilities.abort && "abort" in capabilities.rpcMethods
    }

    /** `blockSend`: a Happy Agent that refuses steering while it thinks. */
    fun isSendBlocked(state: ChatState): Boolean {
        val metadata = state.session?.metadata ?: return false
        if (!metadata.isRig() || !state.thinking) return false
        return metadata.capabilities?.steering != true
    }

    /** `showAbortButton`: the agent is working, or parked on a permission or a question, and it can be stopped. */
    fun showsAbortButton(state: ChatState): Boolean {
        val session = state.session ?: return false
        if (!canAbortAgent(session.metadata)) return false
        return when (state.sessionState) {
            SessionState.Thinking, SessionState.PermissionRequired, SessionState.InputRequired -> true
            SessionState.Waiting, SessionState.Disconnected -> false
        }
    }

    /** `onAbort` is set: there is a session, it is connected, and it can be stopped. */
    fun canAbort(state: ChatState): Boolean {
        val session = state.session ?: return false
        return state.sessionState != SessionState.Disconnected && canAbortAgent(session.metadata)
    }

    /** `canUseAttachments`: Happy Agent sessions by capability, the others by flavor (none, Claude or Codex). */
    fun canUseAttachments(state: ChatState): Boolean {
        val session = state.session ?: return false
        val metadata = session.metadata
        if (metadata.isRigV1()) return metadata?.capabilities?.attachmentsEnabled == true
        val flavor = metadata?.flavor.orEmpty()
        return flavor.isEmpty() || flavor == "claude" || flavor == "codex"
    }
}

/** agentInputPrimaryAction.ts: what the composer's one round button does. */
enum class AgentInputPrimaryAction {
    Send, Stop, Blocked, Voice, Idle;

    companion object {
        /**
         * `resolveAgentInputPrimaryAction`: a blank composer while the agent
         * works is Stop; with a follow-up, sending wins. A blocked send never
         * hides Stop.
         */
        fun resolve(
            hasComposerContent: Boolean,
            isSendBlocked: Boolean,
            isSendDisabled: Boolean,
            showAbortButton: Boolean,
            canAbort: Boolean,
            canVoice: Boolean = false,
        ): AgentInputPrimaryAction = when {
            showAbortButton && canAbort && !hasComposerContent -> Stop
            isSendBlocked && hasComposerContent -> Blocked
            !isSendDisabled && hasComposerContent -> Send
            !isSendDisabled && canVoice -> Voice
            else -> Idle
        }
    }
}

/** useSessionStatus: the status row above the card. */
data class ComposerStatus(val state: SessionState, val text: Text, val tone: Tone, val isPulsing: Boolean) {
    sealed interface Text {
        data class LastSeen(val lastSeen: ComposerStatus.LastSeen) : Text
        data object PermissionRequired : Text
        data object InputRequired : Text
        /** A vibing word, lowercased, with "…". */
        data class Thinking(val word: String) : Text
        data object Online : Text
    }

    /** sessionUtils.ts `formatLastSeen`. */
    sealed interface LastSeen {
        data object JustNow : LastSeen
        data class Minutes(val count: Int) : LastSeen
        data class Hours(val count: Int) : LastSeen
        data class Days(val count: Int) : LastSeen
        /** A week or more: the date (milliseconds). */
        data class Date(val ms: Long) : LastSeen
    }

    /** #999 offline, #FF9500 waiting on the person, #007AFF thinking, #34C759 online. */
    enum class Tone { Disconnected, Attention, Thinking, Online }

    companion object {
        fun of(state: SessionState, activeAt: Long, now: Long, vibingWord: String): ComposerStatus = when (state) {
            SessionState.Disconnected -> ComposerStatus(state, Text.LastSeen(lastSeen(activeAt, now)), Tone.Disconnected, false)
            SessionState.PermissionRequired -> ComposerStatus(state, Text.PermissionRequired, Tone.Attention, true)
            SessionState.InputRequired -> ComposerStatus(state, Text.InputRequired, Tone.Attention, true)
            SessionState.Thinking -> ComposerStatus(state, Text.Thinking(vibingWord.lowercase() + "…"), Tone.Thinking, true)
            SessionState.Waiting -> ComposerStatus(state, Text.Online, Tone.Online, false)
        }

        fun lastSeen(activeAt: Long, now: Long): LastSeen {
            val seconds = floor((now - activeAt) / 1000.0).toLong()
            val minutes = floor(seconds / 60.0).toLong()
            val hours = floor(minutes / 60.0).toLong()
            val days = floor(hours / 24.0).toLong()
            return when {
                seconds < 60 -> LastSeen.JustNow
                minutes < 60 -> LastSeen.Minutes(minutes.toInt())
                hours < 24 -> LastSeen.Hours(hours.toInt())
                days < 7 -> LastSeen.Days(days.toInt())
                else -> LastSeen.Date(activeAt)
            }
        }

        fun randomVibingWord(): String = vibingMessages.random()

        /** sessionUtils.ts `vibingMessages`. */
        val vibingMessages = listOf(
            "Accomplishing", "Actioning", "Actualizing", "Baking", "Booping", "Brewing", "Calculating", "Cerebrating",
            "Channelling", "Churning", "Clauding", "Coalescing", "Cogitating", "Computing", "Combobulating", "Concocting",
            "Conjuring", "Considering", "Contemplating", "Cooking", "Crafting", "Creating", "Crunching", "Deciphering",
            "Deliberating", "Determining", "Discombobulating", "Divining", "Doing", "Effecting", "Elucidating",
            "Enchanting", "Envisioning", "Finagling", "Flibbertigibbeting", "Forging", "Forming", "Frolicking",
            "Generating", "Germinating", "Hatching", "Herding", "Honking", "Ideating", "Imagining", "Incubating",
            "Inferring", "Manifesting", "Marinating", "Meandering", "Moseying", "Mulling", "Mustering", "Musing",
            "Noodling", "Percolating", "Perusing", "Philosophising", "Pontificating", "Pondering", "Processing",
            "Puttering", "Puzzling", "Reticulating", "Ruminating", "Scheming", "Schlepping", "Shimmying", "Simmering",
            "Smooshing", "Spelunking", "Spinning", "Stewing", "Sussing", "Synthesizing", "Thinking", "Tinkering",
            "Transmuting", "Unfurling", "Unravelling", "Vibing", "Wandering", "Whirring", "Wibbling", "Wizarding",
            "Working", "Wrangling",
        )
    }
}

/** The composer's three chips and their menus (AgentInput.tsx `permissionSettingsGroups` and `modelSettingsGroups`). */
data class ComposerChipSet(val permission: Chip?, val model: Chip, val effort: Chip?) {
    data class Option(val key: String, val label: String, val isDisabled: Boolean)

    data class Section(val title: String?, val options: List<Option>)

    data class Chip(
        /** Null draws the permission chip's shield glyph. */
        val label: String?,
        val accessibilityLabel: String,
        /** Null: the chip shows but can't be opened. */
        val sections: List<Section>?,
        val selectedKey: String?,
    )

    /** The copy the chips fall back to (en.ts `agentInput.*.title`). */
    data class Titles(val permission: String, val codexPermission: String, val geminiPermission: String, val model: String, val effort: String)

    companion object {
        const val MODEL_LABEL_MAX_LENGTH = 16

        /** [session] null is a chat that doesn't exist yet: nothing can be picked. */
        fun of(modes: ComposerModes, session: ChatSessionInfo?, titles: Titles): ComposerChipSet {
            val metadata = session?.metadata
            val flavor = metadata?.flavor
            val isRig = metadata.isRig()
            val canPickPermission = session != null && metadata.rigPermissionSelectionEnabled()
            val canPickModel = session != null && metadata.rigModelSelectionEnabled()
            val canPickEffort = session != null && metadata.rigReasoningSelectionEnabled()

            val current = modes.permissionMode?.let(ModeOptions::hackMode)
            val availableModes = ModeOptions.hackModes(modes.availableModes)
            val permissionTitle = when {
                !isRig && flavor == "codex" -> titles.codexPermission
                flavor == "gemini" -> titles.geminiPermission
                else -> titles.permission
            }
            val permission = if (canPickPermission && availableModes.isNotEmpty()) {
                val sandboxed = isSandboxEnabled(metadata?.sandbox)
                Chip(
                    label = ModeOptions.permissionModeShortLabel(current),
                    accessibilityLabel = permissionTitle,
                    sections = listOf(
                        Section(null, availableModes.map { mode ->
                            Option(mode.key, withSandboxSuffix(mode.name, mode.key, sandboxed), mode.disabled)
                        }),
                    ),
                    selectedKey = current?.key ?: "default",
                )
            } else {
                null
            }

            val modelName = (modes.modelMode?.name ?: titles.model).trim()
            val canOpenModels = canPickModel && modes.availableModels.isNotEmpty()
            val model = Chip(
                label = truncateModelLabel(modelName),
                accessibilityLabel = titles.model,
                sections = if (canOpenModels) {
                    ModeOptions.groupModelsByProvider(modes.availableModels).map { group ->
                        Section(group.title ?: titles.model, group.models.map { Option(it.key, it.name, it.disabled) })
                    }
                } else {
                    null
                },
                selectedKey = modes.modelMode?.key,
            )

            val effort = if (canPickEffort && modes.availableEffortLevels.isNotEmpty()) {
                Chip(
                    label = modes.effortLevel?.name ?: titles.effort,
                    accessibilityLabel = titles.effort,
                    sections = listOf(Section(null, modes.availableEffortLevels.map { Option(it.key, it.name, it.disabled) })),
                    selectedKey = modes.effortLevel?.key,
                )
            } else {
                null
            }
            return ComposerChipSet(permission, model, effort)
        }

        /** ModelModeOptions `truncateModelLabel`: cut at a word within 8 of the limit, else at the limit, with "…". */
        fun truncateModelLabel(label: String, max: Int = MODEL_LABEL_MAX_LENGTH): String {
            val trimmed = label.trim()
            if (trimmed.length <= max) return trimmed
            val cut = trimmed.take(max)
            val space = cut.lastIndexOf(' ')
            val head = if (space >= 0 && space >= max - 8) cut.substring(0, space) else cut.trimEnd()
            return "$head…"
        }

        /** AgentInput.tsx `isSandboxEnabled`: `metadata.sandbox` is set, and when it is an object with `enabled`, that is true. */
        fun isSandboxEnabled(sandbox: kotlinx.serialization.json.JsonElement?): Boolean {
            if (sandbox == null || !isTruthy(sandbox)) return false
            val enabled = (sandbox as? JsonObject)?.get("enabled") ?: return true
            return isTruthy(enabled)
        }

        /** AgentInput.tsx `withSandboxSuffix`. */
        fun withSandboxSuffix(label: String, modeKey: String?, sandboxEnabled: Boolean): String =
            if (sandboxEnabled && (modeKey == "bypassPermissions" || modeKey == "yolo")) "$label (sandboxed)" else label

        private fun isTruthy(value: kotlinx.serialization.json.JsonElement): Boolean = when (value) {
            is JsonNull -> false
            is JsonPrimitive -> when {
                value.isString -> value.content.isNotEmpty()
                value.booleanOrNull != null -> value.booleanOrNull == true
                else -> value.doubleOrNull.let { it != null && it != 0.0 && !it.isNaN() }
            }
            else -> true
        }
    }
}
