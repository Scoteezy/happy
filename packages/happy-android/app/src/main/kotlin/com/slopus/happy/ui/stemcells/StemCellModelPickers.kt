package com.slopus.happy.ui.stemcells

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import com.slopus.happy.R
import com.slopus.happy.chat.ComposerChipSet
import com.slopus.happy.layout.Rect
import com.slopus.happy.layout.StemCellPickerMetrics
import com.slopus.happy.layout.stemcells.StemCellPickerPanelLayout
import com.slopus.happy.stemcells.AgentMode
import com.slopus.happy.stemcells.StemCellAccountUsage
import com.slopus.happy.stemcells.StemCellModelCatalog
import com.slopus.happy.stemcells.StemCellModelMenus
import com.slopus.happy.ui.composer.AgentInputCardView
import com.slopus.happy.ui.composer.AgentInputChip
import com.slopus.happy.ui.view.px
import com.slopus.happy.ui.view.setFrame
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import android.text.format.DateFormat as AndroidDateFormat

/**
 * The composer's model control as desktop's `ComposerModelControl`
 * (happy-ios `StemCellModelPickers`), with the phone's one difference: each
 * chip opens its own list (the model chip the models, the effort chip the
 * efforts) rather than a menu of both. A service's heading opens its
 * accounts, stacked over the models in place.
 */
object StemCellModelPickers {
    enum class Kind(val raw: String) { Model("model"), Provider("provider"), Effort("effort"), Permission("permission") }

    data class Chips(val provider: String, val model: String, val effort: String?)

    /** The chips' labels for a mode: provider, model, effort. */
    fun chips(context: Context, menus: StemCellModelMenus?): Chips {
        if (menus == null || (menus.services.isEmpty() && menus.current == null)) {
            return Chips("", context.getString(R.string.stem_models_not_configured), null)
        }
        val service = menus.selectedService
        val provider = service?.let {
            if (it.accounts.size > 1) context.getString(R.string.stem_service_account, it.label, menus.selectedAccount?.label ?: it.label) else it.label
        } ?: menus.selection.providerId
        val effort = menus.current?.let { current -> if (current.efforts.isEmpty()) null else menus.effort?.let(StemCellModelMenus::effortName) }
        return Chips(provider, menus.current?.label ?: menus.selection.modelId, effort)
    }

    /**
     * The card's model control, beside send as desktop's composer has it:
     * the model and its effort, each opening its own list. With nothing to
     * list a press asks for the models again.
     */
    fun configure(card: AgentInputCardView, menus: StemCellModelMenus?) {
        val context = card.context
        val labels = chips(context, menus)
        fun chip(kind: Int, value: String) = ComposerChipSet.Chip(
            label = value,
            accessibilityLabel = context.getString(R.string.stem_chip_label, context.getString(kind), value),
            sections = null,
            selectedKey = null,
            opensPicker = true,
        )
        card.configuration = card.configuration.copy(
            permission = null,
            model = chip(R.string.stem_model, labels.model),
            effort = labels.effort?.let { chip(R.string.stem_effort, it) },
        )
    }

    /** A pick's key: what it changes, then which. */
    fun key(kind: Kind, id: String) = "${kind.raw}\t$id"

    /** The modes a message may run with, from least to most it may do. */
    val permissionModes = listOf("read_only", "workspace_write", "auto", "full_access")

    // Lists

