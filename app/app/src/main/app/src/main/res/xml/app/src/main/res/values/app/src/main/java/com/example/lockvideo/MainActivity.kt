package com.example.lockvideo

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

    private lateinit var status: TextView

    private val pickVideo =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            getSharedPreferences("p", MODE_PRIVATE).edit().putString("uri", uri.toString()).apply()
            status.text = "تم اختيار الفيديو ✓ الآن اضغط «تعيين كخلفية»"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val saved = getSharedPreferences("p", MODE_PRIVATE).getString("uri", null)
        status = TextView(this).apply {
            text = if (saved != null) "يوجد فيديو محفوظ ✓" else "لم تختر فيديو بعد"
            textSize = 16f
            gravity = Gravity.CENTER
        }
        val choose = Button(this).apply {
            text = "اختيار فيديو"
            setOnClickListener { pickVideo.launch(arrayOf("video/*")) }
        }
        val apply = Button(this).apply {
            text = "تعيين كخلفية"
            setOnClickListener { openWallpaperPicker() }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
            addView(status)
            addView(choose)
            addView(apply)
        })
    }

    private fun openWallpaperPicker() {
        try {
            startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(this, VideoWallpaperService::class.java)
            ))
        } catch (e: Exception) {
            startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        }
    }
}
