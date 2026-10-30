package com.slopus.happy.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.view.View
import android.widget.PopupMenu
import androidx.core.content.ContextCompat
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.slopus.happy.AppGraph
import com.slopus.happy.BuildConfig
import com.slopus.happy.MainActivity
import com.slopus.happy.R
import com.slopus.happy.attachments.AttachmentPictures
import com.slopus.happy.auth.AuthState
import com.slopus.happy.icons.Icon
import com.slopus.happy.images.HappyImageLoader
import com.slopus.happy.layout.Palette
import com.slopus.happy.model.Profile
import com.slopus.happy.model.settings.AccountPageModel
import com.slopus.happy.model.settings.AccountSwitcherEntry
import com.slopus.happy.model.settings.PushPermission
import com.slopus.happy.model.settings.PushPermissionStatus
import com.slopus.happy.model.settings.PushRegistration
import com.slopus.happy.model.settings.PushText
import com.slopus.happy.model.settings.PushToken
import com.slopus.happy.model.settings.PushTokensApi
import com.slopus.happy.push.pushRegistration
import com.slopus.happy.ui.components.Alerts
import com.slopus.happy.ui.components.AvatarStyle
import com.slopus.happy.ui.components.AvatarView
import com.slopus.happy.ui.components.GroupedListView
import com.slopus.happy.ui.components.ItemGroupView
import com.slopus.happy.ui.components.ItemIcon
import com.slopus.happy.ui.components.ItemRowView
import com.slopus.happy.ui.nav.BarScreen
import com.slopus.happy.ui.onboarding.WelcomeScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings › Account (app/(app)/settings/account.tsx, happy-ios
 * `SettingsAccountViewController`): account information, linking another
 * device, the profile and connected services, the secret key backup,
 * analytics, push notifications and logout, under the account switcher:
 * every account signed in here, a tap switching to it, a long press
 * signing it out, and Add Account.
 */
class AccountScreen(context: Context, private val graph: AppGraph) : BarScreen(context) {
    private val list = GroupedListView(context)
    private val toggles = SettingsToggleRows(context)
    private var profile: Profile? = null

    private var revealsSecret = false
    private var copiedRecently = false
    private var copiedReset: Job? = null
    private var linkingDevice = false
    private var disconnectingGitHub = false
    private var disconnectingService: String? = null
    /** Pictures by URL or thumbhash, decoded; the page is rebuilt when one arrives. */
    private val pictures = HashMap<String, Drawable?>()

    private var pushTokens: List<PushToken> = emptyList()
    private var pushPermission: PushPermission? = null
    private var currentPushToken: String? = null
    private var loadingPush = false
    private var requestingPermission = false
    private var refreshingToken = false
    private var deletingToken: String? = null
    /** The account a switch is under way to. */
    private var busyAccount: String? = null
    private val pushApi: PushTokensApi get() {
        val requests = checkNotNull(graph.currentRequests()) { "Not signed in" }
        return PushTokensApi(requests.http) { requests.token }
    }
    private val registration: PushRegistration? by lazy {
        activity()?.let { pushRegistration(context, graph, it.notificationPermission) }
    }

    private val signedIn get() = graph.auth.state.value as? AuthState.SignedIn

    override fun createContent(): View {
        bar.title = context.getString(R.string.settings_account)
        return list
    }

    override fun viewCreated(view: View) {
        super.viewCreated(view)
        scope.launch {
            graph.profiles().collect {
                profile = it
                rebuild()
            }
        }
        scope.launch { graph.settings.settings.collect { rebuild() } }
        scope.launch { graph.auth.registry.collect { rebuild() } }
    }

    override fun onAppear() {
        super.onAppear()
        rebuild()
        // useFocusEffect: the push rows are fetched every time the page shows.
        loadPushSettings()
    }

    override fun onInsetsChanged() {
        super.onInsetsChanged()
        list.safeArea = contentSafeArea
    }

