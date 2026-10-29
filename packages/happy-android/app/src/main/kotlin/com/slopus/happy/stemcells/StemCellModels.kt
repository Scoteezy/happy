package com.slopus.happy.stemcells

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToInt

private fun JsonElement?.field(key: String): JsonElement? = (this as? JsonObject)?.get(key)
private val JsonElement?.string: String? get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
private val JsonElement?.bool: Boolean? get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
private val JsonElement?.number: Double? get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
private val JsonElement?.strings: List<String>? get() = (this as? JsonArray)?.mapNotNull { it.string }

/**
 * The models a paired computer offers, read from `GET /v0/config` as
 * desktop's `happyAgentModelCatalogProject` reads it: every provider in the
 * order the daemon lists them, each with the models it has switched on.
 * A port of happy-ios `StemCellModelCatalog`; kotlinx keeps an object's keys
 * in the order they were written, so no key-order reader is needed.
 */
data class StemCellModelCatalog(
    val defaultProviderId: String,
    val defaultModelId: String,
    val defaultPermissionMode: String = "auto",
    /** Every model the daemon knows, whether or not an account offers it. */
    val models: List<Model>,
    val providers: List<Provider>,
) {
    data class Model(
        val id: String,
        val name: String,
        /** Every effort the model supports, least to most. */
        val efforts: List<String>,
        val defaultEffort: String,
        val serviceTiers: List<String> = emptyList(),
    )

    data class Provider(
        val id: String,
        /** The account's friendly name ("Current CLI"), when it has one. */
        val name: String?,
        /** The service it is an account of ("claude", "codex"). */
        val type: String,
        val enabled: Boolean,
        /** Kept out of the pickers; a selection on it still works. */
        val hidden: Boolean,
        val models: List<Model>,
    ) {
        /** Switched off, or on with nothing to offer: its models can't be picked. */
        val isDisabled get() = !enabled || models.isEmpty()
    }

    fun provider(id: String): Provider? = providers.firstOrNull { it.id == id }

    /**
     * `happyAgentSessionSelectionDefault`: the declared default when its
     * account offers it, else the first usable account's first model; the
     * effort is the model's own default.
     */
    fun defaultMode(): AgentMode {
        val declared = provider(defaultProviderId)
        val declaredModel = if (declared?.isDisabled == false) declared.models.firstOrNull { it.id == defaultModelId } else null
        val provider = if (declaredModel != null) declared else providers.firstOrNull { !it.isDisabled && !it.hidden }
        val model = declaredModel ?: provider?.models?.firstOrNull()
        return AgentMode(
            providerId = provider?.id ?: defaultProviderId,
            modelId = model?.id ?: defaultModelId,
            effort = model?.defaultEffort ?: "medium",
            serviceTier = null,
            permissionMode = defaultPermissionMode,
        )
    }

    /**
     * `happyAgentSelectionModelUpdate`: another model, on the account given
     * or the first that offers it. Its effort is the one asked for, else the
     * model's own default, never one carried over that it may not support; a
     * speed it doesn't offer is dropped.
     */
    fun select(providerId: String?, modelId: String, effort: String? = null, from: AgentMode): AgentMode {
        val chosen = providerId ?: providers.firstOrNull { p -> !p.isDisabled && !p.hidden && p.models.any { it.id == modelId } }?.id ?: from.providerId
        val provider = provider(chosen)
        val model = provider?.models?.firstOrNull { it.id == modelId }
        val level = effort?.takeIf { model?.efforts?.contains(it) != false } ?: model?.defaultEffort ?: from.effort
        val tier = from.serviceTier?.takeIf { provider?.isDisabled == false && model?.serviceTiers?.contains(it) == true }
        return AgentMode(chosen, modelId, level, tier, from.permissionMode)
    }

    /** The model's definition, from its account or the shared list. */
    fun model(mode: AgentMode): Model? =
        provider(mode.providerId)?.models?.firstOrNull { it.id == mode.modelId } ?: models.firstOrNull { it.id == mode.modelId }

    companion object {
        /** The configuration in `GET /v0/config`'s answer; null when it isn't one. */
        fun from(response: JsonElement?): StemCellModelCatalog? {
            val config = response.field("config") ?: return null
            val defaults = config.field("defaults") ?: return null
            val providerId = defaults.field("providerId").string ?: return null
            val modelId = defaults.field("modelId").string ?: return null
            val definitions = config.field("models") as? JsonObject ?: JsonObject(emptyMap())
            fun model(id: String, reference: JsonElement?): Model? {
                val definition = definitions[id] ?: return null
                val efforts = (reference.field("efforts") ?: definition.field("efforts")).strings ?: emptyList()
                return Model(
                    id = id,
                    name = reference.field("name").string ?: definition.field("name").string ?: id,
                    efforts = efforts,
                    defaultEffort = (reference.field("defaultEffort") ?: definition.field("defaultEffort")).string ?: efforts.firstOrNull() ?: "medium",
                    serviceTiers = (reference.field("serviceTiers") ?: definition.field("serviceTiers")).strings ?: emptyList(),
                )
            }
            val providers = config.field("providers") as? JsonObject ?: JsonObject(emptyMap())
            return StemCellModelCatalog(
                defaultProviderId = providerId,
                defaultModelId = modelId,
                defaultPermissionMode = defaults.field("permissionMode").string ?: "auto",
                models = definitions.keys.mapNotNull { model(it, null) },
                providers = providers.map { (id, provider) ->
                    val references = provider.field("models") as? JsonArray ?: JsonArray(emptyList())
                    Provider(
                        id = id,
                        name = provider.field("name").string,
                        type = provider.field("type").string ?: id,
                        enabled = provider.field("enabled").bool ?: false,
                        hidden = provider.field("hidden").bool ?: false,
                        models = references.filter { it.field("enabled").bool == true }.mapNotNull { reference ->
                            reference.field("id").string?.let { model(it, reference) }
                        },
                    )
                },
            )
        }
    }
}

