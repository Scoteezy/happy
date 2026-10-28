package com.slopus.happy

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import com.slopus.happy.connect.DeepLinks
import com.slopus.happy.push.ActivityNotificationPermission
import com.slopus.happy.push.BackgroundSendNotifications
import com.slopus.happy.push.startPushRegistration
import com.slopus.happy.ui.AppCoordinator

class MainActivity : ComponentActivity() {
    private val graph: AppGraph get() = (application as HappyApplication).graph
    private lateinit var coordinator: AppCoordinator
    lateinit var notificationPermission: ActivityNotificationPermission
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        notificationPermission = ActivityNotificationPermission(this)
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
    }
}
