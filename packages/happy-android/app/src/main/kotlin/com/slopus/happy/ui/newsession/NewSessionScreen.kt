package com.slopus.happy.ui.newsession

import android.content.Context
import android.view.View
import android.view.animation.PathInterpolator
import android.view.inputmethod.InputMethodManager
import com.slopus.happy.AppGraph
import com.slopus.happy.R
import com.slopus.happy.chat.ComposerStatus
import com.slopus.happy.home.HomeServices
import com.slopus.happy.home.NewSessionServices
import com.slopus.happy.home.StartResult
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.LayoutInsets
import com.slopus.happy.layout.NavigationBarLayout
import com.slopus.happy.layout.NewSessionPickerContentLayout
import com.slopus.happy.layout.NewSessionPickerLayout
import com.slopus.happy.layout.NewSessionScreenLayout
import com.slopus.happy.layout.Palette
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.Size
import com.slopus.happy.layout.TextStyles
import com.slopus.happy.layout.ColorToken
import com.slopus.happy.layout.newSessionEmpty
import com.slopus.happy.layout.newSessionMeta
import com.slopus.happy.layout.newSessionOption
import com.slopus.happy.layout.newSessionSection
import com.slopus.happy.layout.newSessionSettingName
import com.slopus.happy.model.NewSessionDraft
import com.slopus.happy.model.NewSessionOptions
import com.slopus.happy.model.NewSessionPage
import com.slopus.happy.model.NewSessionScreenModel
import com.slopus.happy.model.projectPlaceKey
import com.slopus.happy.model.resolveNewSessionOptions
import com.slopus.happy.ui.chat.ChatScreen
import com.slopus.happy.ui.components.Alerts
import com.slopus.happy.ui.nav.BarScreen
import com.slopus.happy.ui.theme.Theme
import com.slopus.happy.ui.theme.ThemeAware
import com.slopus.happy.ui.view.ManualLayout
import com.slopus.happy.ui.view.ScrollContainer
import com.slopus.happy.ui.view.dp
import com.slopus.happy.ui.view.setFrame
import java.util.Calendar
import java.util.Date
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * app/(app)/new/index.tsx on a phone (happy-ios `NewSessionViewController`):
 * the machine, path and worktree rows over a composer with the harness, its
 * settings and Send, each picker in a popover above what opened it.
 *
 * The pickers write the account's new-session draft, which home's dock
 * shares; the prompt is the draft's too. Send runs the draft through the
 * shared [com.slopus.happy.home.SessionStarter], then this screen gives way
 * to the session. The bar is clear; the title capsule sits beside back.
 */