/**
 * Desktop's `happyAgentComposerModelControlProps`, flattened for a phone:
 * the services (one per provider type) with their accounts in catalog
 * order, the models each lists, and what is selected. The phone shows
 * Model, Provider and Effort as three pickers of their own.
 */
class StemCellModelMenus(catalog: StemCellModelCatalog, val selection: AgentMode) {
    data class Choice(val id: String, val label: String, val efforts: List<String>, val defaultEffort: String, val disabled: Boolean)

    data class Account(val id: String, val label: String, val models: List<Choice>)

    data class Service(
        val id: String,
        val label: String,
        val accounts: List<Account>,
        /** The account whose models the service lists: the selected one when it is this service's, else the first switched on. */
        val account: String,
    ) {
        val shown: Account? get() = accounts.firstOrNull { it.id == account } ?: accounts.firstOrNull()
    }

    /** Why the selected account is named although it is not a choice. */
    sealed class Notice {
        /** It still works; it is only kept out of the lists. */
        data object Hidden : Notice()
        /** It is no longer configured on the computer; its name. */
        data class Removed(val name: String) : Notice()
        /** It is switched off, so nothing is sent to it; its name. */
        data class Off(val name: String) : Notice()
    }

    val services: List<Service>
    /** The selected model, listed or not. */
    val current: Choice?
    val notice: Notice?

    init {
        val services = ArrayList<Service>()
        var current: Choice? = null
        for (provider in catalog.providers) {
            val choices = provider.models.map { choice(it, provider.isDisabled) }
            if (provider.id == selection.providerId) current = choices.firstOrNull { it.id == selection.modelId }
            // A hidden account is not offered, though the selection on it stays.
            if (provider.hidden) continue
            val account = Account(provider.id, provider.name ?: provider.id, choices)
            val existing = services.indexOfFirst { it.id == provider.type }
            if (existing >= 0) {
                val service = services[existing]
                services[existing] = service.copy(accounts = service.accounts + account, account = if (provider.id == selection.providerId) provider.id else service.account)
            } else {
                services += Service(provider.type, serviceName(provider.type), listOf(account), provider.id)
            }
            // A switched-off account is listed, but a service opens on one that works.
            val index = services.indexOfFirst { it.id == provider.type }
            if (index >= 0 && !provider.isDisabled && catalog.provider(services[index].account)?.isDisabled == true &&
                services[index].accounts.none { it.id == selection.providerId }
            ) {
                services[index] = services[index].copy(account = provider.id)
            }
        }
        val selected = catalog.provider(selection.providerId)
        // An account removed from the configuration still names its model.
        if (current == null) catalog.models.firstOrNull { it.id == selection.modelId }?.let { current = choice(it, true) }
        this.services = services
        this.current = current
        val name = selected?.name ?: selection.providerId
        notice = when {
            selected != null && !selected.isDisabled -> if (selected.hidden) Notice.Hidden else null
            selected == null -> Notice.Removed(name)
            else -> Notice.Off(name)
        }
    }

    /** The selected service, and its account. */
    val selectedService: Service? get() = services.firstOrNull { s -> s.accounts.any { it.id == selection.providerId } }
    val selectedAccount: Account? get() = selectedService?.accounts?.firstOrNull { it.id == selection.providerId }

    /** The effort shown: the selection's when its model supports it, else the model's own default. */
    val effort: String?
        get() {
            val current = current ?: return selection.effort
            return if (selection.effort in current.efforts) selection.effort else current.defaultEffort
        }

    /**
     * `composerModelAccountModel`: the model an account is switched onto,
     * the same one when it offers it, otherwise its first. Null when it
     * offers neither.
     */
    fun model(on: Account): Choice? = on.models.firstOrNull { it.id == selection.modelId && !it.disabled } ?: on.models.firstOrNull { !it.disabled }

