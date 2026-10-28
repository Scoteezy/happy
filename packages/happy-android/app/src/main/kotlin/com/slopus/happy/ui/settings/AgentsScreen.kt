package com.slopus.happy.ui.settings

import android.content.Context
import android.view.View
import com.slopus.happy.AppGraph
import com.slopus.happy.R
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.Palette
import com.slopus.happy.model.Machine
import com.slopus.happy.model.settings.AgentDefaultField
import com.slopus.happy.model.settings.AgentDefaultOverrides
import com.slopus.happy.model.settings.AgentDefaults
import com.slopus.happy.model.settings.AgentKey
import com.slopus.happy.model.settings.AgentsPage
import com.slopus.happy.ui.components.Alerts
import com.slopus.happy.ui.components.GroupedListView
import com.slopus.happy.ui.components.ItemGroupView
import com.slopus.happy.ui.components.ItemIcon
import com.slopus.happy.ui.components.ItemRowView
import com.slopus.happy.ui.machine.MachineScreen
import com.slopus.happy.ui.nav.BarScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings › Agents (app/(app)/settings/agents.tsx, happy-ios
 * `SettingsAgentsViewController`): the account's computers, then the
 * permission, model and effort new sessions start with for each agent. A
 * field opens its choices in place, one at a time.
 *
 * The page is worked out off the main thread ([AgentsPage.Content]); only
 * the groups whose content changed are built again, so a heartbeat that
 * leaves the computers alone touches nothing.
 */
class AgentsScreen(context: Context, private val graph: AppGraph) : BarScreen(context) {
    private val list = GroupedListView(context)
    private val open = MutableStateFlow<AgentsPage.OpenField?>(null)
    /** Overrides chosen here, shown until the settings feed speaks again (agents.tsx writes them to its local settings at once). */
    private val chosen = MutableStateFlow<AgentDefaultOverrides?>(null)
    private var overrides = AgentDefaultOverrides()
    private val built = HashMap<Any, Pair<Any, ItemGroupView>>()

    override fun createContent(): View {
        bar.title = context.getString(R.string.settings_agents)
        return list
    }

    override fun viewCreated(view: View) {
        super.viewCreated(view)
        val online = context.getString(R.string.status_online)
        val offline = context.getString(R.string.status_offline)
        val machines = graph.store.changes.map { graph.store.machines() }.distinctUntilChanged().map { records -> records.map(Machine::from) }.flowOn(Dispatchers.IO)
        val settings = graph.settings.settings.map { it.agentDefaultOverrides }.distinctUntilChanged()
        scope.launch {
            // A new settings value replaces what was chosen here.
            settings.collect { chosen.value = null }
        }
        scope.launch {
            combine(settings, chosen, machines, open) { stored, chosen, machines, open -> Triple(chosen ?: stored, machines, open) }
                .collectLatest { (overrides, machines, open) ->
                    val content = withContext(Dispatchers.Default) { AgentsPage.Content.make(overrides, machines, open, online, offline) }
                    this@AgentsScreen.overrides = overrides
                    apply(content)
                }
        }
    }

    override fun onInsetsChanged() {
        super.onInsetsChanged()
        list.safeArea = contentSafeArea
    }

    /** The group for [key] showing [content], built again only when its content changed. */
    private fun group(key: Any, content: Any, build: () -> ItemGroupView): ItemGroupView {
        built[key]?.let { (shown, view) -> if (shown == content) return view }
        return build().also { built[key] = content to it }
    }

    private fun apply(content: AgentsPage.Content) {
        list.sections = listOf(
            group("machines", content.machines) { machinesGroup(content.machines) },
            group("defaults", content.hasOverrides) { defaultsGroup(content.hasOverrides) },
        ) + content.agents.map { agent -> group(agent.agent, agent) { agentGroup(agent) } }
    }