    /**
     * The model list: a notice about the account first, then each service
     * under its heading (its name, the account it lists, that account's
     * windows, and a chevron to its accounts), a hairline between them, the
     * selected model checked; under them, the benchmarks link.
     */
    fun modelContent(context: Context, menus: StemCellModelMenus, usage: Map<String, StemCellAccountUsage>): StemCellPickerContent {
        val lines = ArrayList<StemCellPickerContent.Line>()
        fun block() {
            if (lines.isNotEmpty()) lines += StemCellPickerContent.Line.Separator
        }
        notice(context, menus)?.let { lines += StemCellPickerContent.Line.Notice(it, warning = menus.notice != StemCellModelMenus.Notice.Hidden) }
        // A selection the lists don't offer still names its model, under the notice that says why.
        val listed = menus.services.any { service ->
            service.shown?.id == menus.selection.providerId && service.shown?.models?.any { it.id == menus.selection.modelId } == true
        }
        val current = menus.current
        if (current != null && !listed) {
            block()
            val provider = chips(context, menus).provider
            lines += StemCellPickerContent.Line.Heading(provider, null, emptyList(), null, provider)
            lines += StemCellPickerContent.Line.Option(key(Kind.Model, "${menus.selection.providerId}\n${current.id}"), current.label, checked = true, disabled = true, gauge = null)
        }
        for (service in menus.services) {
            block()
            val account = service.shown
            val windows = account?.let { usage[it.id] }?.let(::windows) ?: emptyList()
            val gauges = windows.map { gauge(context, it, it.blocking) }
            val label = (
                listOf(context.getString(R.string.stem_heading_account, service.label, account?.label ?: service.account)) +
                    windows.map { context.getString(R.string.stem_window_left, window(context, it.kind), it.left) }
                ).joinToString(", ")
            lines += StemCellPickerContent.Line.Heading(service.label, account?.label, gauges, if (service.accounts.isEmpty()) null else service.id, label)
            for (model in account?.models ?: emptyList()) {
                lines += StemCellPickerContent.Line.Option(
                    key(Kind.Model, "${account?.id ?: service.account}\n${model.id}"), model.label,
                    checked = account?.id == menus.selection.providerId && model.id == menus.selection.modelId,
                    disabled = model.disabled, gauge = null,
                )
            }
        }
        return StemCellPickerContent(
            id = "stem-picker-models", label = context.getString(R.string.stem_select_model), lines = lines,
            footer = listOf(StemCellPickerContent.Line.Separator, StemCellPickerContent.Line.Link("benchmarks", context.getString(R.string.stem_latest_benchmarks))),
            minWidth = StemCellPickerMetrics.modelsMinWidth, maxWidth = StemCellPickerMetrics.modelsMaxWidth,
        )
    }

    /** The selected model's efforts, least to most, the current one checked. */
    fun effortContent(context: Context, menus: StemCellModelMenus): StemCellPickerContent {
        val current = menus.effort
        return StemCellPickerContent(
            id = "stem-picker-efforts", label = context.getString(R.string.stem_select_effort),
            lines = (menus.current?.efforts ?: emptyList()).map { level ->
                StemCellPickerContent.Line.Option(key(Kind.Effort, level), StemCellModelMenus.effortName(level), checked = level == current, disabled = false, gauge = null)
            },
            minWidth = StemCellPickerMetrics.effortsMinWidth, maxWidth = StemCellPickerMetrics.modelsMaxWidth,
        )
    }

    /** A service's accounts, the one it lists checked, each with what is left of its week; under them, that account's windows. */
    fun accountsContent(
        context: Context,
        service: StemCellModelMenus.Service,
        menus: StemCellModelMenus,
        usage: Map<String, StemCellAccountUsage>,
        now: Long = System.currentTimeMillis(),
    ): StemCellPickerContent {
        val lines = ArrayList<StemCellPickerContent.Line>()
        for (account in service.accounts) {
            val reading = usage[account.id]
            lines += StemCellPickerContent.Line.Option(
                key(Kind.Provider, account.id), account.label,
                checked = account.id == service.shown?.id, disabled = menus.model(account) == null,
                gauge = reading?.let { r -> r.shown?.let { gauge(context, it, r.limited) } },
            )
        }
        service.shown?.let { shown ->
            lines += StemCellPickerContent.Line.Separator
            lines += StemCellPickerContent.Line.Windows(details(context, usage[shown.id], now))
        }
        return StemCellPickerContent(
            id = "stem-picker-accounts", label = context.getString(R.string.stem_accounts_of, service.label), lines = lines,
            minWidth = StemCellPickerMetrics.accountsMinWidth, maxWidth = StemCellPickerMetrics.accountsMaxWidth,
        )
    }

    private fun windows(usage: StemCellAccountUsage) = listOfNotNull(usage.week, usage.session)

    private fun window(context: Context, kind: StemCellAccountUsage.Window.Kind) = context.getString(
        when (kind) {
            StemCellAccountUsage.Window.Kind.Week -> R.string.stem_window_week
            StemCellAccountUsage.Window.Kind.Session -> R.string.stem_window_session
        },
    )

    private fun gauge(context: Context, window: StemCellAccountUsage.Window, blocked: Boolean) =
        StemCellPickerContent.Gauge(context.getString(R.string.stem_usage_left, window.left), window.left, StemCellAccountUsage.tone(window.left, blocked))

