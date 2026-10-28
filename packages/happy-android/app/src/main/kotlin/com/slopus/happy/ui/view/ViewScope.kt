package com.slopus.happy.ui.view

import android.view.View
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope

/**
 * A main-thread scope for work a view starts (a button's action): the
 * activity's lifecycle scope once the view is in the window, so the work
 * ends with the activity rather than with a screen that was popped.
 */
val View.viewScope: CoroutineScope
    get() = findViewTreeLifecycleOwner()?.lifecycleScope ?: rootView.findViewTreeLifecycleOwner()?.lifecycleScope ?: MainScope()
