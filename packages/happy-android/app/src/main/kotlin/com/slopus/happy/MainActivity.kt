package com.slopus.happy

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import com.slopus.happy.connect.DeepLinks
import com.slopus.happy.push.ActivityNotificationPermission
import com.slopus.happy.push.BackgroundSendNotifications
import com.slopus.happy.push.startPushRegistration
import com.slopus.happy.ui.AppCoordinator
import com.slopus.happy.ui.voice.VoiceCoordinator
import com.slopus.happy.voice.ActivityMicrophonePermission

class MainActivity : ComponentActivity() {
    private val graph: AppGraph get() = (application as HappyApplication).graph
    private lateinit var coordinator: AppCoordinator
    lateinit var notificationPermission: ActivityNotificationPermission
        private set

    /** The UI language (text/index.ts): the synced choice, else the device's, else English, on every Android version. */
    override fun attachBaseContext(newBase: Context) {
        val language = (newBase.applicationContext as? HappyApplication)?.graph?.language?.language?.value
        if (language == null) return super.attachBaseContext(newBase)
        val config = Configuration(newBase.resources.configuration)
        config.setLocales(LocaleList(language.locale))
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        notificationPermission = ActivityNotificationPermission(this)
        VoiceCoordinator.of(graph).microphone = ActivityMicrophonePermission(this)
        if (savedInstanceState == null) DeepLinks.offer(intent)
        BackgroundSendNotifications.start(this, graph)
        coordinator = AppCoordinator(this, graph)
        setContentView(coordinator.root)
        coordinator.start()
        startPushRegistration(this, graph, notificationPermission)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        DeepLinks.offer(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        coordinator.configurationChanged(newConfig)
    }

    override fun onStart() {
        super.onStart()
        graph.setForeground(true)
        graph.requestSync()
    }

    override fun onStop() {
        super.onStop()
        graph.setForeground(false)
        // happy-ios: voice stops when Happy enters the background.
        if (!isChangingConfigurations) VoiceCoordinator.of(graph).backgrounded()
    }
}