    /**
     * `ComposerModelAccountUsageDetails`: the week (leading), then the
     * session, each with when it starts over and what is left; then how
     * current the numbers are.
     */
    fun details(context: Context, usage: StemCellAccountUsage?, now: Long): List<StemCellPickerContent.Window> {
        val lines = ArrayList<StemCellPickerContent.Window>()
        val windows = usage?.let(::windows) ?: emptyList()
        for (window in windows) {
            lines += StemCellPickerContent.Window(
                label = window(context, window.kind),
                resets = window.resetsAt?.let { context.getString(R.string.stem_resets, dayTime(context, it.toLong(), now)) },
                gauge = gauge(context, window, window.blocking),
                primary = window.kind == StemCellAccountUsage.Window.Kind.Week,
            )
        }
        if (windows.isEmpty()) lines += StemCellPickerContent.Window(context.getString(R.string.stem_usage_unknown))
        usage ?: return lines
        if (usage.limited) {
            val label = usage.limitResets?.let { context.getString(R.string.stem_limit_resets, dayTime(context, it.toLong(), now)) } ?: context.getString(R.string.stem_limit_reached)
            lines += StemCellPickerContent.Window(label, critical = true)
        }
        val updated = usage.updatedAt?.toLong()
        lines += StemCellPickerContent.Window(
            when {
                usage.refreshing -> context.getString(R.string.stem_updating)
                usage.refreshError != null ->
                    updated?.let { context.getString(R.string.stem_updated_failed, clock(context, it, now)) } ?: context.getString(R.string.stem_couldnt_refresh)
                updated != null -> context.getString(R.string.stem_updated, age(context, updated, now))
                else -> return lines
            },
        )
        // A window measured well before the rest says when it is from.
        val newest = max(updated ?: Long.MIN_VALUE, windows.mapNotNull { it.capturedAt?.toLong() }.maxOrNull() ?: Long.MIN_VALUE)
        for (window in windows) {
            val captured = window.capturedAt?.toLong() ?: continue
            if (newest - captured > 600_000) {
                lines += StemCellPickerContent.Window(context.getString(R.string.stem_window_as_of, window(context, window.kind), clock(context, captured, now)))
            }
        }
        return lines
    }

    private fun notice(context: Context, menus: StemCellModelMenus): String? = when (val notice = menus.notice) {
        StemCellModelMenus.Notice.Hidden -> context.getString(R.string.stem_hidden_account)
        is StemCellModelMenus.Notice.Removed -> context.getString(R.string.stem_account_removed, notice.name)
        is StemCellModelMenus.Notice.Off -> context.getString(R.string.stem_account_off, notice.name)
        null -> null
    }

    // Times

    private fun time(context: Context, at: Long) = AndroidDateFormat.getTimeFormat(context).format(Date(at))

    private fun weekday(at: Long) = SimpleDateFormat("EEE", Locale.getDefault()).format(Date(at))

    /** `usageAge`: how long ago, in a word. */
    fun age(context: Context, at: Long, now: Long): String {
        val minutes = ((now - at) / 60_000).toInt()
        if (minutes < 1) return context.getString(R.string.stem_age_just_now)
        if (minutes < 60) return context.getString(R.string.stem_age_minutes, minutes)
        val hours = minutes / 60
        if (hours < 20) return context.getString(R.string.stem_age_hours, hours)
        return context.getString(R.string.stem_age_at, clock(context, at, now))
    }

    /** `clockTime`: the time, with its weekday when it isn't within the day. */
    fun clock(context: Context, at: Long, now: Long): String =
        if (abs(now - at) < 20 * 3_600_000L) time(context, at) else "${weekday(at)} ${time(context, at)}"

    /** `localDayTime`: today's time alone; any other day leads with its weekday. */
    fun dayTime(context: Context, at: Long, now: Long): String {
        val a = Calendar.getInstance().apply { timeInMillis = at }
        val b = Calendar.getInstance().apply { timeInMillis = now }
        val sameDay = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        return if (sameDay) time(context, at) else "${weekday(at)} ${time(context, at)}"
    }

    // Picks

