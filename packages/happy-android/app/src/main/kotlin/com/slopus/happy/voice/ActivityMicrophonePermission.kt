package com.slopus.happy.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred

/**
 * [MicrophonePermission] for this activity (iOS's
 * `AVAudioApplication.requestRecordPermission`): RECORD_AUDIO, asked for
 * when a call starts. After a refusal it can be asked again only while the
 * system would still show its prompt (the rationale flag); after that only
 * the app's settings page can grant it.
 */
class ActivityMicrophonePermission(private val activity: ComponentActivity) : MicrophonePermission {
    private var pending: CompletableDeferred<Boolean>? = null
    // Registered while the activity is being created, as the result API requires.
    private val launcher: ActivityResultLauncher<String> =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pending?.complete(granted)
            pending = null
        }
    private val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val granted get() = ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private val canAskAgain get() =
        !prefs.getBoolean(KEY_ASKED, false) || ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)

    override suspend fun request(): MicrophoneAccess {
        if (granted) return MicrophoneAccess(granted = true, canAskAgain = true)
        // Refused for good: the system shows no prompt, so don't wait on one.
        if (!canAskAgain) return MicrophoneAccess(granted = false, canAskAgain = false)
        val result = CompletableDeferred<Boolean>()
        pending?.cancel()
        pending = result
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
        launcher.launch(Manifest.permission.RECORD_AUDIO)
        val ok = result.await()
        return MicrophoneAccess(granted = ok, canAskAgain = ok || canAskAgain)
    }

    override fun openSettings() {
        activity.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    companion object {
        const val PREFS_NAME = "voice-microphone"
        private const val KEY_ASKED = "asked"
    }
}
