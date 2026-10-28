package com.slopus.happy.model.settings

import com.slopus.happy.model.Machine
import com.slopus.happy.model.MachineChoice
import com.slopus.happy.model.collectMachineChoices

/**
 * Settings › Agents (app/(app)/settings/agents.tsx, happy-ios `AgentsPage`):
 * the computers, then each configurable agent's default permission, model
 * and effort.
 */
object AgentsPage {
    /** The agents the page offers, with utils/harnessCatalog.ts's names. Retired harnesses keep their stored defaults but are not offered. */
    val configurable = listOf(AgentKey.Claude to "Claude Code", AgentKey.Codex to "Codex", AgentKey.Agy to "Antigravity")

    /** One computer under Machines. */
    data class MachineRow(
        val id: String,
        val name: String,
        /** `platform • online`, without the platform when it is unknown. */
        val subtitle: String,
        val online: Boolean,
        /** The machine a tap opens: a reachable daemon first, else the one the computer is named after. */
        val targetMachineId: String?,
    )

    /** Computers online first, then the most recently active. */
    fun machineRows(machines: List<Machine>, online: String, offline: String): List<MachineRow> =
        rowsOf(sortedChoices(machines), online, offline)

    private fun rowsOf(sorted: List<MachineChoice>, online: String, offline: String): List<MachineRow> = sorted.map { choice ->
        val machine = choice.happyMachine ?: choice.rigMachine
        val platform = machine?.metadata?.platform?.trim()
        val status = if (choice.online) online else offline
        val subtitle = listOfNotNull(platform, status).filter { it.isNotEmpty() }.joinToString(" • ")
        val target = listOfNotNull(choice.happyMachine, choice.rigMachine).firstOrNull { it.active } ?: machine
        MachineRow(choice.id, choice.name, subtitle, choice.online, target?.id)
    }

    /** The CLI the code defaults are described for: the first listed computer's Happy CLI, where a new session most likely starts. */
    fun defaultsCliVersion(machines: List<Machine>): String? = cliVersionOf(sortedChoices(machines))

    private fun cliVersionOf(sorted: List<MachineChoice>): String? =
        sorted.firstOrNull { it.happyMachine != null }?.happyMachine?.metadata?.happyCliVersion

    /** Stable, as JavaScript's sort is: ties keep collect order. */
    private fun sortedChoices(machines: List<Machine>): List<MachineChoice> =
        collectMachineChoices(machines).sortedWith(compareByDescending<MachineChoice> { it.online }.thenByDescending { it.activeAt })

    /** One field row of an agent's group and the choices it opens. */
    data class FieldRow(
        val field: AgentDefaultField,
        val title: String,
        /** The Ionicons name. */
        val icon: String,
        /** The override's name, or `Default (code default)`. */
        val detail: String,
        val options: List<ModeOption>,
        /** "Use code default"'s subtitle. */
        val codeDefaultName: String,
        val hasOverride: Boolean,
        val overrideValue: String?,
        /** Codex's free-form model: an override that is not in [options]. */
        val customModel: String?,
        val offersCustomModel: Boolean,
    )

    /** The permission modes the page lists, with agents.tsx's short descriptions. */
    fun permissionModes(agent: AgentKey): List<ModeOption> = when (agent) {
        AgentKey.Claude -> listOf(
            ModeOption("auto", "Auto", "asks when unsure"),
            ModeOption("acceptEdits", "Edits", "edits, no asking"),
            ModeOption("plan", "Plan", "plan first"),
            ModeOption("bypassPermissions", "Yolo", "never asks"),
            ModeOption("default", "Default", "harness setting"),
        )
        AgentKey.Codex -> listOf(
            ModeOption("auto", "Auto", "asks when unsure"),
            ModeOption("safe-yolo", "Workspace", "sandboxed, can escalate"),
            ModeOption("read-only", "Read", "no writes"),
            ModeOption("yolo", "Yolo", "full access"),
            ModeOption("default", "Default", "codex setting"),
        )
        AgentKey.Gemini -> listOf(ModeOption("yolo", "Yolo", "never asks"), ModeOption("default", "Default", "harness setting"))
        AgentKey.Openclaw -> listOf(ModeOption("bypassPermissions", "Yolo", "not applied"), ModeOption("default", "Default", "not applied"))
        AgentKey.Agy -> listOf(ModeOption("default", "Default", "agy sandbox"), ModeOption("bypassPermissions", "Yolo", "never asks"))
    }