class NewSessionScreen(
    context: Context,
    private val graph: AppGraph,
    machineId: String? = null,
    path: String? = null,
    prompt: String? = null,
) : BarScreen(context) {
    private enum class Picker(val anchor: NewSessionPickerLayout.Anchor) {
        Machine(NewSessionPickerLayout.Anchor.Row(0)),
        Path(NewSessionPickerLayout.Anchor.Row(1)),
        Worktree(NewSessionPickerLayout.Anchor.Row(2)),
        Agent(NewSessionPickerLayout.Anchor.Composer),
        Settings(NewSessionPickerLayout.Anchor.Composer),
    }

    private val home = HomeServices.of(graph)
    private val services: NewSessionServices = home.newSession(context)
    private val starter = services.starter

    private val titleView = NewSessionTitleView(context, context.getString(R.string.new_session_title))
    private val scroll = ScrollContainer(context)
    /** Taps between the rows put the keyboard and any picker away (`mobileKeyboardDismissArea`). */
    private val dismissArea = View(context)
    private val machineRow = NewSessionConfigRowView(context, Icon.ionicons("desktop-outline"))
    private val offlineView = NewSessionOfflineView(context)
    private val pathRow = NewSessionConfigRowView(context, Icon.ionicons("folder-outline"))
    private val worktreeRow = NewSessionConfigRowView(context, Icon.materialCommunityIcons("tree"))
    private val composerView = NewSessionComposerView(context)
    /** Under the rows and over the composer (`NEW_SESSION_PICKER_LAYERS`): a tap on the composer closes the picker, a tap on a row switches it. */
    private val backdrop = View(context)
    private var root: Root? = null

    private lateinit var model: NewSessionScreenModel
    private var activePicker: Picker? = null
    private var pickerView: NewSessionPickerView? = null
    private var settingsPage: NewSessionScreenModel.Setting.Kind? = null
    private var searchText = ""
    private var searchRow: NewSessionFieldRow? = null
    private var pathField: NewSessionFieldRow? = null
    private var settingPathField = false
    private var pendingPicker: Picker? = null
    private var pendingJob: Job? = null
    /** Send was tapped and the start runs from here. */
    private var launching = false
    private var lastConfigHeight = 0.0

    init {
        // The route's place and prompt go into the shared draft first, as iOS does.
        if (machineId != null) services.select(NewSessionPage.Machine, machineId)
        if (path != null) services.selectPath(path)
        if (prompt != null) services.setInput(prompt)
        bar.fill = ColorToken.Clear
        model = makeModel()
    }

    override fun createContent(): View = Root(context).also { root = it }

    override fun viewCreated(view: View) {
        super.viewCreated(view)
        composerView.onTextChange = { text -> services.setInput(text) }
        composerView.onHeightChange = { root?.requestLayout() }
        composerView.agentButton.onTap { toggle(Picker.Agent) }
        composerView.settingsButton.onTap { toggle(Picker.Settings) }
        // The microphone only brings the keyboard up.
        composerView.micButton.onTap { composerView.textView.focus() }
        composerView.sendButton.onTap { send() }
        backdrop.visibility = View.GONE
        backdrop.setOnClickListener { closePicker() }
        dismissArea.setOnClickListener {
            hideKeyboard()
            closePicker()
        }
        machineRow.onTap { toggle(Picker.Machine) }
        pathRow.onTap { toggle(Picker.Path) }
        worktreeRow.onTap { toggle(Picker.Worktree) }
        listOf(dismissArea, machineRow, offlineView, pathRow, worktreeRow).forEach(scroll::addContent)

        scope.launch {
            combine(services.options, services.draft, starter.phase) { _, _, _ -> }.collect { render() }
        }
        render()
    }

    override fun onInsetsChanged() {
        super.onInsetsChanged()
        // keyboardDidHide: a picker waiting for the keyboard opens now.
        if (keyboard == 0.0 && pendingPicker != null) finishOpening()
    }

    override fun onDestroy() {
        // Back while a session starts here: put it down, as Stop would.
        if (launching || (startedHere && starter.phase.value != null)) starter.cancel()
        super.onDestroy()
    }

    private var startedHere = false

    // State

    private fun options(draft: NewSessionDraft): NewSessionOptions =
        // Until the home feed arrives the draft stays as saved, on no computers.
        services.options.value ?: resolveNewSessionOptions(draft, emptyList(), emptyList(), null)

    private fun makeModel(): NewSessionScreenModel {
        val draft = services.draft.value
        return NewSessionScreenModel.of(
            options(draft),
            draft,
            isStarting = launching || starter.phase.value != null,
            now = System.currentTimeMillis(),
            projectName = { id -> home.snapshots.value?.projects?.get(id)?.name },
        )
    }

    private fun render() {
        if (fillPathIfNeeded()) return
        model = makeModel()
        val draft = services.draft.value

        machineRow.value = model.machineName
        val help = model.offlineHelp
        if (help != null) {
            val text = context.getString(if (help == NewSessionScreenModel.OfflineHelp.HappyAgent) R.string.home_dock_happy_agent_offline else R.string.machine_offline_help)
            offlineView.body.text = text + "\n" + context.getString(R.string.new_session_switch_machines_hint)
        }
        pathRow.value = model.pathName
        worktreeRow.value = model.worktreeLabel
        pathRow.isLive = help == null
        worktreeRow.isLive = help == null

        composerView.placeholderText = model.placeholder
        composerView.agentButton.update(model.agentKey, model.agentLabel)
        composerView.showsSettings = model.settings.isNotEmpty()
        composerView.sendButton.canSend = model.canSend
        composerView.sendButton.isBusy = launching || starter.phase.value != null
        composerView.setText(draft.input)
        updateActiveControls()

        if (activePicker != null) rebuildPicker()
        root?.requestLayout()
    }

    /** new/index.tsx's first-path effect: a computer with no place chosen starts in its first directory, or home. A project is a place. */
    private fun fillPathIfNeeded(): Boolean {
        val draft = services.draft.value
        if (options(draft).choice == null || draft.selectedPath != null || draft.selectedProjectId != null) return false
        val first = makeModel().pathItems.firstOrNull { it.projectId == null }?.key
        services.selectPath(first ?: "~")
        return true
    }

    private fun updateActiveControls() {
        composerView.agentButton.isActive = activePicker == Picker.Agent
        composerView.settingsButton.isActive = activePicker == Picker.Settings
    }

    // Layout

    /** Where the header row starts: the bar's bottom less the 52dp header iOS lays out under. */
    private val headerTop: Double
        get() = NavigationBarLayout.height(safeArea.top) - NewSessionScreenLayout.Metrics.headerHeight

    private inner class Root(context: Context) : ManualLayout(context), ThemeAware {
        init {
            addView(composerView)
            addView(backdrop)
            addView(scroll)
            addView(titleView)
            applyTheme()
        }

        override fun applyTheme() = setBackgroundColor(Theme.color(Palette.groupedBackground))

        override fun layoutSubviews(width: Double, height: Double) {
            if (width <= 0) return
            val composerWidth = max(0.0, width - 2 * NewSessionScreenLayout.Metrics.composerPaddingHorizontal)
            val composerHeight = composerView.layout(composerWidth).size.height
            val showsOffline = model.offlineHelp != null
            val showsWorktree = model.canPickWorktree
            val rows = buildList {
                add(NewSessionScreenLayout.Row.Config(machineRow.label.measurable))
                if (showsOffline) add(NewSessionScreenLayout.Row.Offline(offlineView.title.measurable, offlineView.body.measurable))
                add(NewSessionScreenLayout.Row.Config(pathRow.label.measurable))
                if (showsWorktree) add(NewSessionScreenLayout.Row.Config(worktreeRow.label.measurable))
            }
            val layout = NewSessionScreenLayout(NewSessionScreenLayout.Input(
                size = Size(width, height),
                safeTop = headerTop,
                safeBottom = safeArea.bottom,
                keyboardOverlap = keyboard,
                maxWidth = max(width, height),
                composerHeight = composerHeight,
                rows = rows,
            ))

            composerView.setFrame(layout.composer)
            backdrop.setFrame(Rect(0.0, 0.0, width, height))
            scroll.setFrame(layout.scroll)
            val frames = layout.rows.iterator()
            val machine = frames.next()
            val offline = if (showsOffline) frames.next() else null
            val pathFrame = frames.next()
            val worktree = if (showsWorktree) frames.next() else null
            scroll.place(layout.contentSize, linkedMapOf(
                dismissArea to Rect(0.0, 0.0, layout.contentSize.width, layout.contentSize.height),
                machineRow to machine.frame,
                offlineView to offline?.frame,
                pathRow to pathFrame.frame,
                worktreeRow to worktree?.frame,
            ))
            machineRow.apply(machine)
            offline?.let(offlineView::apply)
            pathRow.apply(pathFrame)
            worktree?.let(worktreeRow::apply)

            val title = titleView.size()
            val back = bar.layout(width).leading.firstOrNull()
            val titleX = (back?.maxX ?: 0.0) + TITLE_GAP
            titleView.setFrame(Rect(titleX, safeArea.top + (NavigationBarLayout.Metrics.height - title.height) / 2, title.width, title.height))

            // Measured with the keyboard down, as the popovers are placed.
            if (keyboard == 0.0) lastConfigHeight = layout.configHeight
            layoutPicker(Size(width, height), composerHeight)
        }
    }

    private fun layoutPicker(size: Size, composerHeight: Double) {
        val pickerView = pickerView ?: return
        val picker = activePicker ?: return
        val m = NewSessionPickerLayout.Metrics
        val contentWidth = max(0.0, size.width - 2 * m.inset - 2 * m.paddingHorizontal)
        val content = pickerView.content.layout(contentWidth)
        val layout = NewSessionPickerLayout(NewSessionPickerLayout.Input(
            size = size,
            safeTop = headerTop,
            safeBottom = safeArea.bottom,
            composerHeight = composerHeight,
            configHeight = lastConfigHeight,
            anchor = picker.anchor,
            contentHeight = content.height,
        ))
        var frame = layout.frame
        // The path popover rides on the keyboard while its field is typed in (KeyboardStickyView).
        if (picker == Picker.Path) frame = frame.offsetBy(0.0, -keyboard)
        pickerView.setFrame(frame)
    }

    // Pickers

    /** `togglePicker`: the same control closes its picker; another opens its own once the keyboard is down. */
    private fun toggle(picker: Picker) {
        if (activePicker == picker) {
            closePicker()
            return
        }
        if (pendingPicker == picker) return
        closePicker()
        if (picker == Picker.Worktree) services.onOpenPicker(NewSessionPage.Worktree)
        if (picker == Picker.Settings) settingsPage = null
        if (keyboard == 0.0) {
            open(picker)
            return
        }
        pendingPicker = picker
        pendingJob = scope.launch {
            delay(420)
            finishOpening()
        }
        hideKeyboard()
    }

    private fun finishOpening() {
        val picker = pendingPicker ?: return
        cancelPending()
        open(picker)
    }

    private fun cancelPending() {
        pendingPicker = null
        pendingJob?.cancel()
        pendingJob = null
    }

    private fun open(picker: Picker) {
        val root = root ?: return
        activePicker = picker
        searchText = ""
        val view = NewSessionPickerView(context, NewSessionPickerContentView(context))
        pickerView = view
        rebuildPicker()
        root.addView(view)
        backdrop.visibility = View.VISIBLE
        updateActiveControls()
        // AnimatedPopup: in from a little smaller and lower.
        view.alpha = 0f
        view.translationY = dp(NewSessionPickerLayout.Metrics.enterOffset)
        view.scaleX = NewSessionPickerLayout.Metrics.enterScale.toFloat()
        view.scaleY = NewSessionPickerLayout.Metrics.enterScale.toFloat()
        view.animate()
            .alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration((NewSessionPickerLayout.Metrics.enterDuration * 1000).toLong())
            .setInterpolator(PathInterpolator(0.215f, 0.61f, 0.355f, 1f))
            .start()
    }

    /** `closePicker`: gone at once (`exitImmediately`). */
    private fun closePicker() {
        cancelPending()
        if (activePicker == null) return
        activePicker = null
        settingsPage = null
        searchRow = null
        pathField?.field?.let { if (it.hasFocus()) hideKeyboard() }
        pathField = null
        pickerView?.let { root?.removeView(it) }
        pickerView = null
        backdrop.visibility = View.GONE
        updateActiveControls()
    }

    private fun rebuildPicker() {
        val picker = activePicker ?: return
        val content = pickerView?.content ?: return
        content.listMaxHeight = NewSessionPickerContentLayout.Metrics.listMaxHeight
        content.paddingTop = 0.0
        content.paddingBottom = NewSessionPickerContentLayout.Metrics.embeddedPaddingBottom
        when (picker) {
            Picker.Machine -> buildList(content, emptyList(), model.machineItems, model.selectedMachineKey, R.string.home_new_session_search_machines) { key ->
                services.select(NewSessionPage.Machine, key)
                closePicker()
            }
            Picker.Worktree -> buildList(
                content,
                model.worktreeFixedItems,
                model.worktreeItems,
                model.worktreeKey,
                if (model.picksWorkspaces) R.string.home_new_session_search_workspaces else R.string.home_new_session_search_worktrees,
            ) { key ->
                services.select(NewSessionPage.Worktree, key)
                closePicker()
            }
            Picker.Agent -> buildList(content, emptyList(), model.agentItems, model.agentKey, R.string.home_new_session_search_agents) { key ->
                services.select(NewSessionPage.Agent, key)
                closePicker()
            }
            Picker.Path -> buildPath(content)
            Picker.Settings -> {
                val page = settingsPage
                val setting = page?.let { kind -> model.settings.find { it.kind == kind } }
                if (setting != null) buildSettingsPage(content, setting) else buildSettings(content)
            }
        }
        root?.requestLayout()
    }

    /** PickerContent, embedded: a search field past four rows, the fixed rows, a divider, then the rest under their section headings. */
    private fun buildList(
        content: NewSessionPickerContentView,
        fixed: List<NewSessionScreenModel.Item>,
        items: List<NewSessionScreenModel.Item>,
        selected: String?,
        search: Int?,
        extra: List<NewSessionPickerContentView.Row> = emptyList(),
        select: (String) -> Unit,
    ) {
        val header = extra.toMutableList()
        val searches = search != null && fixed.size + items.size > 4
        if (searches && search != null) {
            val row = searchRow ?: makeSearchRow(context.getString(search))
            searchRow = row
            header += NewSessionPickerContentView.Row.Field(row)
        } else {
            searchText = ""
        }
        val query = searchText.lowercase()
        val filtered = if (searches && query.isNotEmpty()) items.filter { it.label.lowercase().contains(query) } else items
        val list = fixed.map<NewSessionScreenModel.Item, NewSessionPickerContentView.Row> { NewSessionPickerContentView.Row.Option(optionRow(it, selected, select)) }.toMutableList()
        if (fixed.isNotEmpty() && filtered.isNotEmpty()) list += NewSessionPickerContentView.Row.Divider(DividerView(context))
        var section: String? = null
        for (item in filtered) {
            val heading = item.section
            if (heading != null && heading != section) list += NewSessionPickerContentView.Row.Text(newSessionLabel(context, TextStyles.newSessionSection, heading))
            section = item.section
            list += NewSessionPickerContentView.Row.Option(optionRow(item, selected, select))
        }
        if (filtered.isEmpty() && query.isNotEmpty()) {
            list += NewSessionPickerContentView.Row.Empty(newSessionLabel(context, TextStyles.newSessionEmpty, context.getString(R.string.home_new_session_no_results), lines = 0, centred = true))
        }
        content.setRows(header, list)
    }

    private fun makeSearchRow(placeholder: String) = NewSessionFieldRow(context, Icon.ionicons("search"), placeholder, isPath = false).also { row ->
        row.field.onChange = { text ->
            searchText = text
            rebuildPicker()
        }
    }

    private fun optionRow(item: NewSessionScreenModel.Item, selected: String?, select: (String) -> Unit): NewSessionOptionRow {
        val isSelected = item.key == selected
        val detail = item.status?.let(::statusText) ?: item.subtitle
        val row = NewSessionOptionRow(
            context,
            Icon.octicons(if (isSelected) "check-circle-fill" else "circle"),
            16.0,
            if (isSelected) Palette.text else Palette.textSecondary,
            NewSessionOptionRow.Text.option(item.label),
            detail?.let(NewSessionOptionRow.Text::detail),
        )
        row.isDimmed = item.isDimmed
        row.isSelected = isSelected
        row.onTap { select(item.key) }
        return row
    }

    /** PathPickerContent, embedded: the field, what it resolves to, then the computer's recent places. */
    private fun buildPath(content: NewSessionPickerContentView) {
        val field = pathField ?: makePathField()
        pathField = field
        if (!field.field.hasFocus() && field.field.string != model.pathValue) setPathField(model.pathValue)
        val header = mutableListOf<NewSessionPickerContentView.Row>(NewSessionPickerContentView.Row.Field(field))
        val isCustomPath = model.pathValue.isNotBlank() && model.matchedPathKey == null
        val projectName = model.selectedProjectName
        if (projectName != null) {
            header += NewSessionPickerContentView.Row.Text(newSessionLabel(context, TextStyles.newSessionMeta, context.getString(R.string.home_new_session_using_project, projectName), lines = 0))
        } else if (isCustomPath) {
            header += NewSessionPickerContentView.Row.Text(newSessionLabel(context, TextStyles.newSessionMeta, context.getString(R.string.home_new_session_using_custom_path), lines = 0))
        }
        header += NewSessionPickerContentView.Row.Text(newSessionLabel(context, TextStyles.newSessionSection, context.getString(R.string.home_new_session_recent)))

        val list = model.pathItems.map<NewSessionScreenModel.Item, NewSessionPickerContentView.Row> { item ->
            val isSelected = model.selectedProjectKey?.let { item.key == it } ?: (item.key == model.matchedPathKey)
            val row = NewSessionOptionRow(
                context,
                Icon.ionicons("folder-outline"),
                16.0,
                Palette.textSecondary,
                NewSessionOptionRow.Text.option(item.label),
                null,
                trailing = if (isSelected) Icon.ionicons("checkmark-circle") else null,
                trailingSize = if (isSelected) 18.0 else null,
            )
            row.isSelected = isSelected
            row.onTap { pickPath(item) }
            NewSessionPickerContentView.Row.Option(row)
        }.toMutableList()
        if (list.isEmpty()) {
            list += NewSessionPickerContentView.Row.Empty(newSessionLabel(context, TextStyles.newSessionEmpty, context.getString(R.string.home_new_session_no_recent_projects), lines = 0, centred = true))
        }
        content.setRows(header, list)
    }

    private fun makePathField() = NewSessionFieldRow(context, Icon.ionicons("folder-outline"), context.getString(R.string.home_new_session_path_placeholder), isPath = true).also { row ->
        row.field.onChange = { text -> if (!settingPathField) services.selectPath(text) }
        row.field.onReturn = { closePicker() }
    }

    private fun setPathField(value: String) {
        val field = pathField?.field ?: return
        settingPathField = true
        field.setText(value)
        field.setSelection(value.length)
        settingPathField = false
    }

    /** `handleSuggestionPress`: a catalog project is picked whole; a directory goes into the field to be adjusted. */
    private fun pickPath(item: NewSessionScreenModel.Item) {
        val projectId = item.projectId
        if (projectId != null) {
            services.select(NewSessionPage.Project, projectPlaceKey(projectId))
            closePicker()
            return
        }
        val value = model.fieldValue(item)
        services.selectPath(value)
        setPathField(value)
        pathField?.field?.focus()
    }

    /** ComposerSettingsContent: a row per setting, each opening its page. */
    private fun buildSettings(content: NewSessionPickerContentView) {
        content.listMaxHeight = null
        content.paddingTop = NewSessionPickerContentLayout.Metrics.settingsPaddingTop
        content.paddingBottom = NewSessionPickerContentLayout.Metrics.settingsPaddingBottom
        val list = mutableListOf<NewSessionPickerContentView.Row>(
            NewSessionPickerContentView.Row.Text(newSessionLabel(context, TextStyles.newSessionSection, context.getString(R.string.settings_title))),
        )
        for (setting in model.settings) {
            val row = NewSessionOptionRow(
                context,
                Icon.ionicons(setting.icon),
                17.0,
                Palette.textSecondary,
                NewSessionOptionRow.Text(settingTitle(setting.kind), TextStyles.newSessionSettingName, Palette.textSecondary, 0),
                NewSessionOptionRow.Text(setting.value, TextStyles.newSessionOption, Palette.text, 1),
                trailing = Icon.ionicons("chevron-forward"),
                trailingSize = 15.0,
                trailingTint = Palette.groupedChevron,
            )
            row.onTap {
                settingsPage = setting.kind
                rebuildPicker()
            }
            list += NewSessionPickerContentView.Row.Option(row)
        }
        content.setRows(emptyList(), list)
    }

    /** ComposerSettingsPickerContent: back, the setting's name, its options. */
    private fun buildSettingsPage(content: NewSessionPickerContentView, setting: NewSessionScreenModel.Setting) {
        content.paddingBottom = 2 * NewSessionPickerContentLayout.Metrics.embeddedPaddingBottom
        val back = NewSessionBackRow(context, settingTitle(setting.kind))
        back.back.onTap {
            settingsPage = null
            rebuildPicker()
        }
        buildList(content, emptyList(), setting.items, setting.selectedKey, null, listOf(NewSessionPickerContentView.Row.Back(back))) { key ->
            services.select(
                when (setting.kind) {
                    NewSessionScreenModel.Setting.Kind.Permission -> NewSessionPage.Permission
                    NewSessionScreenModel.Setting.Kind.Model -> NewSessionPage.Model
                    NewSessionScreenModel.Setting.Kind.Effort -> NewSessionPage.Effort
                },
                key,
            )
            settingsPage = null
            rebuildPicker()
        }
    }

    private fun settingTitle(kind: NewSessionScreenModel.Setting.Kind): String = context.getString(
        when (kind) {
            NewSessionScreenModel.Setting.Kind.Permission ->
                if (model.isCodex) R.string.agent_input_codex_permission_mode_title else R.string.agent_input_permission_mode_title
            NewSessionScreenModel.Setting.Kind.Model -> R.string.agent_input_model_title
            NewSessionScreenModel.Setting.Kind.Effort -> R.string.agent_input_effort_title
        },
    )

    /** `status.online`, or `status.lastSeen` with `formatLastSeen`. */
    private fun statusText(status: NewSessionScreenModel.MachineStatus): String = when (status) {
        NewSessionScreenModel.MachineStatus.Online -> context.getString(R.string.status_online)
        is NewSessionScreenModel.MachineStatus.LastSeen -> context.getString(R.string.status_last_seen, lastSeen(status.lastSeen))
    }

    private fun lastSeen(lastSeen: ComposerStatus.LastSeen): String {
        val resources = context.resources
        return when (lastSeen) {
            ComposerStatus.LastSeen.JustNow -> context.getString(R.string.time_just_now)
            is ComposerStatus.LastSeen.Minutes -> resources.getQuantityString(R.plurals.time_minutes_ago, lastSeen.count, lastSeen.count)
            is ComposerStatus.LastSeen.Hours -> resources.getQuantityString(R.plurals.time_hours_ago, lastSeen.count, lastSeen.count)
            is ComposerStatus.LastSeen.Days -> resources.getQuantityString(R.plurals.session_history_days_ago, lastSeen.count, lastSeen.count)
            is ComposerStatus.LastSeen.Date -> {
                val date = Date(lastSeen.ms)
                val year = Calendar.getInstance().apply { time = date }.get(Calendar.YEAR)
                val sameYear = year == Calendar.getInstance().get(Calendar.YEAR)
                val locale = resources.configuration.locales[0]
                val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, if (sameYear) "MMMd" else "yMMMd")
                java.text.SimpleDateFormat(pattern, locale).format(date)
            }
        }
    }

    private fun hideKeyboard() {
        val view = root ?: return
        // The root takes the focus: cleared, it would go on to the composer and keep the keyboard up.
        view.isFocusableInTouchMode = true
        view.requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(view.windowToken, 0)
    }

    // Start

    /**
     * `handleSend`: the draft started as it is, and the session opened in
     * this screen's place. The prompt is optional. One at a time: Send is
     * busy meanwhile.
     */
    private fun send() {
        if (!model.canSend || launching) return
        closePicker()
        launching = true
        startedHere = true
        render()
        scope.launch {
            val result = try {
                starter.start(confirmDirectory = ::confirmDirectoryCreation)
            } finally {
                launching = false
            }
            render()
            when (result) {
                is StartResult.Started -> openSession(result.sessionId)
                is StartResult.Refused -> showStartError(result.refusal.message)
                is StartResult.Failed -> showStartError(result.message)
                is StartResult.NotSent -> Alerts.show(context, context.getString(R.string.image_upload_not_supported_title), context.getString(R.string.image_upload_not_supported_message))
                StartResult.Abandoned -> Unit
            }
        }
    }

    /** `router.back()` then `navigateToSession`: the session takes this screen's place on the stack. */
    private fun openSession(sessionId: String) {
        startedHere = false
        stack?.replaceTop(ChatScreen(context, graph, sessionId))
    }

    private fun showStartError(message: String) = Alerts.show(context, context.getString(R.string.common_error), message)

    private suspend fun confirmDirectoryCreation(directory: String): Boolean = Alerts.confirm(
        context,
        context.getString(R.string.home_create_directory_title),
        context.getString(R.string.home_create_directory_message, directory),
        confirmTitle = context.getString(R.string.common_create),
    )

    private class DividerView(context: Context) : View(context), ThemeAware {
        init { applyTheme() }

        override fun applyTheme() = setBackgroundColor(Theme.color(Palette.divider))
    }

    companion object {
        /** The gap between back and the title capsule. */
        private const val TITLE_GAP = 8.0
    }
}
