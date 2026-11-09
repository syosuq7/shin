package com.example.screenanswer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.screenanswer.STOP"
        private const val CH = "screen_answer"
        private const val IDLE = 0xFF6D73E8.toInt()
        private const val BUSY = 0xFFF0A030.toInt()
    }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private var lastImage: Image? = null

    private var bubble: View? = null
    private var bubbleBg: GradientDrawable? = null
    private var card: TextView? = null
    private var cardTimer: Runnable? = null
    private var capW = 0
    private var capH = 0
    private var busy = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (projection != null) return START_NOT_STICKY

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH, "Screen Answer", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Screen Answer работает")
            .setContentText("Нажмите на кружок, чтобы получить ответ")
            .addAction(Notification.Action.Builder(null, "Остановить", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)

        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra("data", Intent::class.java)
        else intent?.getParcelableExtra("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(code, data)
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
        }, main)

        setupCapture(mp)
        showBubble()
        return START_NOT_STICKY
    }

    private fun setupCapture(mp: MediaProjection) {
        val dm = DisplayMetrics()
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(dm)
        capW = dm.widthPixels
        capH = dm.heightPixels
        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 3)
        reader = r
        // держим один самый свежий кадр: так ответ не зависит от того, менялся ли экран
        r.setOnImageAvailableListener({ rd ->
            val img = try { rd.acquireLatestImage() } catch (e: Exception) { null }
            if (img != null) { lastImage?.close(); lastImage = img }
        }, main)
        vd = mp.createVirtualDisplay(
            "screen-answer", capW, capH, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, main
        )
    }

    private fun showBubble() {
        val size = dp(56)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(IDLE)
            setStroke(dp(2), 0x99FFFFFF.toInt())
        }
        bubbleBg = bg
        val v = View(this).apply { background = bg; alpha = 0.88f }
        val lp = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(8); y = dp(220) }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0; var drag = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = lp.x; sy = lp.y; drag = false }
                MotionEvent.ACTION_MOVE -> {
                    val mx = e.rawX - dx; val my = e.rawY - dy
                    if (!drag && (Math.abs(mx) > slop || Math.abs(my) > slop)) drag = true
                    if (drag) { lp.x = sx + mx.toInt(); lp.y = sy + my.toInt(); wm.updateViewLayout(v, lp) }
                }
                MotionEvent.ACTION_UP -> if (!drag) onTap()
            }
            true
        }
        wm.addView(v, lp)
        bubble = v
    }

    private fun onTap() {
        if (busy) return
        busy = true
        hideCard()
        bubble?.visibility = View.INVISIBLE      // прячем кружок и ответ, чтобы их не было на скриншоте
        main.postDelayed({
            val img = lastImage
            if (img == null) { finish("⚠ Нет кадра экрана, нажмите ещё раз"); return@postDelayed }
            val bmp = try { toBitmap(img) } catch (e: Exception) { null }
            bubble?.visibility = View.VISIBLE
            bubbleBg?.setColor(BUSY)
            if (bmp == null) { finish("⚠ Не удалось прочитать кадр"); return@postDelayed }
            val cfg = readCfg()
            io.execute {
                val text = try {
                    val ans = Api.ask(cfg, encode(bmp))
                    ans.ifBlank { "(пустой ответ)" }
                } catch (e: Exception) {
                    "⚠ " + (e.message ?: e.toString()).take(300)
                }
                main.post { finish(text) }
            }
        }, 250)
    }

    private fun finish(text: String) {
        busy = false
        bubble?.visibility = View.VISIBLE
        bubbleBg?.setColor(IDLE)
        showCard(text)
    }

    private fun readCfg(): Cfg {
        val p = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val i = p.getInt("prov", 0)
        return Cfg(
            p.getString("type", "openai") ?: "openai",
            p.getString("url$i", "") ?: "",
            p.getString("key$i", "") ?: "",
            p.getString("model$i", "") ?: "",
            p.getString("prompt", "") ?: ""
        )
    }

    private fun toBitmap(img: Image): Bitmap {
        val pl = img.planes[0]
        val pad = pl.rowStride - pl.pixelStride * img.width
        val bmp = Bitmap.createBitmap(img.width + pad / pl.pixelStride, img.height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(pl.buffer)
        return if (pad == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
    }

    private fun encode(bmp: Bitmap): String {
        val s = 1600f / maxOf(bmp.width, bmp.height)
        val b = if (s < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true) else bmp
        val out = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun showCard(text: String) {
        hideCard()
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 17f
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply { setColor(0xEE1E1F24.toInt()); cornerRadius = dp(16).toFloat() }
            setOnClickListener { hideCard() }
        }
        val lp = WindowManager.LayoutParams(
            (capW * 0.92f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(90) }
        wm.addView(tv, lp)
        card = tv
        cardTimer = Runnable { hideCard() }.also { main.postDelayed(it, 30000) }
    }

    private fun hideCard() {
        cardTimer?.let { main.removeCallbacks(it) }
        card?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        card = null
    }

    override fun onDestroy() {
        hideCard()
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble = null
        lastImage?.close(); lastImage = null
        vd?.release(); reader?.close()
        projection?.stop()
        io.shutdownNow()
        super.onDestroy()
    }
}
