package com.slopus.happy.push

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.slopus.happy.AppGraph
import com.slopus.happy.auth.AuthState
import com.slopus.happy.model.settings.PushPermission
import com.slopus.happy.model.settings.PushPermissionRequest
import com.slopus.happy.model.settings.PushPermissionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The notification permission as expo-notifications reports and asks for
 * it, in pushRegistration.ts's terms (Settings' [PushPermission]).
 */
interface NotificationPermission {
    fun info(): PushPermission

    /** Notifications.requestPermissionsAsync: the system prompt (POST_NOTIFICATIONS, Android 13+). */
    suspend fun request(): PushPermission

    /** The app's notification settings, for when the prompt can't be shown again. */
    fun openSettings()
}

/**
 * requestPushPermissionOrOpenSettings: allowed already, or the prompt if it
 * can still be shown, or the system settings.
 */
suspend fun NotificationPermission.requestOrOpenSettings(): PushPermissionRequest {
    val existing = info()
    if (existing.granted) return PushPermissionRequest(granted = true, openedSettings = false, permission = existing)
    if (existing.canAskAgain) {
        val requested = request()
        return PushPermissionRequest(granted = requested.granted, openedSettings = false, permission = requested)
    }
    openSettings()
    return PushPermissionRequest(granted = false, openedSettings = true, permission = existing)
}

private fun permission(status: PushPermissionStatus, canAskAgain: Boolean) =
    PushPermission(status, granted = status == PushPermissionStatus.Granted, canAskAgain = canAskAgain)

/**
 * [NotificationPermission] for this activity. Expo's rules: before Android 13
 * notifications are allowed unless the user turned them off, and then they
 * can't be asked for. From 13 on, POST_NOTIFICATIONS: undetermined until
 * asked once; after a refusal it can be asked again only while the system
 * would still show its prompt (the rationale flag).
 */
class ActivityNotificationPermission(private val activity: ComponentActivity) : NotificationPermission {
    private var pending: CompletableDeferred<Boolean>? = null
    // Registered while the activity is being created, as the result API requires.
    private val launcher: ActivityResultLauncher<String> =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pending?.complete(granted)
            pending = null
        }
    private val prefs = activity.getSharedPreferences(RegisteredPushTokenStore.PREFS_NAME, Context.MODE_PRIVATE)

    override fun info(): PushPermission {
        if (NotificationManagerCompat.from(activity).areNotificationsEnabled()) {
            return permission(PushPermissionStatus.Granted, canAskAgain = true)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return permission(PushPermissionStatus.Denied, canAskAgain = false)
        }
        if (!prefs.getBoolean(KEY_ASKED, false)) return permission(PushPermissionStatus.Undetermined, canAskAgain = true)
        val canAsk = ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
        return permission(PushPermissionStatus.Denied, canAskAgain = canAsk)
    }

    override suspend fun request(): PushPermission {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return info()
        val result = CompletableDeferred<Boolean>()
        pending?.cancel()
        pending = result
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        result.await()
        return info()
    }

    override fun openSettings() {
        activity.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private const val KEY_ASKED = "notification-permission-asked"


/**
 * sync.ts `pushTokenSync`: while signed in, every time the app comes to the
 * foreground, ask for notifications if they can still be asked for, then
 * register this device's push token. Signing out forgets the registration.
 * Builds without Firebase, and devices without an FCM token, skip it all
 * and never prompt.
 */
fun startPushRegistration(activity: ComponentActivity, graph: AppGraph, permission: NotificationPermission) {
    val services = PushServices.get(activity)
    NotificationChannels.create(activity)
    activity.lifecycleScope.launch {
        graph.auth.state.map { it as? AuthState.SignedIn }.distinctUntilChanged().collectLatest { account ->
            if (account == null) {
                services.registrar.forget()
                return@collectLatest
            }
            val requests = graph.accountRequests(account)
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (!withContext(Dispatchers.IO) { services.fcm.supported }) return@repeatOnLifecycle
                var info = permission.info()
                if (!info.granted) {
                    if (!info.canAskAgain) return@repeatOnLifecycle
                    info = permission.request()
                    if (!info.granted) return@repeatOnLifecycle
                }
                try {
                    withContext(Dispatchers.IO) { services.registrar.sync(requests) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("PushRegistration", "Failed to register push token", e)
                }
            }
        }
    }
}
