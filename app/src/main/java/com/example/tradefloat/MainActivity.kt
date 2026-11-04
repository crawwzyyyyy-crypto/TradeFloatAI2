package com.example.tradefloat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    companion object { private const val REQUEST_CAPTURE = 7001 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(28, 32, 28, 32); setBackgroundColor(0xff101318.toInt())
        }
        val title = TextView(this).apply { text = "TRADEFLOAT AI 2.0"; textSize = 24f; setTextColor(0xffffffff.toInt()) }
        val info = TextView(this).apply {
            text = "Continuous chart capture • Hybrid analysis • Manual signals only\n\n1. Grant overlay permission\n2. Start screen capture\n3. Switch to your chart app\n4. Use the floating 30 sec / 1 min controls"
            textSize = 15f; setTextColor(0xffc7cbd1.toInt()); setPadding(0, 22, 0, 22)
        }
        val overlay = Button(this).apply { text = "1. Grant floating overlay permission"; setOnClickListener { requestOverlayPermission() } }
        val start = Button(this).apply { text = "2. Start continuous chart capture"; setOnClickListener { startCapturePermission() } }
        val stop = Button(this).apply { text = "Stop TradeFloat service"; setOnClickListener { stopService(Intent(this@MainActivity, CaptureOverlayService::class.java)) } }
        root.addView(title); root.addView(info); root.addView(overlay); root.addView(start); root.addView(stop)
        setContentView(root)
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) { Toast.makeText(this, "Overlay permission already granted.", Toast.LENGTH_SHORT).show(); return }
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun startCapturePermission() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Grant overlay permission first.", Toast.LENGTH_LONG).show(); return
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    @Deprecated("Legacy result callback used to keep this starter dependency-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            val service = Intent(this, CaptureOverlayService::class.java).apply {
                action = CaptureOverlayService.ACTION_START
                putExtra(CaptureOverlayService.EXTRA_RESULT_CODE, resultCode)
                putExtra(CaptureOverlayService.EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
            Toast.makeText(this, "Capture started. Switch to your chart app.", Toast.LENGTH_LONG).show()
        } else Toast.makeText(this, "Screen capture permission was not granted.", Toast.LENGTH_SHORT).show()
    }
}
