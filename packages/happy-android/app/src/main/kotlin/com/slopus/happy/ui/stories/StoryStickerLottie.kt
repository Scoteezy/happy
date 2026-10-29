package com.slopus.happy.ui.stories

import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.View
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.slopus.happy.stories.Sticker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * A `tgs` sticker played by Lottie (happy-ios `StoryStickerLottiePlayer`),
 * as Telegram plays its animated stickers: looping, its drawable drawn into
 * the host view. The Lottie JSON (`?as=lottie`, kept on disk by
 * [StoriesMedia]) is read and parsed off the main thread; until it arrives
 * nothing is drawn. It plays only while asked to, which its owner ties to
 * being on screen.
 */
class StoryStickerLottiePlayer(sticker: Sticker, media: StoriesMedia, private val host: View) : StoryStickerAnimating {
    private val drawable = LottieDrawable()
    private var loading: Job? = null
    private var playing = false

    /** Whether the animation has arrived. */
    val isLoaded: Boolean get() = drawable.composition != null
    val isAnimating: Boolean get() = drawable.isAnimating

    init {
        drawable.repeatCount = LottieDrawable.INFINITE
        drawable.callback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) = host.invalidate()
            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) { host.postDelayed(what, `when` - android.os.SystemClock.uptimeMillis()) }
            override fun unscheduleDrawable(who: Drawable, what: Runnable) { host.removeCallbacks(what) }
        }
        loading = CoroutineScope(Dispatchers.Main.immediate).launch {
            val data = media.stickerFile(sticker) ?: return@launch
            val composition = withContext(Dispatchers.Default) { decode(data, sticker.sha256) } ?: return@launch
            drawable.composition = composition
            apply()
            host.invalidate()
        }
    }

    override fun setPlaying(playing: Boolean) {
        this.playing = playing
        apply()
    }

    private fun apply() {
        host.stateDescription = if (playing) "playing" else "paused"
        if (drawable.composition == null) return
        if (playing) {
            if (!drawable.isAnimating) drawable.resumeAnimation()
        } else {
            drawable.pauseAnimation()
        }
    }

    override fun draw(canvas: Canvas, width: Int, height: Int) {
        val composition = drawable.composition ?: return
        // Aspect-fit in the host.
        val bounds = composition.bounds
        val fit = min(width / bounds.width().toFloat(), height / bounds.height().toFloat())
        val w = (bounds.width() * fit).toInt()
        val h = (bounds.height() * fit).toInt()
        drawable.setBounds((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        drawable.draw(canvas)
    }

    override fun stop() {
        loading?.cancel()
        drawable.cancelAnimation()
        drawable.callback = null
    }

    companion object {
        fun decode(data: ByteArray, key: String?): LottieComposition? =
            LottieCompositionFactory.fromJsonStringSync(String(data), key).value
    }
}
