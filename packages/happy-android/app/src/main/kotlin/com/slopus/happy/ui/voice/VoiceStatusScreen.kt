package com.slopus.happy.ui.voice

import android.content.Context
import android.view.View
import com.slopus.happy.R
import com.slopus.happy.icons.Icon
import com.slopus.happy.layout.Palette
import com.slopus.happy.ui.components.GroupedListView
import com.slopus.happy.ui.components.ItemGroupView
import com.slopus.happy.ui.components.ItemIcon
import com.slopus.happy.ui.components.ItemRowView
import com.slopus.happy.ui.nav.BarScreen
import com.slopus.happy.ui.nav.NavigationBar
import com.slopus.happy.ui.settings.SupportUsScreen
import com.slopus.happy.voice.VoiceCallState
import kotlinx.coroutines.launch

/**
 * happy-ios `VoiceStatusViewController`: the call's controls as grouped
 * rows, which stay readable at every text size and in landscape. The status
 * (a spinner while connecting), mute, stop, and where a start failed what
 * to do about it. iOS presents it as a page sheet; here it is a modal
 * screen rising over the chat. Closing it leaves the call running.
 */
class VoiceStatusScreen(context: Context, private val coordinator: VoiceCoordinator) : BarScreen(context) {
    private val list = GroupedListView(context)

    override val isModal get() = true

    override val wantsBackButton get() = false

    override fun createContent(): View {
        bar.title = context.getString(R.string.settings_voice_assistant)
        bar.actions = listOf(NavigationBar.Action.text(context.getString(R.string.voice_call_close)) { pop() })
        return list
    }

    override fun viewCreated(view: View) {
        super.viewCreated(view)
        scope.launch { coordinator.state.collect(::update) }
    }

    override fun onInsetsChanged() {
        super.onInsetsChanged()
        list.safeArea = contentSafeArea
    }

    private fun string(id: Int) = context.getString(id)

    private fun update(state: VoiceCallState) {
        val rows = mutableListOf<ItemRowView>()
        val title = when (state) {
            VoiceCallState.Ended -> string(R.string.voice_call_ended)
            VoiceCallState.Connecting -> string(R.string.voice_status_bar_connecting)
            is VoiceCallState.Active -> {
                rows += ItemRowView(
                    context,
                    string(if (state.muted) R.string.voice_call_unmute else R.string.voice_call_mute),
                    icon = ItemIcon.Glyph(Icon.ionicons(if (state.muted) "mic-outline" else "mic"), Palette.iconBlue),
                ) { coordinator.toggleMute() }
                string(VoiceCoordinator.activityText(state))
            }
            is VoiceCallState.Denied -> {
                if (state.reason == VoiceCallState.Reason.SubscriptionRequired) {
                    rows += ItemRowView(
                        context,
                        string(R.string.settings_voice_support_title),
                        icon = ItemIcon.Glyph(Icon.ionicons("heart-outline"), Palette.iconRed),
                    ) { push(SupportUsScreen(context)) }
                }
                string(
                    when (state.reason) {
                        VoiceCallState.Reason.SubscriptionRequired -> R.string.voice_call_subscription_required
                        VoiceCallState.Reason.ConversationLimitReached -> R.string.voice_call_conversation_limit
                    },
                )
            }
            is VoiceCallState.Failed -> {
                if (state.failure == VoiceCallState.Failure.MicrophonePermission) {
                    rows += ItemRowView(
                        context,
                        string(R.string.voice_call_open_settings),
                        icon = ItemIcon.Glyph(Icon.ionicons("settings-outline"), Palette.iconBlue),
                    ) { coordinator.openMicrophoneSettings() }
                }
                string(
                    when (state.failure) {
                        VoiceCallState.Failure.MicrophonePermission -> R.string.voice_call_permission_denied
                        VoiceCallState.Failure.Authentication -> R.string.voice_call_authentication
                        VoiceCallState.Failure.Unavailable -> R.string.errors_voice_service_unavailable
                    },
                )
            }
        }
        val status = ItemRowView(context, title, icon = ItemIcon.Glyph(Icon.ionicons("mic-outline"), Palette.iconBlue)).apply {
            showsChevron = false
            isLoading = state == VoiceCallState.Connecting
        }
        if (state.isRunning) {
            rows += ItemRowView(
                context,
                string(R.string.voice_call_stop),
                icon = ItemIcon.Glyph(Icon.octicons("stop"), Palette.iconRed),
                isDestructive = true,
            ) { coordinator.stop() }
        }
        val all = listOf(status) + rows
        // Error explanations must stay readable at large text sizes.
        for (row in all) row.titleLabel.maxLines = 0
        list.sections = listOf(ItemGroupView(context, footer = string(R.string.voice_call_footer), rows = all))
    }
}
