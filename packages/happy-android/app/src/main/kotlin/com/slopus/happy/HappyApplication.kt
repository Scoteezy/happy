package com.slopus.happy

import android.app.Application
import android.content.ComponentCallbacks2
import com.slopus.happy.attachments.AttachmentServices
import com.slopus.happy.push.PushServices

open class HappyApplication : Application() {
    val graph: AppGraph by lazy {
        createGraph().also {
            com.slopus.happy.direct.Tailcat.install(this)
            PushServices.unregisterOnLogout(this, it)
        }
    }

    protected open fun createGraph(): AppGraph = AppGraph(
            context = this,
            clientId = "android/${BuildConfig.VERSION_NAME}",
            defaultServerUrl = BuildConfig.DEFAULT_SERVER_URL,
        )

    /** iOS's memory warning: decoded attachment pictures go first. */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) AttachmentServices.of(graph).trimMemory()
    }
}
