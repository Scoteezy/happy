package com.slopus.happy.push

import android.util.Log
import com.slopus.happy.AppGraph
import com.slopus.happy.net.AuthenticatedHttp
import com.slopus.happy.model.settings.PushPermission
import com.slopus.happy.model.settings.PushRegistration
import com.slopus.happy.model.settings.PushTokenSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings › Account's push rows over FCM: pushRegistration.ts's
 * getPushPermissionInfo, requestPushPermissionOrOpenSettings,
 * getCurrentExpoPushToken, syncCurrentPushToken and removePushToken.
 * Where push is unsupported (no Firebase in the build, no Play services)
 * syncing registers nothing and reports the token last registered, as
 * happy-app does without a project id.
 */
class AppPushRegistration(
    private val permission: NotificationPermission,
    private val supported: () -> Boolean,
    private val registrar: PushRegistrar,
    private val store: RegisteredPushTokenStore,
    private val account: () -> AuthenticatedHttp?,
) : PushRegistration {
    override suspend fun permission(): PushPermission = permission.info()

    override suspend fun requestPermissionOrOpenSettings() = permission.requestOrOpenSettings()

    override suspend fun currentToken(): String? {
        if (!permission.info().granted || !supported()) return store.load()
        return try {
            withContext(Dispatchers.IO) { registrar.currentExpoToken() } ?: store.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("PushRegistration", "Failed to get Expo push token", e)
            store.load()
        }
    }

    override suspend fun syncCurrentToken(): PushTokenSync {
        val selected = account() // Keep host and token together through permission and Expo awaits.
        var info = permission.info()
        if (!info.granted) {
            if (!info.canAskAgain) return PushTokenSync(registered = false, token = store.load(), permission = info)
            info = permission.request()
            if (!info.granted) return PushTokenSync(registered = false, token = store.load(), permission = info)
        }
        if (selected == null || !supported()) return PushTokenSync(registered = false, token = store.load(), permission = info)
        return when (val result = withContext(Dispatchers.IO) { registrar.sync(selected) }) {
            is PushSyncResult.Registered -> PushTokenSync(registered = true, token = result.token, permission = info)
            PushSyncResult.Unsupported -> PushTokenSync(registered = false, token = store.load(), permission = info)
        }
    }

    override suspend fun removeToken(token: String) {
        val selected = checkNotNull(account()) { "Not signed in" }
        registrar.remove(selected, token)
    }
}

/** [AppPushRegistration] over the activity's permission prompt. */
fun pushRegistration(context: android.content.Context, graph: AppGraph, permission: NotificationPermission): PushRegistration {
    val services = PushServices.get(context)
    return AppPushRegistration(
        permission = permission,
        supported = { services.fcm.supported },
        registrar = services.registrar,
        store = services.store,
        account = graph::currentRequests,
    )
}
