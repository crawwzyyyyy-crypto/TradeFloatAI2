package com.example.tradefloat

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * Lightweight screen-quality and colour-balance sampler. This deliberately does NOT
 * claim to reconstruct OHLC candles or produce a buy/sell decision from screenshot pixels.
 * Its measurements are context for the vision model, not a trading edge by themselves.
 */
object ChartFrameAnalyzer {
    fun analyze(bitmap: Bitmap): LocalFeatures {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 100 || h < 100) return LocalFeatures(System.currentTimeMillis(), w, h, 0.0, 0.0, 0.0, "FRAME_TOO_SMALL")
        val stepX = (w / 100).coerceAtLeast(1)
        val stepY = (h / 100).coerceAtLeast(1)
        var green = 0
        var red = 0
        var sampled = 0
        var luminanceSum = 0.0
        var luminanceSq = 0.0
        for (y in 0 until h step stepY) {
            for (x in 0 until w step stepX) {
                val c = bitmap.getPixel(x, y)
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                if (g > r * 1.25 && g > b * 1.12 && g > 75) green++
                if (r > g * 1.25 && r > b * 1.12 && r > 75) red++
                val l = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
                luminanceSum += l
                luminanceSq += l * l
                sampled++
            }
        }
        if (sampled == 0) return LocalFeatures(System.currentTimeMillis(), w, h, 0.0, 0.0, 0.0, "NO_PIXELS")
        val mean = luminanceSum / sampled
        val variance = (luminanceSq / sampled - mean * mean).coerceAtLeast(0.0)
        val colourTotal = (green + red).coerceAtLeast(1)
        val quality = when {
            variance < 0.008 -> "LOW_VISUAL_DETAIL"
            w < h -> "PORTRAIT_FRAME"
            else -> "FRAME_OK"
        }
        return LocalFeatures(
            capturedAt = System.currentTimeMillis(), frameWidth = w, frameHeight = h,
            motionScore = abs(mean - 0.5), greenRatio = green.toDouble() / colourTotal,
            redRatio = red.toDouble() / colourTotal, quality = quality
        )
    }
}
