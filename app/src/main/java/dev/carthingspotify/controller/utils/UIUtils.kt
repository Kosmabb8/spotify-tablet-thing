package dev.carthingspotify.controller.utils

import android.graphics.Color
import kotlin.math.roundToInt

object UIUtils {
    fun blend(first: Int, second: Int, amountSecond: Float): Int {
        val a = amountSecond.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(first) * (1 - a) + Color.red(second) * a).roundToInt(),
            (Color.green(first) * (1 - a) + Color.green(second) * a).roundToInt(),
            (Color.blue(first) * (1 - a) + Color.blue(second) * a).roundToInt()
        )
    }
}
