package io.github.mjohnson7cd.chronicleauto.features.car

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider
import io.github.mjohnson7cd.chronicleauto.R
import io.github.mjohnson7cd.chronicleauto.application.Injector
import io.github.mjohnson7cd.chronicleauto.features.player.PLAYBACK_SPEED_PRESETS
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Playback speed picker, opened from car settings (the now-playing speed button can only cycle
 * presets: car hosts block activity starts from the media service). Distraction-optimized (usable while driving): big preset buttons, a chunky slider and -/+
 * steps. Every change is written to prefs immediately; MediaPlayerService applies it live.
 */
class CarPlaybackSpeedActivity : AppCompatActivity() {
    private val prefsRepo by lazy { Injector.get().prefsRepo() }

    private lateinit var current: TextView
    private lateinit var slider: Slider
    private val presetButtons = mutableListOf<Pair<Float, MaterialButton>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_car_playback_speed)
        current = findViewById(R.id.car_speed_current)
        slider = findViewById(R.id.car_speed_slider)

        val presets = findViewById<LinearLayout>(R.id.car_speed_presets)
        val margin = dp(6)
        PRESETS.forEach { speed ->
            val button =
                MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = format(speed)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                    setPadding(0, 0, 0, 0)
                    setOnClickListener { setSpeed(speed) }
                }
            presets.addView(
                button,
                LinearLayout.LayoutParams(0, dp(96), 1f).apply { setMargins(margin, 0, margin, 0) },
            )
            presetButtons += speed to button
        }

        slider.addOnChangeListener { _, value, fromUser -> if (fromUser) setSpeed(value) }
        findViewById<MaterialButton>(R.id.car_speed_minus).setOnClickListener { setSpeed(prefsRepo.playbackSpeed - STEP) }
        findViewById<MaterialButton>(R.id.car_speed_plus).setOnClickListener { setSpeed(prefsRepo.playbackSpeed + STEP) }
        findViewById<MaterialButton>(R.id.car_speed_done).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        render(prefsRepo.playbackSpeed)
    }

    private fun setSpeed(value: Float) {
        val speed = snap(value)
        if (speed != prefsRepo.playbackSpeed) prefsRepo.playbackSpeed = speed
        render(speed)
    }

    private fun render(speed: Float) {
        current.text = format(speed)
        // Prefs may hold a value outside the slider range (phone allows up to 3x).
        slider.value = snap(speed)
        val primary = MaterialColors.getColor(current, androidx.appcompat.R.attr.colorPrimary)
        val onPrimary = MaterialColors.getColor(current, com.google.android.material.R.attr.colorOnPrimary)
        presetButtons.forEach { (preset, button) ->
            val selected = abs(preset - speed) < 0.001f
            button.backgroundTintList = ColorStateList.valueOf(if (selected) primary else Color.TRANSPARENT)
            button.setTextColor(if (selected) onPrimary else Color.WHITE)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        const val MIN = 0.5f
        const val MAX = 2.5f
        const val STEP = 0.05f
        val PRESETS = PLAYBACK_SPEED_PRESETS

        /** Clamp to [MIN]..[MAX] and round to the slider's [STEP] grid (Slider rejects off-grid values). */
        fun snap(value: Float): Float = ((value.coerceIn(MIN, MAX) / STEP).roundToInt() * STEP * 100).roundToInt() / 100f

        fun format(speed: Float): String = "%.2f".format(java.util.Locale.US, speed).trimEnd('0').trimEnd('.') + "x"
    }
}
