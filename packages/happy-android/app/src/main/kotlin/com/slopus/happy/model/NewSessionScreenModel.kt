package com.slopus.happy.model

import com.slopus.happy.chat.ComposerStatus

/**
 * What new/index.tsx's phone screen shows for a draft (happy-ios
 * `NewSessionScreenModel`): the config rows, each picker's rows and choice,
 * the composer's harness and settings, and whether Send can be pressed. A
 * pure value, read off the draft's options whenever they change.
 */
data class NewSessionScreenModel(
    val machineName: String,
    val machineItems: List<Item>,
    val selectedMachineKey: String?,
    /** Set while the chosen harness's daemon is offline: the place rows fade and stop taking presses. */
    val offlineHelp: OfflineHelp?,
    val pathName: String,
    /** The path field's text. */
    val pathValue: String,
    val pathItems: List<Item>,
    /** The row drawn as chosen while the draft names a project. */
    val selectedProjectKey: String?,
    /** "using project …", while the draft names a project. */
    val selectedProjectName: String?,
    /** The row whose directory the field holds. */
    val matchedPathKey: String?,
    val homeDir: String?,
    val canPickWorktree: Boolean,
    val picksWorkspaces: Boolean,
    val worktreeKey: String,
    val worktreeLabel: String,
    val worktreeFixedItems: List<Item>,
    val worktreeItems: List<Item>,
    val agentKey: String,
    val agentLabel: String,
    val agentItems: List<Item>,
    val placeholder: String,
    val settings: List<Setting>,
    /** Codex words its permission picker as its own. */
    val isCodex: Boolean,
    /** `canSend`: a computer whose daemon for the harness is online, and no start already running. A prompt is not needed. */
    val canSend: Boolean,
) {
    /** One picker row (`PickerItem`). */
    data class Item(
        val key: String,
        val label: String,
        val subtitle: String? = null,
        /** The heading drawn above the first row of a group. */
        val section: String? = null,
        /** An offline computer, drawn faded but still pickable. */
        val isDimmed: Boolean = false,
        /** A computer's state, worded by the screen (`status.online`, `status.lastSeen`). */
        val status: MachineStatus? = null,
        /** A catalog project offered by identity: picked, never typed. */
        val projectId: String? = null,
    )

    sealed interface MachineStatus {
        data object Online : MachineStatus
        data class LastSeen(val lastSeen: ComposerStatus.LastSeen) : MachineStatus
    }

    /** One row of the composer's settings popover. */
    data class Setting(
        val kind: Kind,
        val value: String,
        /** The Ionicons glyph. */
        val icon: String,
        val items: List<Item>,
        val selectedKey: String?,
    ) {
        enum class Kind { Permission, Model, Effort }
    }

    /** The machine row's help while the harness's daemon is offline. */
    enum class OfflineHelp {
        /** `machine.offlineHelp`. */
        HappyCli,
        HappyAgent,
    }

    /** The path a directory row puts in the field: the row's own folder, as the list words it. */
    fun fieldValue(item: Item): String {
        if (item.projectId != null) return ""
        val path = if (readProjectPlaceKey(item.key) == null) item.key else item.label
        return formatPathRelativeToHome(path, homeDir)
    }

    companion object {
        /** new/index.tsx's `ALL_AGENTS`: lowercased, in the dock's pick order. */
        val agentLabels = mapOf("claude" to "claude code", "codex" to "codex", "agy" to "antigravity", "rig" to "happy")

        /**
         * The screen for [draft] as [options] resolve it. [projectName] names
         * a project the catalog knows but no session shows.
         */
        fun of(
            options: NewSessionOptions,
            draft: NewSessionDraft,
            isStarting: Boolean,
            now: Long,
            projectName: (String) -> String? = { null },
        ): NewSessionScreenModel {
            val choice = options.choice
            val agent = options.resolvedAgent.wire
            val agentMachine = resolveAgentMachine(choice, options.resolvedAgent)

            // Online first, each group in the catalog's order: the options' machines are already so.
            val machineItems = options.machineChoices.map { machine ->
                Item(
                    key = machine.id,
                    label = machine.name,
                    isDimmed = !machine.online,
                    status = if (machine.online) MachineStatus.Online else MachineStatus.LastSeen(ComposerStatus.lastSeen(machine.activeAt, now)),
                )
            }
            val isOffline = agentMachine?.isOnline == false

            // Both daemons contribute places, whichever harness is chosen.
            val homeDir = options.homeDir
            val selectedPath = draft.selectedPath
            val places = options.sessionPlaces
            val draftProjectId = draft.selectedProjectId
            val draftProjectPlace = draftProjectId?.let { id -> places.find { it.projectId == id } }
            val pathItems = places.map { place ->
                // Only Happy Agent's catalog knows this project's folder.
                val path = place.path ?: return@map Item(place.key, place.name, projectId = place.projectId)
                val relative = formatPathRelativeToHome(path, homeDir)
                Item(
                    key = place.key,
                    label = if (place.projectId != null) place.name else relative,
                    subtitle = if (place.projectId != null) relative else null,
                )
            }.toMutableList()
            // The draft's own project keeps its row after its last chat is archived, named by the catalog.
            if (draftProjectId != null && draftProjectPlace == null) {
                pathItems += Item(projectPlaceKey(draftProjectId), projectName(draftProjectId) ?: "Project", projectId = draftProjectId)
            }
            val selectedProjectKey = draftProjectId?.let { draftProjectPlace?.key ?: projectPlaceKey(it) }
            val trimmedPath = selectedPath.orEmpty().trim()
            val pathName = if (draftProjectId != null) {
                pathItems.find { it.projectId == draftProjectId }?.label ?: "Project"
            } else if (trimmedPath.isEmpty()) {
                "~"
            } else {
                formatPathRelativeToHome(trimmedPath, homeDir)
            }
            val normalizedValue = normalizedPath(selectedPath, homeDir)

            val picksWorkspaces = options.picksWorkspaces
            val fixed = buildList {
                if (options.canCreateWorktree) add(Item(WorktreeSelection.NEW, if (picksWorkspaces) "Create New" else "new worktree"))
                add(Item(WorktreeSelection.NONE, if (picksWorkspaces) "Main" else "no worktree"))
            }
            val worktreeItems = if (picksWorkspaces) {
                // A project named only by the catalog has no directory to list workspaces under.
                if (normalizedValue == null) emptyList() else options.sessionWorkspaces.map { Item(it.key, it.name, it.path) }
            } else {
                options.gitWorktrees.map { Item(it.path, it.branch, it.path) }
            }
            val worktreeKey = options.worktreeSelection
            val worktreeLabel = when (worktreeKey) {
                WorktreeSelection.NONE -> if (picksWorkspaces) "Main" else "no worktree"
                WorktreeSelection.NEW -> if (picksWorkspaces) "Create New" else "new worktree"
                else -> worktreeItems.find { it.key == worktreeKey }?.label ?: worktreeKey
            }

            val shown = if (agentLabels[agent] != null) agent else "claude"
            val agentLabel = agentLabels[shown] ?: shown

            val settings = buildList {
                val permissions = options.permissions.filter { !it.disabled }
                val permission = options.permission
                if (permissions.size > 1 && permission != null) {
                    add(Setting(
                        Setting.Kind.Permission,
                        permission.name,
                        permissionIcon(permission.key) ?: "shield-outline",
                        permissions.map { Item(it.key, it.name, it.description) },
                        permission.key,
                    ))
                }
                val model = options.model
                if (options.models.sumOf { it.options.size } > 1 && model != null) {
                    val items = options.models.flatMap { section ->
                        section.options.map { option ->
                            Item(option.key, option.name, option.description.takeUnless { it == section.title }, section = section.title)
                        }
                    }
                    add(Setting(Setting.Kind.Model, model.name, "cube-outline", items, model.key))
                }
                val effort = options.effort
                if (options.efforts.isNotEmpty() && effort != null) {
                    add(Setting(Setting.Kind.Effort, effort.name, "speedometer-outline", options.efforts.map { Item(it.key, it.name, it.description) }, effort.key))
                }
            }

            return NewSessionScreenModel(
                machineName = choice?.name ?: "Select machine",
                machineItems = machineItems,
                selectedMachineKey = choice?.id ?: draft.selectedMachineId,
                offlineHelp = if (isOffline) (if (agent == "rig") OfflineHelp.HappyAgent else OfflineHelp.HappyCli) else null,
                pathName = pathName,
                pathValue = selectedPath.orEmpty(),
                pathItems = pathItems,
                selectedProjectKey = selectedProjectKey,
                selectedProjectName = selectedProjectKey?.let { key -> pathItems.find { it.key == key }?.label },
                matchedPathKey = normalizedValue?.let { value -> pathItems.find { normalizedPath(it.key, homeDir) == value }?.key },
                homeDir = homeDir,
                canPickWorktree = options.supportsWorktree || picksWorkspaces,
                picksWorkspaces = picksWorkspaces,
                worktreeKey = worktreeKey,
                worktreeLabel = worktreeLabel,
                worktreeFixedItems = fixed,
                worktreeItems = worktreeItems,
                agentKey = shown,
                agentLabel = agentLabel,
                agentItems = options.agents.map { Item(it.key, agentLabels[it.key] ?: it.name) },
                placeholder = if (agent == "codex") "Ask Codex" else "Ask $agentLabel",
                settings = settings,
                isCodex = agent == "codex",
                canSend = draft.selectedMachineId != null && agentMachine?.isOnline == true && !isStarting,
            )
        }

        /** utils/newSessionModeSelection.ts `resolvePermissionStyle`'s icon. */
        fun permissionIcon(key: String): String? = when (key) {
            "acceptEdits", "auto_edit", "dontAsk", "safe-yolo", "bypassPermissions", "yolo" -> "play-forward"
            "plan", "read-only" -> "pause"
            else -> null
        }

        private val driveRoot = Regex("^[A-Za-z]:[\\\\/]?$")

        /** `normalizePathForComparison`: trimmed, absolute, without a trailing separator; null when blank. */
        fun normalizedPath(path: String?, homeDir: String?): String? {
            val trimmed = path.orEmpty().trim()
            if (trimmed.isEmpty()) return null
            val absolute = resolveAbsolutePath(trimmed, homeDir)
            if (absolute == "/" || driveRoot.matches(absolute)) return absolute
            return absolute.trimEnd('/', '\\')
        }
    }
}
