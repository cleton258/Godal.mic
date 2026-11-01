package com.godalmic.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max
import kotlin.math.sin

private val RED = 0xFFFF2A2A.toInt()
private val CARD = 0xFF141214.toInt()
private val LINE = 0xFF2A2A30.toInt()
private val MUTED = 0xFF9AA0A6.toInt()
private val TEXT = 0xFFF1F3F4.toInt()

/** Medidor de nível em segmentos (estilo da janela do PC). */
class LevelBar(c: Context) : View(c) {
    var level = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    override fun onDraw(canvas: Canvas) {
        val n = 30
        val gap = width / 160f
        val w = (width - gap * (n - 1)) / n
        val lit = (level * n).toInt()
        for (i in 0 until n) {
            p.color = when {
                i == lit - 1 && lit > 0 && i > n * 0.8 -> Color.WHITE
                i < lit -> RED
                else -> 0xFF2A1618.toInt()
            }
            r.set(i * (w + gap), 0f, i * (w + gap) + w, height.toFloat())
            canvas.drawRoundRect(r, w / 3, w / 3, p)
        }
    }
}

class MainActivity : Activity() {
    private lateinit var st: TextView
    private lateinit var label: TextView
    private lateinit var ring: View
    private lateinit var disc: FrameLayout
    private lateinit var icon: ImageView
    private lateinit var bar: LevelBar
    private val h = Handler(Looper.getMainLooper())
    private var lv = 0f
    private var shownRunning: Boolean? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun shape(fill: Int, radius: Int, stroke: Int = 0, strokeColor: Int = 0, oval: Boolean = false) =
        GradientDrawable().apply {
            if (oval) shape = GradientDrawable.OVAL else cornerRadius = dp(radius).toFloat()
            setColor(fill)
            if (stroke > 0) setStroke(dp(stroke), strokeColor)
        }

    private fun mkIcon(res: Int, size: Int, color: Int) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(color)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(CARD, 20, 1, LINE)
        setPadding(dp(18), dp(16), dp(18), dp(16))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
    }

    private fun tonal(res: Int, text: String, onClick: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        background = shape(0xFF1E1E22.toInt(), 28, 1, RED)
        isClickable = true
        setPadding(dp(18), 0, dp(18), 0)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(14) }
        addView(mkIcon(res, 22, RED))
        addView(TextView(this@MainActivity).apply {
            this.text = text
            setTextColor(TEXT); textSize = 15f; setPadding(dp(10), 0, 0, 0)
        })
    }

    private val tick = object : Runnable {
        override fun run() {
            val running = MicService.running
            st.text = MicService.status
            lv = max(MicService.peak * 1.4f, lv * 0.8f).coerceIn(0f, 1f)
            bar.level = lv; bar.invalidate()
            if (shownRunning != running) {
                shownRunning = running
                disc.background = if (running) shape(RED, 0, oval = true)
                                  else shape(0xFF1E1E22.toInt(), 0, 1, LINE, true)
                icon.setImageResource(if (running) R.drawable.ic_stop else R.drawable.ic_mic)
                icon.setColorFilter(if (running) Color.WHITE else RED)
                label.text = if (running) "Toque para parar" else "Toque para iniciar"
            }
            if (running) {
                val k = (sin(System.currentTimeMillis() / 350.0) + 1).toFloat() / 2
                ring.scaleX = 1f + 0.08f * k; ring.scaleY = ring.scaleX
                ring.alpha = 0.9f - 0.55f * k
            } else { ring.scaleX = 1f; ring.scaleY = 1f; ring.alpha = 0.25f }
            h.postDelayed(this, 40)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        col.addView(ImageView(this).apply {
            setImageResource(R.drawable.logo_wordmark)
            adjustViewBounds = true
            contentDescription = "GODALmic"
            layoutParams = LinearLayout.LayoutParams(dp(250), -2)
        })
        col.addView(TextView(this).apply {
            text = "Microfone do celular no PC"
            setTextColor(MUTED); textSize = 14f
            setPadding(0, dp(6), 0, dp(24))
        })

        // botão principal circular (FAB grande)
        val stack = FrameLayout(this)
        ring = View(this).apply { background = shape(Color.TRANSPARENT, 0, 3, RED, true) }
        stack.addView(ring, FrameLayout.LayoutParams(dp(200), dp(200), Gravity.CENTER))
        disc = FrameLayout(this).apply {
            isClickable = true
            contentDescription = "Iniciar ou parar"
            elevation = dp(8).toFloat()
            setOnClickListener { if (MicService.running) stopSvc() else ensurePermsThenStart() }
        }
        icon = ImageView(this).apply { setImageResource(R.drawable.ic_mic) }
        disc.addView(icon, FrameLayout.LayoutParams(dp(68), dp(68), Gravity.CENTER))
        stack.addView(disc, FrameLayout.LayoutParams(dp(160), dp(160), Gravity.CENTER))
        col.addView(stack, LinearLayout.LayoutParams(dp(220), dp(220)))

        label = TextView(this).apply {
            setTextColor(TEXT); textSize = 16f; setPadding(0, dp(10), 0, dp(6))
        }
        col.addView(label)

        // card de estado + nível
        val c1 = card()
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(mkIcon(R.drawable.ic_graphic_eq, 20, RED))
        st = TextView(this).apply {
            setTextColor(TEXT); textSize = 16f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(10), 0, 0, 0)
        }
        top.addView(st)
        c1.addView(top)
        bar = LevelBar(this)
        c1.addView(bar, LinearLayout.LayoutParams(-1, dp(26)).apply { topMargin = dp(14) })
        col.addView(c1)

        // botões tonais: bateria e diagnóstico
        col.addView(tonal(R.drawable.ic_battery, "Liberar uso da bateria") { askBattery() })
        col.addView(tonal(R.drawable.ic_list, "Diagnóstico e log") { startActivity(Intent(this, LogActivity::class.java)) })

        // dica
        val c2 = card()
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(mkIcon(R.drawable.ic_info, 22, MUTED))
        row.addView(TextView(this).apply {
            text = "Conecte o cabo USB e abra o GODALmic no PC. Pode apagar a tela ou sair do app: a gravação continua pela notificação."
            setTextColor(MUTED); textSize = 13f; setPadding(dp(12), 0, 0, 0)
        })
        c2.addView(row)
        col.addView(c2)

        setContentView(ScrollView(this).apply { isFillViewport = true; addView(col) })
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra("autostart", false) == true) ensurePermsThenStart()
    }

    override fun onResume() { super.onResume(); shownRunning = null; h.post(tick) }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }

    private fun ensurePermsThenStart() {
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isEmpty()) startSvc() else requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startSvc()
        else st.text = "Sem permissão de microfone"
    }

    private fun startSvc() {
        if (!MicService.running) startForegroundService(Intent(this, MicService::class.java))
    }

    private fun stopSvc() {
        startService(Intent(this, MicService::class.java).setAction(MicService.ACTION_STOP))
    }

    private fun askBattery() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) { st.text = "Bateria já liberada"; return }
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")))
    }
}
