package com.slopus.happy.push

import com.slopus.happy.model.settings.PushPermission
import com.slopus.happy.model.settings.PushPermissionStatus
import java.util.concurrent.atomic.AtomicInteger

/** The permission fakes the push tests share. */
object PushPromptTest {
    class FakePermission(var current: PushPermission, val answer: PushPermission = current) : NotificationPermission {
        val infos = AtomicInteger()
        val requests = AtomicInteger()
        var settingsOpened = 0

        override fun info(): PushPermission {
            infos.incrementAndGet()
            return current
        }

        override suspend fun request(): PushPermission {
            requests.incrementAndGet()
            current = answer
            return answer
        }

        override fun openSettings() {
            settingsOpened++
        }
    }

    val GRANTED = PushPermission(PushPermissionStatus.Granted, granted = true, canAskAgain = true)
    val UNDETERMINED = PushPermission(PushPermissionStatus.Undetermined, granted = false, canAskAgain = true)
    val BLOCKED = PushPermission(PushPermissionStatus.Denied, granted = false, canAskAgain = false)
}
