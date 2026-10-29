package com.slopus.happy.ui.nav

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import androidx.activity.BackEventCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class ScreenTransitionTest {
    private open class Plain(context: Context) : Screen(context) {
        override fun createView(): View = FrameLayout(context)
    }
    private class Custom(context: Context) : Plain(context), NavigationTransitionProviding {
        val transitions = mutableListOf<Recording>()
        override fun navigationTransition(other: Screen, pushing: Boolean) = Recording().also(transitions::add)
    }
    private class Recording : ScreenTransition {
        override val durationMillis = 350L
        override val interpolator = LinearInterpolator()
        var interactive = false
        val frames = mutableListOf<Float>()
        val endings = mutableListOf<Boolean>()
        override fun begin(interactive: Boolean) { this.interactive = interactive }
        override fun apply(shown: Float) { frames += shown }
        override fun finish(cancelled: Boolean) { endings += cancelled }
    }
    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
    private fun fixture(body: (ScreenStack, Plain) -> Unit) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val stack = ScreenStack(activity.get())
        activity.get().setContentView(stack)
        stack.layout(0, 0, 1080, 2400)
        val home = Plain(activity.get())
        stack.setScreens(listOf(home))
        try { body(stack, home) } finally {
            stack.setScreens(emptyList())
            activity.pause().stop().destroy()
        }
    }

    @Test fun customPushAndPopNeverSlideAndFinishOnce() = fixture { stack, home ->
        val chat = Custom(stack.context)
        stack.push(chat)
        assertEquals(0f, chat.view.translationX)
        assertEquals(0f, home.view.translationX)
        assertEquals(0f, chat.transitions.single().frames.first())
        settle()
        assertEquals(listOf(false), chat.transitions.single().endings)
        assertEquals(1f, chat.transitions.single().frames.last())
        stack.pop()
        settle()
        assertSame(home, stack.top)
        assertEquals(0f, chat.transitions.last().frames.last())
        assertEquals(listOf(false), chat.transitions.last().endings)
    }

    @Test fun predictiveCancellationRestoresAndCommitRemovesTheTop() = fixture { stack, home ->
        val chat = Custom(stack.context)
        stack.push(chat, animated = false)
        fun gesture() {
            stack.backCallback.handleOnBackStarted(BackEventCompat(0f, 50f, 0f, BackEventCompat.EDGE_LEFT))
            stack.backCallback.handleOnBackProgressed(BackEventCompat(200f, 50f, .4f, BackEventCompat.EDGE_LEFT))
            assertEquals(.6f, chat.transitions.last().frames.last(), .001f)
            assertEquals(0f, chat.view.translationX)
        }
        gesture()
        assertTrue(chat.transitions.last().interactive)
        stack.backCallback.handleOnBackCancelled()
        settle()
        assertSame(chat, stack.top)
        assertEquals(listOf(true), chat.transitions.last().endings)
        assertEquals(View.INVISIBLE, home.view.visibility)
        gesture()
        stack.backCallback.handleOnBackPressed()
        settle()
        assertSame(home, stack.top)
        assertEquals(listOf(false), chat.transitions.last().endings)
    }

    @Test fun replacingTheStackOrDetachingFinishesWithoutRestoringGestureFocus() = fixture { stack, home ->
        val chat = Custom(stack.context)
        stack.push(chat, animated = false)
        stack.backCallback.handleOnBackStarted(BackEventCompat(0f, 50f, 0f, BackEventCompat.EDGE_LEFT))
        stack.setScreens(listOf(home))
        assertEquals(listOf(false), chat.transitions.single().endings)
        val another = Custom(stack.context)
        stack.push(another, animated = false)
        stack.backCallback.handleOnBackStarted(BackEventCompat(0f, 50f, 0f, BackEventCompat.EDGE_LEFT))
        (stack.parent as android.view.ViewGroup).removeView(stack)
        assertEquals(listOf(false), another.transitions.single().endings)
    }

    @Test fun reducedMotionCompletesCustomTransitionAndOrdinaryScreensStillSlide() = fixture { stack, home ->
        val plain = Plain(stack.context)
        stack.push(plain)
        assertEquals(1080f, plain.view.translationX)
        settle()
        stack.pop(animated = false)
        fun scale(value: Float) = ReflectionHelpers.callStaticMethod<Unit>(ValueAnimator::class.java, "setDurationScale", ClassParameter.from(Float::class.javaPrimitiveType, value))
        scale(0f)
        try {
            val chat = Custom(stack.context)
            stack.push(chat)
            settle()
            assertEquals(listOf(false), chat.transitions.single().endings)
            assertEquals(0f, chat.view.translationX)
            stack.pop()
            settle()
            assertSame(home, stack.top)
        } finally { scale(1f) }
    }

    @Test fun replacementDuringCancelledGestureSettlementDoesNotRestoreFocus() = fixture { stack, home ->
        val chat = Custom(stack.context)
        stack.push(chat, animated = false)
        stack.backCallback.handleOnBackStarted(BackEventCompat(0f, 50f, 0f, BackEventCompat.EDGE_LEFT))
        stack.backCallback.handleOnBackProgressed(BackEventCompat(200f, 50f, .4f, BackEventCompat.EDGE_LEFT))
        stack.backCallback.handleOnBackCancelled()
        stack.setScreens(listOf(home))
        settle()
        assertEquals(listOf(false), chat.transitions.single().endings)
    }

    @Test fun windowDetachDuringPopSettlementFinishesOnlyOnce() = fixture { stack, _ ->
        val chat = Custom(stack.context)
        stack.push(chat, animated = false)
        stack.pop()
        (stack.parent as android.view.ViewGroup).removeView(stack)
        settle()
        assertEquals(listOf(false), chat.transitions.single().endings)
    }
}