    private fun string(id: Int) = context.getString(id)

    private fun rebuild() {
        val signedIn = signedIn ?: return
        val page = AccountPageModel(profile)
        val blue = Palette.iconBlue
        val sections = mutableListOf<View>(accountSwitcher(signedIn))

        val anonId = signedIn.encryption.anonId
        val publicId = signedIn.publicId
        val analyticsId = ItemRowView(context, string(R.string.settings_account_anonymous_id), detail = anonId).apply {
            onLongPress = { copy(anonId, string(R.string.settings_account_anonymous_id)) }
            showsChevron = false
        }
        val publicIdRow = ItemRowView(context, string(R.string.settings_account_public_id), detail = publicId ?: string(R.string.settings_account_not_available)).apply {
            if (publicId != null) onLongPress = { copy(publicId, string(R.string.settings_account_public_id)) }
            showsChevron = false
        }
        val link = ItemRowView(
            context,
            string(R.string.settings_account_link_new_device),
            subtitle = string(if (linkingDevice) R.string.common_scanning else R.string.settings_account_link_new_device_subtitle),
            icon = ItemIcon.Glyph(Icon.ionicons("qr-code-outline"), blue),
        ) { linkDevice() }
        link.showsChevron = false
        sections += ItemGroupView(
            context,
            title = string(R.string.settings_account_account_information),
            rows = listOf(
                ItemRowView(context, string(R.string.settings_account_status), detail = string(R.string.settings_account_status_active)),
                analyticsId,
                publicIdRow,
                disabled(link, linkingDevice),
            ),
        )

        if (page.showsProfile) {
            val rows = mutableListOf<ItemRowView>()
            page.displayName?.let { rows += ItemRowView(context, string(R.string.settings_account_name), detail = it) }
            page.githubLogin?.let { login ->
                rows += ItemRowView(
                    context,
                    string(R.string.settings_account_github),
                    subtitle = string(R.string.settings_account_tap_to_disconnect),
                    detail = "@$login",
                    icon = githubIcon(page.avatarUrl, page.avatarThumbhash),
                ) { disconnectGitHub() }.apply {
                    showsChevron = false
                    isLoading = disconnectingGitHub
                }
            }
            val group = ItemGroupView(context, title = string(R.string.settings_account_profile), rows = rows)
            // ItemGroup counts the GitHub slot even when it is empty, so the Name row always has its divider in happy-app.
            rows.firstOrNull()?.showsDivider = page.displayName != null
            sections += group
        }

        if (page.services.isNotEmpty()) {
            sections += ItemGroupView(
                context,
                title = string(R.string.settings_connected_accounts),
                rows = page.services.map { service ->
                    val busy = disconnectingService == service.wire
                    val row = ItemRowView(
                        context,
                        service.title,
                        subtitle = string(R.string.settings_account_tap_to_disconnect),
                        detail = string(R.string.settings_account_status_active),
                        icon = ContextCompat.getDrawable(context, service.image)?.let { ItemIcon.Picture(it, tint = if (service.tintsWithText) Palette.text else null) },
                    ) { disconnect(service) }
                    row.showsChevron = false
                    row.isLoading = busy
                    disabled(row, busy)
                },
            )
        }

        val secret = ItemRowView(
            context,
            string(R.string.settings_account_secret_key),
            subtitle = string(if (revealsSecret) R.string.settings_account_tap_to_hide else R.string.settings_account_tap_to_reveal),
            icon = ItemIcon.Glyph(Icon.ionicons(if (revealsSecret) "eye-off-outline" else "eye-outline"), Palette.iconOrange),
        ) { toggleSecret() }
        secret.showsChevron = false
        sections += ItemGroupView(context, title = string(R.string.settings_account_backup), footer = string(R.string.settings_account_backup_description), rows = listOf(secret))
        if (revealsSecret) {
            val card = SecretKeyCardView(context, signedIn.formattedSecretKey) { copySecret() }
            card.copiedRecently = copiedRecently
            sections += ItemGroupView(context, rows = listOf(card))
        }

        val optOut = graph.settings.settings.value.analyticsOptOut
        sections += ItemGroupView(
            context,
            title = string(R.string.settings_account_privacy),
            footer = string(R.string.settings_account_privacy_description),
            rows = listOf(
                toggles.row(
                    "analyticsOptOut",
                    string(R.string.settings_account_analytics),
                    string(if (optOut) R.string.settings_account_analytics_disabled else R.string.settings_account_analytics_enabled),
                    isOn = !optOut,
                ) { on -> graph.settings.change { it.copy(analyticsOptOut = !on) } },
            ),
        )

        sections += pushSections()

        sections += ItemGroupView(
            context,
            title = string(R.string.settings_account_danger_zone),
            rows = listOf(
                ItemRowView(
                    context,
                    string(R.string.settings_account_logout),
                    subtitle = string(R.string.settings_account_logout_subtitle),
                    icon = ItemIcon.Glyph(Icon.ionicons("log-out-outline"), Palette.iconRed),
                    isDestructive = true,
                ) { logout() },
            ),
        )
        list.sections = sections
    }

