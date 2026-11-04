package com.example.tradefloat

data class LocalFeatures(
    val capturedAt: Long,
    val frameWidth: Int,
    val frameHeight: Int,
    val motionScore: Double,
    val greenRatio: Double,
    val redRatio: Double,
    val quality: String
)

data class AiSignal(
    val direction: String = "WAIT",
    val confidence: Int = 0,
    val entry: String = "Not available",
    val stopLoss: String = "Not available",
    val target: String = "Not available",
    val reason: String = "Insufficient evidence",
    val timeframe: String = "",
    val warning: String = "Visual chart estimates are not verified market prices."
)