    /** What a pick makes the mode; null leaves it as it is (the benchmarks link, a key that isn't a pick). */
    fun pick(key: String, menus: StemCellModelMenus, catalog: StemCellModelCatalog): AgentMode? {
        val parts = key.split('\t', limit = 2)
        if (parts.size != 2) return null
        val kind = Kind.entries.firstOrNull { it.raw == parts[0] } ?: return null
        val id = parts[1]
        val current = menus.selection
        return when (kind) {
            Kind.Model -> {
                val pair = id.split('\n', limit = 2)
                if (pair.size != 2) return null
                val same = pair[0] == current.providerId && pair[1] == current.modelId
                catalog.select(pair[0], pair[1], if (same) current.effort else null, current)
            }
            Kind.Provider -> {
                val account = menus.services.flatMap { it.accounts }.firstOrNull { it.id == id } ?: return null
                val model = menus.model(account) ?: return null
                catalog.select(account.id, model.id, current.effort, current)
            }
            Kind.Effort -> current.copy(effort = id)
            Kind.Permission -> if (id in permissionModes) current.copy(permissionMode = id) else null
        }
    }

    const val BENCHMARKS = "https://happy.engineering/model-benchmarks"

    /** A pick from a list: the benchmarks link opens, anything else that changes the mode comes back as the new one. */
    fun choose(context: Context, key: String, state: StemCellModelState, mode: AgentMode?, onPick: (AgentMode) -> Unit) {
        if (key == "benchmarks") {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BENCHMARKS))) }
            return
        }
        val catalog = state.catalog ?: return
        mode ?: return
        val menus = StemCellModelMenus(catalog, mode)
        pick(key, menus, catalog)?.takeIf { it != mode }?.let(onPick)
    }

    /** A list opened: the readings of the accounts it lists are asked for again, as desktop asks as its menu opens. */
    fun opened(state: StemCellModelState, mode: AgentMode?) {
        val catalog = state.catalog
        if (catalog == null || mode == null) return state.load()
        state.refreshUsage(listedAccounts(StemCellModelMenus(catalog, mode)))
    }

    /** `composerModelListedAccounts`: every account the lists name. */
    fun listedAccounts(menus: StemCellModelMenus): List<String> {
        val listed = menus.services.flatMap { it.accounts }.map { it.id }.toMutableList()
        if (menus.selection.providerId !in listed) listed += menus.selection.providerId
        return listed
    }
}

/**
 * One chip's list open over the screen, and a service's accounts stacked
 * over it (happy-ios `StemCellPickerPresenter`): placed over the chip,
 * opened on the picked line, redrawn as the selection and the readings
 * change, closed by a pick, a touch outside or back.
 */