    private fun machinesGroup(machines: List<AgentsPage.MachineRow>): ItemGroupView {
        val title = context.getString(R.string.agents_machines)
        if (machines.isEmpty()) {
            val row = ItemRowView(
                context,
                context.getString(R.string.agents_no_machines),
                subtitle = context.getString(R.string.agents_no_machines_subtitle),
                icon = ItemIcon.Glyph(Icon.ionicons("desktop-outline"), Palette.textSecondary),
            )
            row.alpha = 0.5f
            return ItemGroupView(context, title = title, rows = listOf(row))
        }
        return ItemGroupView(
            context,
            title = title,
            rows = machines.map { machine ->
                ItemRowView(
                    context,
                    machine.name,
                    subtitle = machine.subtitle,
                    icon = ItemIcon.Glyph(Icon.ionicons("desktop-outline"), if (machine.online) Palette.statusConnected else Palette.statusDisconnected),
                    onTap = machine.targetMachineId?.let { id -> { push(MachineScreen(context, graph, id)) } },
                ).apply { alpha = if (machine.online) 1f else 0.5f }
            },
        )
    }

    private fun defaultsGroup(hasOverrides: Boolean): ItemGroupView {
        val clear = ItemRowView(
            context,
            context.getString(R.string.agents_clear_overrides),
            subtitle = context.getString(R.string.agents_clear_overrides_subtitle),
            icon = ItemIcon.Glyph(Icon.ionicons("refresh-outline"), Palette.iconOrange),
        ) { choose(AgentDefaultOverrides()) }
        clear.showsChevron = false
        if (!hasOverrides) {
            clear.isEnabled = false
            clear.alpha = 0.5f
        }
        return ItemGroupView(context, title = context.getString(R.string.agents_defaults), rows = listOf(clear))
    }

    private fun agentGroup(group: AgentsPage.AgentGroup): ItemGroupView {
        val agent = group.agent
        val rows = mutableListOf<ItemRowView>()
        for (field in group.fields) {
            val opening = AgentsPage.OpenField(agent, field.field)
            rows += ItemRowView(context, field.title, detail = field.detail, icon = ItemIcon.Glyph(Icon.ionicons(field.icon), Palette.iconPurple)) {
                open.value = if (open.value == opening) null else opening
            }
            if (group.open != field.field) continue
            rows += option(context.getString(R.string.agents_use_code_default), field.codeDefaultName, !field.hasOverride) { set(agent, field.field, null) }
            for (choice in field.options) {
                rows += option(choice.name, choice.description, field.hasOverride && field.overrideValue == choice.key) { set(agent, field.field, choice.key) }
            }
            if (field.offersCustomModel) {
                rows += option(
                    context.getString(R.string.agents_custom_model),
                    field.customModel ?: context.getString(R.string.agents_custom_model_subtitle),
                    field.customModel != null,
                ) { editCustomModel(field.customModel) }
            }
        }
        val view = ItemGroupView(context, title = group.name, rows = rows)
        // Each field is a Fragment in happy-app, which ItemGroup leaves alone,
        // so every row keeps Item's default divider, the last one included.
        rows.forEach { it.showsDivider = true }
        return view
    }

    private fun option(title: String, subtitle: String?, selected: Boolean, onTap: () -> Unit) =
        ItemRowView(context, title, subtitle = subtitle, onTap = onTap).apply {
            showsChevron = false
            if (selected) trailingView = SettingsCheckmark.view(context, Palette.headerTint)
        }

    private fun set(agent: AgentKey, field: AgentDefaultField, value: String?) =
        choose(AgentDefaults.set(overrides, agent.wire, field, value))

    /** Shows [next] at once and saves it. */
    private fun choose(next: AgentDefaultOverrides) {
        chosen.value = next
        graph.settings.change { it.copy(agentDefaultOverrides = next) }
    }

    private fun editCustomModel(current: String?) {
        scope.launch {
            val model = Alerts.prompt(
                context,
                context.getString(R.string.agents_custom_model_title),
                context.getString(R.string.agents_custom_model_message),
                placeholder = context.getString(R.string.agents_custom_model_placeholder),
                confirmTitle = context.getString(R.string.common_save),
                initial = current.orEmpty(),
            )?.trim()
            if (!model.isNullOrEmpty()) set(AgentKey.Codex, AgentDefaultField.ModelMode, model)
        }
    }
}