    /** Item.tsx's `disabled`: half opacity and no taps. */
    private fun disabled(row: ItemRowView, isDisabled: Boolean): ItemRowView {
        if (isDisabled) {
            row.isEnabled = false
            row.alpha = 0.5f
        }
        return row
    }

    /** The GitHub row's picture, the thumbhash until it arrives, as expo-image shows it. */
    private fun githubIcon(avatarUrl: String?, thumbhash: String?): ItemIcon {
        if (avatarUrl == null) return ItemIcon.Glyph(Icon.ionicons("logo-github"), Palette.textSecondary)
        val picture = picture(avatarUrl) { url ->
            val request = ImageRequest.Builder(context).data(url).allowHardware(false).build()
            HappyImageLoader.get(context).execute(request).image?.toBitmap()
        } ?: thumbhash?.let { hash -> picture("#$hash") { AttachmentPictures.thumbhashBitmap(hash) } }
        return ItemIcon.Picture(picture ?: ContextCompat.getDrawable(context, android.R.color.transparent)!!, round = true)
    }

    /** [key]'s picture if it is decoded, else null while [load] runs once; the page is rebuilt when it lands. */
    private fun picture(key: String, load: suspend (String) -> android.graphics.Bitmap?): Drawable? {
        if (pictures.containsKey(key)) return pictures[key]
        pictures[key] = null
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { runCatching { load(key) }.getOrNull() }
            if (bitmap != null) {
                pictures[key] = BitmapDrawable(context.resources, bitmap)
                rebuild()
            } else {
                pictures.remove(key)
            }
        }
        return null
    }

    // Account switcher

    private fun accountSwitcher(signedIn: AuthState.SignedIn): View {
        val settings = graph.settings.settings.value
        val entries = AccountSwitcherEntry.entries(
            graph.auth.registry.value.entries,
            activeId = signedIn.encryption.anonId,
            activeProfile = profile,
            defaultServer = graph.serverConfig.defaultUrl,
            fallbackTitle = string(R.string.account_switcher_fallback_title),
        )
        val rows = entries.map { entry ->
            val face = AvatarView.Content(entry.id, AvatarStyle.normalize(settings.avatarStyle), settings.avatarMonochrome, image = entry.avatarUrl)
            ItemRowView(context, entry.title, subtitle = entry.subtitle, icon = ItemIcon.Avatar(face)).apply {
                if (!entry.isActive) onTap = { switchAccount(entry) }
                onLongPress = { offerSignOut(this, entry) }
                showsChevron = false
                if (entry.isActive) trailingView = SettingsCheckmark.view(context, Palette.iconBlue)
                isLoading = busyAccount == entry.id
            }
        }
        val add = ItemRowView(
            context,
            string(R.string.account_switcher_add_account),
            icon = ItemIcon.Glyph(Icon.ionicons("add-circle-outline"), Palette.iconBlue),
        ) { addAccount() }
        add.showsChevron = false
        return ItemGroupView(
            context,
            title = string(R.string.account_switcher_title),
            footer = string(R.string.account_switcher_footer),
            rows = rows + add,
        )
    }

    private fun switchAccount(entry: AccountSwitcherEntry) {
        if (busyAccount != null) return
        busyAccount = entry.id
        rebuild()
        scope.launch {
            try {
                // On success the coordinator replaces the whole stack with the other account's home.
                graph.switchAccount(entry.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Alerts.show(context, string(R.string.common_error), string(R.string.account_switcher_switch_failed))
            }
            busyAccount = null
            rebuild()
        }
    }

    /** The sign-in screens over this account, which stays signed in; a sign-in there adds the account and opens it. */
    private fun addAccount() {
        graph.beginAddingAccount()
        push(WelcomeScreen(context, graph, addingAccount = true))
    }

    /** iOS's context menu: Sign Out, then (for an account that is not the active one) a confirmation. */
    private fun offerSignOut(anchor: View, entry: AccountSwitcherEntry) {
        val menu = PopupMenu(context, anchor)
        menu.menu.add(string(R.string.account_switcher_sign_out)).setOnMenuItemClickListener {
            signOut(entry)
            true
        }
        menu.show()
    }

    private fun signOut(entry: AccountSwitcherEntry) {
        if (entry.isActive) return logout()
        scope.launch {
            val confirmed = Alerts.confirm(
                context,
                string(R.string.account_switcher_sign_out),
                context.getString(R.string.account_switcher_sign_out_confirm, entry.title),
                confirmTitle = string(R.string.account_switcher_sign_out),
                destructive = true,
            )
            if (!confirmed) return@launch
            try {
                graph.signOut(entry.id).join()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Alerts.show(context, string(R.string.common_error), string(R.string.account_switcher_sign_out_failed))
            }
        }
    }

    // Account information and profile

    private fun copy(value: String, label: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Alerts.show(context, string(R.string.common_copied), context.getString(R.string.items_copied_to_clipboard, label))
    }

    private fun linkDevice() {
        if (linkingDevice) return
        scope.launch {
            val text = PairingFlow.scan(context, PairingFlow.Kind.Account) ?: return@launch
            linkingDevice = true
            rebuild()
            val outcome = PairingFlow.approve(graph, text, PairingFlow.Kind.Account)
            linkingDevice = false
            rebuild()
            PairingFlow.report(list, outcome, PairingFlow.Kind.Account)
        }
    }

    private fun disconnectGitHub() {
        if (disconnectingGitHub) return
        disconnectingGitHub = true
        rebuild()
        scope.launch {
            SettingsConnections.disconnectGitHub(graph, context)
            disconnectingGitHub = false
            rebuild()
        }
    }

    private fun disconnect(service: AccountPageModel.Service) {
        if (disconnectingService != null) return
        scope.launch {
            val confirmed = Alerts.confirm(
                context,
                context.getString(R.string.modals_disconnect_service, service.title),
                context.getString(R.string.modals_disconnect_service_confirm, service.title),
                confirmTitle = string(R.string.modals_disconnect),
                destructive = true,
            )
            if (!confirmed) return@launch
            disconnectingService = service.wire
            rebuild()
            try {
                graph.connect.disconnectService(service.wire)
                graph.account.value?.syncNow()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Alerts.show(context, string(R.string.common_error), context.getString(R.string.errors_disconnect_service_failed, service.title))
            }
            disconnectingService = null
            rebuild()
        }
    }

    // Backup

    private fun toggleSecret() {
        revealsSecret = !revealsSecret
        rebuild()
    }

    private fun copySecret() {
        val key = signedIn?.formattedSecretKey ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(string(R.string.settings_account_secret_key), key))
        copiedRecently = true
        rebuild()
        copiedReset?.cancel()
        copiedReset = scope.launch {
            delay(2_000)
            copiedRecently = false
            rebuild()
        }
        Alerts.show(context, string(R.string.common_success), string(R.string.settings_account_secret_key_copied))
    }

    // Push notifications

    private fun pushSections(): List<View> {
        val permission = ItemRowView(
            context,
            string(R.string.push_permission),
            subtitle = PushText.subtitle(pushPermission),
            detail = PushText.label(pushPermission),
            icon = ItemIcon.Glyph(Icon.ionicons("notifications-outline"), Palette.iconBlue),
        )
        permission.isLoading = loadingPush
        val request = ItemRowView(
            context,
            string(R.string.push_request_again),
            subtitle = PushText.requestSubtitle(pushPermission),
            icon = ItemIcon.Glyph(Icon.ionicons("shield-checkmark-outline"), Palette.iconGreen),
        ) { requestPermission() }
        request.showsChevron = false
        request.isLoading = requestingPermission
        val current = currentPushToken
        val reregister = ItemRowView(
            context,
            string(R.string.push_reregister),
            subtitle = if (current == null) string(R.string.push_reregister_subtitle) else context.getString(R.string.push_reregister_current, PushText.fingerprint(current)),
            icon = ItemIcon.Glyph(Icon.ionicons("refresh-outline"), Palette.iconOrange),
        ) { refreshToken() }
        reregister.showsChevron = false
        reregister.isLoading = refreshingToken
        val push = ItemGroupView(
            context,
            title = string(R.string.push_title),
            footer = string(R.string.push_footer),
            rows = listOf(
                permission,
                disabled(request, requestingPermission || loadingPush || pushPermission?.status == PushPermissionStatus.Unsupported),
                disabled(reregister, refreshingToken || loadingPush),
            ),
        )

        val (deviceLabel, appLabel) = PushText.deviceLabels(
            deviceName = AndroidSettings.Global.getString(context.contentResolver, AndroidSettings.Global.DEVICE_NAME),
            modelName = Build.MODEL,
            osVersion = Build.VERSION.RELEASE,
            appVersion = BuildConfig.VERSION_NAME,
            buildVersion = BuildConfig.VERSION_CODE.toString(),
            emulator = Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu"),
        )
        val tokenRows = if (pushTokens.isEmpty()) {
            listOf(ItemRowView(context, string(R.string.push_no_tokens), subtitle = string(R.string.push_no_tokens_subtitle)))
        } else {
            pushTokens.map { token ->
                val isCurrent = current == token.token
                val fingerprint = PushText.fingerprint(token.token)
                ItemRowView(
                    context,
                    fingerprint,
                    subtitle = PushText.tokenSubtitle(token, isCurrent, deviceLabel, appLabel),
                    detail = if (isCurrent) string(R.string.push_this_device) else null,
                    icon = ItemIcon.Glyph(
                        Icon.ionicons(if (isCurrent) "phone-portrait-outline" else "trash-outline"),
                        if (isCurrent) Palette.textSecondary else Palette.iconRed,
                    ),
                ).apply {
                    if (isCurrent) onLongPress = { copy(token.token, fingerprint) } else onTap = { delete(token) }
                    showsChevron = false
                    isLoading = deletingToken == token.token
                    disabled(this, deletingToken != null)
                }
            }
        }
        val tokens = ItemGroupView(
            context,
            title = context.getString(R.string.push_registered_tokens, pushTokens.size),
            footer = string(R.string.push_tokens_footer),
            rows = tokenRows,
        )
        // The token rows sit in a Fragment, which ItemGroup leaves alone, so every one keeps Item's default divider, the last included.
        if (pushTokens.isNotEmpty()) tokenRows.forEach { it.showsDivider = true }
        return listOf(push, tokens)
    }

    private fun loadPushSettings(showError: Boolean = false) {
        loadingPush = true
        rebuild()
        scope.launch {
            try {
                val tokens = withContext(Dispatchers.IO) { pushApi.list() }
                pushTokens = tokens
                pushPermission = registration?.permission() ?: PushPermission(PushPermissionStatus.Unsupported, false, false)
                currentPushToken = registration?.currentToken()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (showError) Alerts.show(context, string(R.string.common_error), string(R.string.push_load_failed))
            }
            loadingPush = false
            rebuild()
        }
    }

    private fun requestPermission() {
        val registration = registration ?: return
        if (requestingPermission) return
        requestingPermission = true
        rebuild()
        scope.launch {
            try {
                val result = registration.requestPermissionOrOpenSettings()
                pushPermission = result.permission
                if (result.permission.granted) {
                    try {
                        registration.syncCurrentToken()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        Alerts.show(context, string(R.string.common_error), string(R.string.push_request_failed))
                        return@launch
                    }
                    loadPushSettings()
                    Alerts.show(context, string(R.string.common_success), string(R.string.push_enabled))
                    return@launch
                }
                loadPushSettings()
                if (result.openedSettings) {
                    Alerts.show(context, string(R.string.push_open_settings_title), string(R.string.push_opened_settings))
                } else {
                    Alerts.show(context, string(R.string.common_error), string(R.string.push_not_granted))
                }
            } finally {
                requestingPermission = false
                rebuild()
            }
        }
    }

    /** `syncCurrentPushToken`: asks while it may, then registers this device's token again. */
    private fun refreshToken() {
        val registration = registration ?: return
        if (refreshingToken) return
        refreshingToken = true
        rebuild()
        scope.launch {
            try {
                val result = registration.syncCurrentToken()
                pushPermission = result.permission
                if (!result.permission.granted) {
                    loadPushSettings()
                    Alerts.show(context, string(R.string.common_error), string(R.string.push_not_enabled_yet))
                    return@launch
                }
                // No registration means no token of this device's own to send: a failure here, as on iOS, rather than happy-app's silent success.
                if (!result.registered) throw IllegalStateException("not registered")
                loadPushSettings()
                Alerts.show(context, string(R.string.common_success), string(R.string.push_refreshed))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                loadPushSettings()
                Alerts.show(context, string(R.string.common_error), string(R.string.push_refresh_failed))
            } finally {
                refreshingToken = false
                rebuild()
            }
        }
    }

    private fun delete(token: PushToken) {
        if (deletingToken != null) return
        scope.launch {
            val fingerprint = PushText.fingerprint(token.token)
            val confirmed = Alerts.confirm(
                context,
                string(R.string.push_delete_title),
                context.getString(R.string.push_delete_message, fingerprint),
                confirmTitle = string(R.string.common_delete),
                destructive = true,
            )
            if (!confirmed) return@launch
            deletingToken = token.token
            rebuild()
            try {
                withContext(Dispatchers.IO) { pushApi.unregister(token.token) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Alerts.show(context, string(R.string.common_error), string(R.string.push_delete_failed))
            }
            deletingToken = null
            loadPushSettings()
        }
    }

    // Danger zone

    private fun logout() {
        scope.launch {
            val confirmed = Alerts.confirm(
                context,
                string(R.string.common_logout),
                string(R.string.settings_account_logout_confirm),
                confirmTitle = string(R.string.common_logout),
                destructive = true,
            )
            // The coordinator swaps back to Welcome.
            if (confirmed) graph.logout()
        }
    }

    private fun activity(): MainActivity? {
        var c: Context = context
        while (c is ContextWrapper) {
            if (c is MainActivity) return c
            c = c.baseContext
        }
        return null
    }
}
