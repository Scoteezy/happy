package com.slopus.happy.harness

import android.app.Activity
import android.app.Application
import android.content.ClipboardManager
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import com.slopus.happy.MainActivity

/** Opt-in touch diagnostics for synthetic terminal lines in the isolated APK only. */
internal object TroubleshootProbe {
    fun install(application: Application) {
        check(application.packageName == "com.slopus.happy.dev.shellharness")
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity !is MainActivity) return
                val root = activity.window.decorView
                fun lines(view: View): List<TextView> = buildList {
                    if (view is TextView && view.javaClass.simpleName == "SelectableTerminalLine") add(view)
                    if (view is ViewGroup) for (index in 0 until view.childCount) addAll(lines(view.getChildAt(index)))
                }
                lateinit var listener: ViewTreeObserver.OnGlobalLayoutListener
                listener = ViewTreeObserver.OnGlobalLayoutListener {
                    val found = lines(root)
                    if (found.isNotEmpty()) {
                        root.viewTreeObserver.removeOnGlobalLayoutListener(listener)
                        application.unregisterActivityLifecycleCallbacks(this)
                        val clipboard = activity.getSystemService(ClipboardManager::class.java)
                        lateinit var copied: ClipboardManager.OnPrimaryClipChangedListener
                        copied = ClipboardManager.OnPrimaryClipChangedListener {
                            // Never log clipboard contents, only this fixed synthetic assertion.
                            val matches = clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "happy"
                            Log.d("TroubleshootProbe", "commandCopied=$matches")
                            if (matches) clipboard.removePrimaryClipChangedListener(copied)
                        }
                        clipboard.addPrimaryClipChangedListener(copied)
                        found.forEach { line ->
                            fun trace(reason: String) {
                                val bounds = Rect(); line.getGlobalVisibleRect(bounds)
                                Log.d("TroubleshootProbe", "$reason text=${line.text} selectable=${line.isTextSelectable} clickable=${line.isClickable} long=${line.isLongClickable} focus=${line.hasFocus()} selection=${line.selectionStart}:${line.selectionEnd} movement=${line.movementMethod?.javaClass?.simpleName} bounds=$bounds")
                            }
                            trace("installed")
                            line.setOnTouchListener { _, event ->
                                if (event.actionMasked != android.view.MotionEvent.ACTION_MOVE) {
                                    Log.d("TroubleshootProbe", "touch=${event.actionMasked} x=${event.x} y=${event.y}")
                                    line.postDelayed({ trace("after touch") }, 150)
                                }
                                false
                            }
                        }
                    }
                }
                root.viewTreeObserver.addOnGlobalLayoutListener(listener)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}