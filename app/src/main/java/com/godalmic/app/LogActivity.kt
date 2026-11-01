package com.godalmic.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class LogActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var checksBox: LinearLayout
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private var last: List<Check> = emptyList()
    private var shown = ""

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun box(fill: Int, r: Int) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(r).toFloat(); setStroke(dp(1), 0xFF2A2A30.toInt())
    }

    private fun button(text: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 14f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(if (primary) 0xFFFF2A2A.toInt() else 0xFF1E1E22.toInt()); cornerRadius = dp(22).toFloat()
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(6) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(12))
        }
        root.addView(TextView(this).apply {
            text = "Diagnóstico e log"; textSize = 22f; setTextColor(0xFFF1F3F4.toInt())
            typeface = Typeface.DEFAULT_BOLD
        })
        val row1 = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        }
        row1.addView(button("Executar diagnóstico", false) { runDiag() })
        row1.addView(button("Reparar tudo", true) { repair() })
        root.addView(row1)
        val rowR = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        }
        rowR.addView(button("RESETAR APP", true) { resetApp() })
        rowR.addView(button("Info do app (limpar dados)", false) { appInfo() })
        root.addView(rowR)

        val sc = ScrollView(this)
        checksBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = box(0xFF141214.toInt(), 16)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        sc.addView(checksBox)
        root.addView(sc, LinearLayout.LayoutParams(-1, 0, 1.1f).apply { topMargin = dp(12) })

        root.addView(TextView(this).apply {
            text = "LOG"; textSize = 11f; setTextColor(0xFF9AA0A6.toInt()); setPadding(dp(4), dp(10), 0, dp(2))
        })
        logScroll = ScrollView(this).apply { background = box(0xFF0A0A0C.toInt(), 12) }
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 11f; setTextColor(0xFFC9CDD6.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(8)); setTextIsSelectable(true)
        }
        logScroll.addView(logView)
        root.addView(logScroll, LinearLayout.LayoutParams(-1, 0, 1.4f))

        val row2 = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        }
        row2.addView(button("Copiar", false) { copy() })
        row2.addView(button("Compartilhar", false) { share() })
        row2.addView(button("Limpar", false) { AppLog.clear(); shown = "" })
        root.addView(row2)
        setContentView(root)
        runDiag()
    }

    private val tick = object : Runnable {
        override fun run() {
            val t = AppLog.text()
            if (t != shown) {
                shown = t
                logView.text = t
                logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
            h.postDelayed(this, 700)
        }
    }

    override fun onResume() { super.onResume(); h.post(tick); runDiag() }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }

    private fun runDiag() {
        Thread {
            val res = try { Diag.run(applicationContext) } catch (e: Exception) { emptyList() }
            h.post {
                last = res
                checksBox.removeAllViews()
                for (c in res) {
                    val (sym, col) = when (c.ok) {
                        true -> Pair("✔", 0xFF34D17A.toInt())
                        false -> Pair("✖", 0xFFFF5A4F.toInt())
                        null -> Pair("⚠", 0xFFFDD663.toInt())
                    }
                    val row = LinearLayout(this).apply {
                        setPadding(0, dp(7), 0, dp(7)); gravity = Gravity.TOP
                    }
                    row.addView(TextView(this).apply {
                        text = sym; textSize = 17f; setTextColor(col); width = dp(28)
                    })
                    val col2 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    col2.addView(TextView(this).apply {
                        text = c.name; textSize = 14f; setTextColor(0xFFF1F3F4.toInt()); typeface = Typeface.DEFAULT_BOLD
                    })
                    col2.addView(TextView(this).apply { text = c.detail; textSize = 12f; setTextColor(0xFF9AA0A6.toInt()) })
                    row.addView(col2)
                    checksBox.addView(row)
                }
                val bad = res.count { it.ok == false }
                AppLog.add("Diagnóstico: ${res.size} itens, $bad com problema" +
                    res.filter { it.ok == false }.joinToString("") { "\n   ✖ ${it.name}: ${it.detail}" })
            }
        }.start()
    }

    /** Limpa tudo que pode estar preso no app e recomeça: serviço, conexão, fonte do microfone, cache e log. */
    private fun resetApp() {
        AppLog.add("RESET: parando a gravação e limpando o estado do app...")
        startService(Intent(this, MicService::class.java).setAction(MicService.ACTION_STOP))
        try { cacheDir.deleteRecursively(); externalCacheDir?.deleteRecursively() } catch (_: Exception) { }
        MicService.cmdNext = false
        MicService.sent = 0
        MicService.linked = false
        h.postDelayed({
            AppLog.clear()
            AppLog.add("RESET: estado limpo. Reiniciando a gravação...")
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startForegroundService(Intent(this, MicService::class.java))
            } else {
                AppLog.add("RESET: falta a permissão do microfone, pedindo...")
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
            }
        }, 1200)
        h.postDelayed({
            AppLog.add("RESET: pronto. Deixe o GODALmic aberto no PC; se o celular pedir 'Permitir depuração USB', toque em Permitir.")
            runDiag()
        }, 4000)
    }

    private fun appInfo() {
        AppLog.add("Abrindo as informações do app: use 'Forçar parada' e 'Armazenamento > Limpar dados' se precisar.")
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun need(fix: String) = last.any { it.fix == fix && it.ok != true }

    private fun repair() {
        AppLog.add("Reparo automático iniciado")
        // 1) o que dá para resolver sozinho: reiniciar o serviço do zero (reconecta e volta à fonte padrão)
        if (MicService.running) {
            AppLog.add("Reiniciando o serviço de gravação...")
            startService(Intent(this, MicService::class.java).setAction(MicService.ACTION_STOP))
            h.postDelayed({
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                    startForegroundService(Intent(this, MicService::class.java))
            }, 900)
        }
        // 2) o que precisa de um toque seu (uma coisa por vez)
        when {
            need("perm") -> {
                AppLog.add("Pedindo a permissão do microfone...")
                val p = mutableListOf(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
                requestPermissions(p.toTypedArray(), 2)
                return
            }
            need("notif") -> {
                AppLog.add("Abrindo as configurações de notificações...")
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
            need("battery") -> {
                AppLog.add("Pedindo para liberar a bateria...")
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName))
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
            need("dev") -> {
                AppLog.add("Ligue a 'Depuração USB' nas opções do desenvolvedor.")
                try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) } catch (_: Exception) { }
            }
            need("usb") -> AppLog.add("Túnel USB fechado: abra o GODALmic no PC, confira o cabo e toque em 'Permitir' no aviso de Depuração USB. O PC tenta consertar sozinho.")
        }
        h.postDelayed({ runDiag() }, 3500)
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        AppLog.add("Permissões: " + p.zip(r.toList()).joinToString { "${it.first.substringAfterLast('.')}=${if (it.second == 0) "ok" else "negada"}" })
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED && !MicService.running)
            startForegroundService(Intent(this, MicService::class.java))
        h.postDelayed({ runDiag() }, 1500)
    }

    private fun fullText(): String =
        "GODALmic · celular\n" + last.joinToString("\n") { "${when (it.ok) { true -> "[OK]" ; false -> "[ERRO]"; null -> "[AVISO]" }} ${it.name}: ${it.detail}" } +
            "\n\n" + AppLog.text()

    private fun copy() {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("log", fullText()))
        Toast.makeText(this, "Log copiado", Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, fullText()), "Compartilhar log"))
    }
}
