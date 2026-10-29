package com.slopus.happy.ui.nav

import android.animation.TimeInterpolator

/** Optional screen-owned transition, as UIKit's navigation transition provider. */
interface NavigationTransitionProviding {
    fun navigationTransition(other: Screen, pushing: Boolean): ScreenTransition?
}

/** The stack still owns navigation, timing, touch exclusion and predictive back. */
interface ScreenTransition {
    val durationMillis: Long
    val interpolator: TimeInterpolator
    fun begin(interactive: Boolean)
    /** 0 = only the underlying screen, 1 = the top screen fully shown. */
    fun apply(shown: Float)
    /** Always once. cancelled means a gesture returned to this screen, not teardown. */
    fun finish(cancelled: Boolean)
}