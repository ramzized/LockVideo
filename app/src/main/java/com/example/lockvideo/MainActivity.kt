package com.example.lockvideo

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class PreviewView(c: Context) : View(c) {
    var bmp: Bitmap? = null
    var zoom = 1f
    var px = 0.5f
    var py = 0.5f
    private val dst = RectF()

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val b = bmp ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val r = videoRect(w, h, b.width.toFloat(), b.height.toFloat(), zoom, px, py)
        val cx = w / 2f + r[2] * w / 2f
        val cy = h / 2f - r[3] * h / 2f
        dst.set(cx - r[0] * w / 2f, cy - r[1] * h / 2f, cx + r[0] * w / 2f, cy + r[1] * h / 2f)
        canvas.drawBitmap(b, null, dst, null)
    }
}

class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var preview: PreviewView

    private fun prefs() = getSharedPreferences("p", MODE_PRIVATE)

    private val pickVideo =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            prefs().edit().putString("uri", uri.toString()).apply()
            status.text = "تم اختيار الفيديو ✓ اضبط الظهور ثم اضغط «تعيين كخلفية»"
            loadThumb(uri.toString())
        }

    private fun loadThumb(uri: String) {
        Thread {
            try {
                val m = MediaMetadataRetriever()
                m.setDataSource(this@MainActivity, Uri.parse(uri))
                val frame = m.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                m.release()
                runOnUiThread {
                    preview.bmp = frame
                    preview.invalidate()
                }
            } catch (e: Exception) {
            }
        }.start()
    }

    private fun slider(label: String, top: Int, start: Int, onChange: (Int) -> Unit): LinearLayout {
        val bar = SeekBar(this)
        bar.max = top
        bar.progress = start
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                onChange(p)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, 24, 0, 0)
        box.addView(TextView(this).apply { text = label })
        box.addView(bar)
        return box
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val p = prefs()
        val saved = p.getString("uri", null)

        status = TextView(this).apply {
            text = if (saved != null) "يوجد فيديو محفوظ ✓" else "لم تختر فيديو بعد"
            textSize = 16f
            gravity = Gravity.CENTER
        }

        val dm = resources.displayMetrics
        val pw = (dm.widthPixels * 0.45f).toInt()
        val ph = (pw * dm.heightPixels.toFloat() / dm.widthPixels).toInt()
        preview = PreviewView(this)
        preview.zoom = p.getFloat("zoom", 1f)
        preview.px = p.getFloat("px", 0.5f)
        preview.py = p.getFloat("py", 0.5f)
        val lp = LinearLayout.LayoutParams(pw, ph)
        lp.gravity = Gravity.CENTER_HORIZONTAL
        lp.topMargin = 32
        lp.bottomMargin = 16
        preview.layoutParams = lp
        if (saved != null) loadThumb(saved)

        val zoomBar = slider("تكبير / تصغير", 250, ((preview.zoom - 0.5f) * 100).toInt()) { v ->
            val z = 0.5f + v / 100f
            prefs().edit().putFloat("zoom", z).apply()
            preview.zoom = z
            preview.invalidate()
        }
        val xBar = slider("الموضع الأفقي", 100, (preview.px * 100).toInt()) { v ->
            prefs().edit().putFloat("px", v / 100f).apply()
            preview.px = v / 100f
            preview.invalidate()
        }
        val yBar = slider("الموضع العمودي", 100, (preview.py * 100).toInt()) { v ->
            prefs().edit().putFloat("py", v / 100f).apply()
            preview.py = v / 100f
            preview.invalidate()
        }

        val choose = Button(this).apply {
            text = "اختيار فيديو"
            setOnClickListener { pickVideo.launch(arrayOf("video/*")) }
        }
        val apply = Button(this).apply {
            text = "تعيين كخلفية"
            setOnClickListener { openWallpaperPicker() }
        }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(48, 48, 48, 48)
        root.addView(status)
        root.addView(preview)
        root.addView(zoomBar)
        root.addView(xBar)
        root.addView(yBar)
        root.addView(choose)
        root.addView(apply)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
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