    /** The model rows the page lists: the catalog plus a saved model that left it (`includeConfiguredModel`), without the `default` row. */
    fun modelModes(agent: AgentKey, configured: String?): List<ModeOption> =
        ModeOptions.includeConfiguredModel(agent.wire, ModeOptions.modelModes(agent.wire), configured).filter { it.key != "default" }

    /** Effort levels per model: one scale for Claude, Codex per model. */
    fun effortLevels(agent: AgentKey, model: String): List<ModeOption> = ModeOptions.effortLevelsForModel(agent.wire, model)

    /** `optionName`: an option's name, the raw key if unlisted, or "none". */
    fun optionName(options: List<ModeOption>, key: String?): String {
        if (key.isNullOrEmpty()) return "none"
        return options.firstOrNull { it.key == key }?.name ?: key
    }

    private operator fun AgentDefaultConfig.get(field: AgentDefaultField): String? = when (field) {
        AgentDefaultField.PermissionMode -> permissionMode
        AgentDefaultField.ModelMode -> modelMode
        AgentDefaultField.EffortLevel -> effortLevel
    }

    /** The fields an agent shows: permission always, model and effort when the agent has any. */
    fun fields(agent: AgentKey, overrides: AgentDefaultOverrides, cliVersion: String?): List<FieldRow> {
        val code = AgentDefaults.codeDefaults(agent.wire, cliVersion)
        val effective = AgentDefaults.resolve(overrides, agent.wire, cliVersion)
        val configs = listOf(
            // agents.tsx:253, 260, 267, spelled out.
            Triple(AgentDefaultField.PermissionMode, "Permission", "shield-checkmark-outline") to permissionModes(agent),
            Triple(AgentDefaultField.ModelMode, "Model", "hardware-chip-outline") to modelModes(agent, effective.modelMode),
            Triple(AgentDefaultField.EffortLevel, "Effort", "speedometer-outline") to effortLevels(agent, effective.modelMode),
        )
        return configs.mapNotNull { (config, options) ->
            val (field, title, icon) = config
            if (field != AgentDefaultField.PermissionMode && options.isEmpty()) return@mapNotNull null
            val overrideValue = AgentDefaults.overrideValue(overrides, agent.wire, field)
            val hasOverride = overrideValue != null
            // agents.tsx:133 "Default (", spelled out.
            val detail = if (hasOverride) optionName(options, overrideValue) else "Default (${optionName(options, effective[field])})"
            val offersCustom = agent == AgentKey.Codex && field == AgentDefaultField.ModelMode
            val isCustom = offersCustom && !overrideValue.isNullOrEmpty() && options.none { it.key == overrideValue }
            FieldRow(
                field = field,
                title = title,
                icon = icon,
                detail = detail,
                options = options,
                codeDefaultName = optionName(options, code[field]),
                hasOverride = hasOverride,
                overrideValue = overrideValue,
                customModel = if (isCustom) overrideValue else null,
                offersCustomModel = offersCustom,
            )
        }
    }

    /** The field showing its choices; one at a time across the page. */
    data class OpenField(val agent: AgentKey, val field: AgentDefaultField)

    /** One agent's group: its fields and, when it is this agent's, the one showing its choices. */
    data class AgentGroup(val agent: AgentKey, val name: String, val fields: List<FieldRow>, val open: AgentDefaultField?)

    /** agents.tsx reduced to what it draws. */
    data class Content(
        val machines: List<MachineRow>,
        /** "Clear Overrides" is enabled. */
        val hasOverrides: Boolean,
        val agents: List<AgentGroup>,
    ) {
        companion object {
            /** Off the main thread. */
            fun make(overrides: AgentDefaultOverrides, machines: List<Machine>, open: OpenField?, online: String, offline: String): Content {
                val sorted = sortedChoices(machines)
                val cliVersion = cliVersionOf(sorted)
                return Content(
                    machines = rowsOf(sorted, online, offline),
                    hasOverrides = overrides.agents.isNotEmpty(),
                    agents = configurable.map { (agent, name) ->
                        AgentGroup(agent, name, fields(agent, overrides, cliVersion), if (open?.agent == agent) open.field else null)
                    },
                )
            }
        }
    }
}
