package com.example.tradefloat

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object WorkerClient {
    // Replace this after deploying worker/worker.js. Keep the /predict path.
    const val WORKER_URL = "https://YOUR-WORKER.YOUR-SUBDOMAIN.workers.dev/predict"
    private val executor = Executors.newSingleThreadExecutor()

    fun predict(frames: List<Bitmap>, horizonSeconds: Int, features: LocalFeatures, callback: (Result<AiSignal>) -> Unit) {
        executor.execute {
            try {
                require(WORKER_URL.startsWith("https://") && !WORKER_URL.contains("YOUR-WORKER")) {
                    "Set WORKER_URL in WorkerClient.kt after deploying your Cloudflare Worker."
                }
                require(frames.isNotEmpty()) { "No chart frames available yet." }
                val arr = JSONArray()
                // Send at most the 3 freshest frames; resize/compress to reduce latency and cost.
                frames.takeLast(3).forEach { bitmap ->
                    val scaled = Bitmap.createScaledBitmap(bitmap, bitmap.width.coerceAtMost(960), bitmap.height.coerceAtMost(960), true)
                    val out = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 68, out)
                    if (scaled !== bitmap) scaled.recycle()
                    arr.put(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
                }
                val body = JSONObject().apply {
                    put("horizonSeconds", horizonSeconds)
                    put("frames", arr)
                    put("features", JSONObject().apply {
                        put("width", features.frameWidth); put("height", features.frameHeight)
                        put("greenRatio", features.greenRatio); put("redRatio", features.redRatio)
                        put("quality", features.quality)
                    })
                }.toString()
                val conn = (URL(WORKER_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 4500
                    readTimeout = 9000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val response = stream.bufferedReader().use { it.readText() }
                conn.disconnect()
                if (code !in 200..299) throw IllegalStateException("Worker HTTP $code: $response")
                val json = JSONObject(response)
                val signal = AiSignal(
                    direction = json.optString("direction", "WAIT").uppercase(),
                    confidence = json.optInt("confidence", 0).coerceIn(0, 100),
                    entry = json.optString("entry", "Not available"),
                    stopLoss = json.optString("stopLoss", "Not available"),
                    target = json.optString("target", "Not available"),
                    reason = json.optString("reason", "No explanation returned"),
                    timeframe = "${horizonSeconds}s",
                    warning = json.optString("warning", "Visual estimate only; verify price and risk manually.")
                )
                callback(Result.success(signal))
            } catch (t: Throwable) { callback(Result.failure(t)) }
        }
    }
}