    companion object {
        fun choice(model: StemCellModelCatalog.Model, disabled: Boolean) = Choice(model.id, model.name, model.efforts, model.defaultEffort, disabled)

        /** The service a provider type names ("Claude", "Codex"), titled when unknown. */
        fun serviceName(type: String): String = when (type) {
            "bedrock" -> "Bedrock"
            "claude" -> "Claude"
            "codex" -> "Codex"
            "grok" -> "Grok"
            else -> type.split('_', '-').filter { it.isNotEmpty() }.joinToString(" ") { it.take(1).uppercase() + it.drop(1) }
        }

        /** `happyAgentThinkingLabel`. */
        fun effortName(level: String): String = when (level) {
            "off" -> "Off"
            "on" -> "On"
            "minimal" -> "Minimal"
            "low" -> "Low"
            "medium" -> "Medium"
            "high" -> "High"
            "xhigh" -> "Extra High"
            "max" -> "Maximum"
            "ultra" -> "Ultra"
            else -> level.take(1).uppercase() + level.drop(1)
        }
    }
}

/**
 * One account's plan usage as the picker shows it: desktop's
 * `happyAgentComposerModelUsageProject` for one `GET /v0/usage` entry.
 * Times are milliseconds since 1970.
 */
data class StemCellAccountUsage(
    val week: Window?,
    val session: Window?,
    val updatedAt: Double?,
    val refreshing: Boolean,
    val refreshError: String?,
    /** The account has spent its limit. */
    val limited: Boolean,
    /** When it is usable again, if the service said. */
    val limitResets: Double?,
) {
    data class Window(
        val kind: Kind,
        val usedPercent: Double,
        val resetsAt: Double?,
        val capturedAt: Double?,
        /** The account can't be used until this window starts over. */
        val blocking: Boolean,
    ) {
        enum class Kind { Week, Session }

        /** What is left, 0–100, as the rings read it. */
        val left: Int get() = (100 - usedPercent).coerceIn(0.0, 100.0).roundToInt()
    }

    /** How a ring is coloured. */
    enum class Tone { Ample, Low, Critical }

    /** The window an account's row shows: its week, else its session. */
    val shown: Window? get() = week ?: session

    private data class Raw(val used: Double, val resets: Double?, val captured: Double?)

    companion object {
        private fun rawWindow(value: JsonElement?): Raw? =
            value.field("usedPercent").number?.let { Raw(it, value.field("resetsAt").number, value.field("capturedAt").number) }

        /** Every account with a reading, a read in flight or a failed read. */
        fun project(response: JsonElement?): Map<String, StemCellAccountUsage> {
            val usage = LinkedHashMap<String, StemCellAccountUsage>()
            for (entry in response.field("providers") as? JsonArray ?: JsonArray(emptyList())) {
                val providerId = entry.field("providerId").string ?: continue
                val reading = entry.field("usage")
                val windows = reading.field("windows")
                val fiveHour = rawWindow(windows.field("fiveHour"))
                val weekly = rawWindow(windows.field("weekly"))
                val monthly = rawWindow(windows.field("monthly"))
                val hasPlan = fiveHour != null || weekly != null || reading.field("planName").string != null
                val refreshedAt = entry.field("refreshedAt").number ?: if (hasPlan) reading.field("capturedAt").number else null
                val refreshError = entry.field("refreshError").string ?: entry.field("error").string
                val refreshing = entry.field("refreshing").bool ?: false
                if (!hasPlan && !refreshing && refreshError == null) continue
                val exhausted = hasPlan && reading.field("exhausted").bool == true
                // Every full window holds a spent account back; one blocked with none full waits on its fullest.
                var blocking: List<Raw> = emptyList()
                if (exhausted) {
                    val all = listOfNotNull(fiveHour, weekly, monthly)
                    val full = all.filter { it.used >= 100 }
                    blocking = full.ifEmpty { all.sortedByDescending { it.used }.take(1) }
                }
                fun window(kind: Window.Kind, raw: Raw?) = raw?.let { Window(kind, it.used, it.resets, it.captured, it in blocking) }
                val resets = blocking.mapNotNull { it.resets }.maxOrNull()
                usage[providerId] = StemCellAccountUsage(
                    week = if (hasPlan) window(Window.Kind.Week, weekly) else null,
                    session = if (hasPlan) window(Window.Kind.Session, fiveHour) else null,
                    updatedAt = refreshedAt,
                    refreshing = refreshing,
                    refreshError = refreshError,
                    limited = exhausted,
                    limitResets = if (exhausted) resets else null,
                )
            }
            return usage
        }

        fun tone(left: Int, blocked: Boolean): Tone = when {
            blocked || left < 10 -> Tone.Critical
            left < 25 -> Tone.Low
            else -> Tone.Ample
        }
    }
}
