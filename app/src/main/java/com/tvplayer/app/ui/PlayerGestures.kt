package com.tvplayer.app.ui

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.media3.common.C
import androidx.media3.common.Player
import kotlin.math.abs

/**
 * Touch gestures on the video (phones and tablets only):
 *  - swipe up/down on the right half  → volume
 *  - swipe up/down on the left half   → brightness
 *  - swipe left/right                 → seek (movies / episodes)
 *  - double-tap right / left          → +10 s / −10 s
 *  - single tap                       → show / hide the controls
 */
class PlayerGestures(
    private val activity: Activity,
    private val player: () -> Player?,
    private val isLive: () -> Boolean,
    private val osd: (String) -> Unit,
    /** Volume (true) or brightness (false) level 0–100, for the big centered indicator. */
    private val level: (Boolean, Int) -> Unit,
    private val toggleControls: () -> Unit,
) : View.OnTouchListener {

    private val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    private val threshold = 28 * activity.resources.displayMetrics.density

    private enum class Mode { NONE, SEEK, VOLUME, BRIGHTNESS }

    private var mode = Mode.NONE
    private var startX = 0f
    private var startY = 0f
    private var startVolume = 0
    private var startBrightness = 0.5f
    private var startPosition = 0L
    private var seekTarget = -1L

    private val detector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            toggleControls()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (isLive()) return false
            val p = player() ?: return false
            val forward = e.x > activity.window.decorView.width / 2f
            val dur = p.duration
            val target = p.currentPosition + if (forward) 10_000 else -10_000
            p.seekTo(if (dur != C.TIME_UNSET && dur > 0) target.coerceIn(0, dur) else target.coerceAtLeast(0))
            osd(if (forward) "⏩  +10 ثواني" else "⏪  −10 ثواني")
            return true
        }
    }).apply {
        // A slow tap (busy device) must still count as a tap, not a long press.
        setIsLongpressEnabled(false)
    }

    override fun onTouch(v: View, e: MotionEvent): Boolean {
        detector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = e.x
                startY = e.y
                mode = Mode.NONE
                startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                val b = activity.window.attributes.screenBrightness
                startBrightness = if (b in 0f..1f) b else 0.5f
                startPosition = player()?.currentPosition ?: 0L
                seekTarget = -1L
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - startX
                val dy = e.y - startY
                if (mode == Mode.NONE) {
                    mode = when {
                        abs(dx) > threshold && abs(dx) > abs(dy) && !isLive() -> Mode.SEEK
                        abs(dy) > threshold && abs(dy) > abs(dx) -> if (startX > v.width / 2f) Mode.VOLUME else Mode.BRIGHTNESS
                        else -> Mode.NONE
                    }
                }
                when (mode) {
                    Mode.SEEK -> {
                        val p = player() ?: return true
                        val dur = p.duration
                        if (dur != C.TIME_UNSET && dur > 0) {
                            // A full-width swipe moves 90 seconds.
                            seekTarget = (startPosition + dx / v.width * 90_000).toLong().coerceIn(0, dur)
                            val diff = (seekTarget - startPosition) / 1000
                            osd("${if (diff >= 0) "⏩ +" else "⏪ "}${diff} ث   •   ${DetailsActivity.formatTime(seekTarget)} / ${DetailsActivity.formatTime(dur)}")
                        }
                    }
                    Mode.VOLUME -> {
                        val delta = (-dy / (v.height * 0.7f) * maxVolume).toInt()
                        val vol = (startVolume + delta).coerceIn(0, maxVolume)
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0)
                        level(true, vol * 100 / maxVolume)
                    }
                    Mode.BRIGHTNESS -> {
                        val bright = (startBrightness - dy / (v.height * 0.7f)).coerceIn(0.02f, 1f)
                        val attrs = activity.window.attributes
                        attrs.screenBrightness = bright
                        activity.window.attributes = attrs
                        level(false, (bright * 100).toInt())
                    }
                    Mode.NONE -> Unit
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.SEEK && seekTarget >= 0) player()?.seekTo(seekTarget)
                mode = Mode.NONE
            }
        }
        return true
    }
}