class StemCellPickerPresenter(
    val list: List,
    private val card: AgentInputCardView,
    private var menus: StemCellModelMenus,
    private var usage: Map<String, StemCellAccountUsage>,
    private val onChoose: (String) -> Unit,
) {
    enum class List { Model, Effort }

    private val context = card.context
    private val overlay = StemCellPickerOverlay(context)
    /** The service whose accounts are stacked over the models. */
    private var accounts: String? = null
    /** An account was switched: the models open again on the one picked. */
    private var reopens = false
    var onClose: (() -> Unit)? = null
    /** Where the screen's chrome ends, in the host's space (dp): a panel stays under it. */
    var top: (() -> Double)? = null
    private var host: ViewGroup? = null

    init {
        overlay.onOutside = { outside() }
        overlay.onLayout = { place(opening = false) }
    }

    val isOpen: Boolean get() = overlay.parent != null

    /** Over everything in [host], the list's first frame on its picked line. */
    fun show(host: ViewGroup) {
        this.host = host
        host.addView(overlay)
        overlay.setFrame(Rect(0.0, 0.0, px(host.width), px(host.height)))
        val panel = makePanel(level = 0)
        overlay.push(panel)
        place(opening = true)
        panel.grow()
    }

    fun update(menus: StemCellModelMenus?, usage: Map<String, StemCellAccountUsage>) {
        if (menus == null) return close(animated = true)
        if (menus.selection == this.menus.selection && menus.services == this.menus.services && menus.current == this.menus.current && usage == this.usage) return
        this.menus = menus
        this.usage = usage
        if (list == List.Effort && menus.current?.efforts.isNullOrEmpty()) return close(animated = true)
        place(opening = false)
    }

    /** The chip moved (the keyboard, the card growing), or the screen: the panels follow. */
    fun follow() {
        val host = host ?: return
        if (!isOpen) return
        overlay.setFrame(Rect(0.0, 0.0, px(host.width), px(host.height)))
        place(opening = false)
    }

    fun close(animated: Boolean) {
        if (!isOpen) return
        while (overlay.panels.size > 1) overlay.pop(animated)
        overlay.pop(animated)
        val overlay = overlay
        val host = host
        if (animated) {
            overlay.isEnabled = false
            overlay.postDelayed({ host?.removeView(overlay) }, StemCellPickerOverlay.POP_DURATION)
        } else {
            host?.removeView(overlay)
        }
        onClose?.invoke()
    }

    private fun makePanel(level: Int) = StemCellPickerPanelView(context).also { panel ->
        panel.onTap = { line -> tapped(line, level) }
    }

    private fun baseContent() = when (list) {
        List.Model -> StemCellModelPickers.modelContent(context, menus, usage)
        List.Effort -> StemCellModelPickers.effortContent(context, menus)
    }

    /**
     * The panels where they stand now: the list over its chip, the accounts
     * over the list. Opening the list, it stands on its picked line;
     * stacking the accounts, they stand on theirs.
     */
    private fun place(opening: Boolean, stacking: Boolean = false) {
        val base = overlay.panels.firstOrNull() ?: return
        val width = px(overlay.width)
        val height = px(overlay.height)
        val top = max(0.0, top?.invoke() ?: 0.0)
        val bounds = Rect(0.0, top, width, max(0.0, height - top))
        val anchor = card.chipFrame(if (list == List.Model) AgentInputChip.Model else AgentInputChip.Effort, overlay)
            ?: run {
                val at = IntArray(2).also(card::getLocationInWindow)
                val origin = IntArray(2).also(overlay::getLocationInWindow)
                Rect(px(at[0] - origin[0]), px(at[1] - origin[1]), px(card.width), px(card.height))
            }
        val content = baseContent()
        val layout = layout(content, bounds) { items, footer, w ->
            StemCellPickerPanelLayout.over(items, footer, w, anchor, bounds, content.selected)
        }
        base.apply(content, layout, opening || reopens)
        reopens = false
        if (overlay.panels.size < 2) return
        val service = menus.services.firstOrNull { it.id == accounts } ?: return closeAccounts()
        val stacked = StemCellModelPickers.accountsContent(context, service, menus, usage)
        val stackedLayout = layout(stacked, bounds) { items, footer, w ->
            StemCellPickerPanelLayout.stackedOver(items, footer, w, layout.frame, bounds, stacked.selected)
        }
        overlay.panels[1].apply(stacked, stackedLayout, stacking)
    }

    private fun layout(
        content: StemCellPickerContent,
        bounds: Rect,
        make: (kotlin.collections.List<StemCellPickerPanelLayout.Item>, kotlin.collections.List<StemCellPickerPanelLayout.Item>, Double) -> StemCellPickerPanelLayout,
    ): StemCellPickerPanelLayout {
        val width = min(StemCellPickerType.panelWidth(content), bounds.width - 2 * StemCellPickerMetrics.margin)
        return make(content.lines.map { StemCellPickerType.item(it, width) }, content.footer.map { StemCellPickerType.item(it, width) }, width)
    }

    private fun tapped(line: StemCellPickerContent.Line, level: Int) {
        when (line) {
            is StemCellPickerContent.Line.Heading -> line.opens?.let(::openAccounts)
            is StemCellPickerContent.Line.Option -> {
                StemCellsHaptics.play(card, StemCellsHaptics.Moment.Pick)
                if (level > 0) {
                    // An account is not the last choice: the models stay, on that account's, open on the one it switched to.
                    reopens = true
                    closeAccounts()
                    onChoose(line.key)
                } else {
                    onChoose(line.key)
                    close(animated = true)
                }
            }
            is StemCellPickerContent.Line.Link -> {
                onChoose(line.key)
                close(animated = true)
            }
            else -> Unit
        }
    }

    private fun openAccounts(service: String) {
        if (accounts == service) return closeAccounts()
        if (overlay.panels.size > 1) overlay.pop(animated = false)
        accounts = service
        val panel = makePanel(level = 1)
        overlay.push(panel)
        // The list stays where it was scrolled.
        place(opening = false, stacking = true)
        panel.grow()
        overlay.panels.first().importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    private fun closeAccounts() {
        accounts = null
        if (overlay.panels.size < 2) return
        overlay.pop(animated = true)
        overlay.panels.first().importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }

    /** A touch outside, or back: the stacked accounts first, then the list. */
    fun outside() {
        if (overlay.panels.size > 1) closeAccounts() else close(animated = true)
    }

    /** What the harness reports: the open panels, bottom first. */
    val probe: String get() = overlay.panels.joinToString("+") { it.content?.id?.removePrefix("stem-picker-") ?: "?" }
}
