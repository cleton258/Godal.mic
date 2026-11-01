package com.godalmic.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** Serviço em primeiro plano: captura o microfone mesmo com a tela desligada e envia ao PC. */
class MicService : Service() {
    companion object {
        @Volatile var running = false
        @Volatile var status = "Parado"
        @Volatile var peak = 0f
        @Volatile var linked = false
        @Volatile var sent = 0L
        @Volatile var sourceName = "-"
        @Volatile var cmdNext = false
        const val PORT = 8443
        const val RATE = 48000
        const val ACTION_STOP = "com.godalmic.app.STOP"
        private const val CHANNEL = "godalmic"
        private val SOURCES = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        private val NAMES = arrayOf("UNPROCESSED", "MIC", "VOICE_RECOGNITION", "CAMCORDER", "VOICE_COMMUNICATION")
    }

    private var thread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var pcError = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "GODALmic", NotificationManager.IMPORTANCE_LOW))
        }
        val stopPi = PendingIntent.getService(
            this, 0, Intent(this, MicService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        val openPi = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
                else Notification.Builder(this)
        val notif = b.setContentTitle("GODALmic")
            .setContentText("Enviando o microfone para o PC")
            .setSmallIcon(R.drawable.ic_mic)
            .setColor(0xFFFF2A2A.toInt())
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Parar", stopPi).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notif)
        }

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "godalmic:wl").also { it.acquire() }

        running = true
        linked = false
        sent = 0
        cmdNext = false
        pcError = ""
        status = "Iniciando..."
        AppLog.add("Serviço iniciado (${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE})")
        thread = Thread { loop() }.also { it.start() }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ conexão com o PC
    private inner class Link(private val client: OkHttpClient) {
        @Volatile private var ws: WebSocket? = null
        @Volatile private var attempt = 0
        private val up = AtomicBoolean(false)
        private var fails = 0
        private var lastTry = 0L

        fun tick(now: Long) {
            if (up.get()) return
            val wait = minOf(1500L + fails * 500L, 5000L)
            if (now - lastTry < wait) return
            lastTry = now
            connect()
        }

        private fun markDown(id: Int, why: String) {
            if (id != attempt) return
            attempt++                       // ignora avisos repetidos do mesmo socket
            up.set(false)
            linked = false
            fails++
            AppLog.add(why)
            status = when {
                pcError.isNotEmpty() -> "PC: $pcError"
                fails >= 3 -> "Sem conexão com o PC (cabo, depuração USB, GODALmic aberto?)"
                else -> "Sem conexão com o PC. Tentando..."
            }
            if (fails == 3) AppLog.add("DICA: confira o cabo, 'Depuração USB' ligada e se o GODALmic está aberto no PC.")
        }

        private fun connect() {
            ws?.cancel()
            val id = ++attempt
            AppLog.add("Conectando ao PC (127.0.0.1:$PORT)...")
            val req = Request.Builder().url("ws://127.0.0.1:$PORT/ws").build()
            ws = client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(w: WebSocket, response: Response) {
                    if (id != attempt) return
                    ws = w
                    w.send("{\"rate\":$RATE}")
                    up.set(true); linked = true; fails = 0
                    status = "Transmitindo para o PC"
                    AppLog.add("Conectado ao PC ✔")
                    AppLog.add("Celular ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, microfone=$sourceName", true)
                }
                override fun onMessage(w: WebSocket, text: String) { handleMsg(text) }
                override fun onClosing(w: WebSocket, code: Int, reason: String) {
                    try { w.close(1000, null) } catch (_: Exception) { }
                    markDown(id, "O PC encerrou a conexão ($code ${reason.take(60)}).")
                }
                override fun onClosed(w: WebSocket, code: Int, reason: String) {
                    markDown(id, "Conexão encerrada ($code).")
                }
                override fun onFailure(w: WebSocket, t: Throwable, response: Response?) {
                    markDown(id, "Falha ao conectar: ${t.javaClass.simpleName}: ${t.message}")
                }
            })
        }

        private fun handleMsg(text: String) {
            try {
                val j = JSONObject(text)
                if (j.optString("cmd") == "next_source") {
                    AppLog.add("PC pediu para trocar a fonte do microfone.")
                    cmdNext = true
                }
                if (j.has("error")) {
                    pcError = j.optString("error")
                    status = "PC: $pcError"
                    AppLog.add("PC informou erro: $pcError")
                }
                if (j.optBoolean("ok", false)) {
                    pcError = ""
                    AppLog.add("PC pronto para receber áudio (${j.optString("sink")}).")
                }
            } catch (_: Exception) {
                AppLog.add("PC: ${text.take(120)}")
            }
        }

        fun sendLog(line: String) {
            if (up.get()) try { ws?.send(JSONObject().put("log", line).toString()) } catch (_: Exception) { }
        }

        fun send(buf: ByteArray, n: Int) {
            if (!up.get()) return
            val w = ws ?: return
            if (w.queueSize() > 20000) return          // PC lento: descarta em vez de acumular atraso
            if (w.send(buf.toByteString(0, n))) sent += n
            else markDown(attempt, "Não consegui enviar: a conexão caiu.")
        }

        fun close() { try { ws?.cancel() } catch (_: Exception) { } }
    }

    // ------------------------------------------------------------------ captura
    private fun openRecorder(start: Int): Pair<AudioRecord, Int>? {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, RATE / 5 * 2)
        for (i in SOURCES.indices) {
            val k = (start + i) % SOURCES.size
            try {
                val r = AudioRecord(SOURCES[k], RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
                if (r.state == AudioRecord.STATE_INITIALIZED) {
                    sourceName = NAMES[k]
                    AppLog.add("Microfone aberto: fonte ${NAMES[k]}", true)
                    return Pair(r, k)
                }
                r.release()
            } catch (e: Exception) {
                AppLog.add("Fonte ${NAMES[k]} indisponível: ${e.message}")
            }
        }
        status = "Erro: não consegui abrir o microfone"
        AppLog.add("Nenhuma fonte de microfone abriu (permissão negada ou outro app usando o microfone).", true)
        return null
    }

    /** 0 = parar, 1 = trocar de fonte, 2 = erro (reabrir). */
    private fun pump(rec: AudioRecord, link: Link, canRotate: Boolean): Int {
        rec.startRecording()
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            AppLog.add("O microfone não começou a gravar (outro app está usando?).")
            return 2
        }
        val shorts = ShortArray(480)
        val bb = ByteBuffer.allocate(960).order(ByteOrder.LITTLE_ENDIAN)
        var silent = 0
        var warned = false
        var lastStat = System.currentTimeMillis()
        var lastSent = sent
        var maxPeak = 0
        while (running && !Thread.currentThread().isInterrupted) {
            var n = 0
            while (n < 480) {
                val r = rec.read(shorts, n, 480 - n)
                if (r < 0) { AppLog.add("Erro de leitura do microfone ($r)"); return 2 }
                n += r
            }
            var p = 0
            for (s in shorts) { val a = abs(s.toInt()); if (a > p) p = a }
            peak = p / 32768f
            if (p > maxPeak) maxPeak = p
            if (p < 3) silent++ else { silent = 0; warned = false }
            bb.clear(); bb.asShortBuffer().put(shorts)
            val now = System.currentTimeMillis()
            link.tick(now)
            link.send(bb.array(), 960)

            if (cmdNext) { cmdNext = false; if (canRotate) return 1 }
            if (silent >= 600) {                      // 6 s de silêncio digital
                if (canRotate) {
                    AppLog.add("Microfone mudo por 6 s na fonte $sourceName: trocando de fonte.", true)
                    return 1
                } else if (!warned) {
                    warned = true
                    status = "Microfone sem sinal"
                    AppLog.add("Todas as fontes enviam silêncio. Veja permissão, outro app usando o microfone ou capa tapando.", true)
                }
            }
            if (now - lastStat >= 5000) {
                val kbs = (sent - lastSent) / 1024.0 / ((now - lastStat) / 1000.0)
                AppLog.add("Enviando %.0f KB/s · pico %d · %s".format(kbs, maxPeak, if (linked) "PC ok" else "PC sem conexão"))
                lastStat = now; lastSent = sent; maxPeak = 0
            }
        }
        return 0
    }

    private fun loop() {
        val client = OkHttpClient.Builder()
            .pingInterval(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val link = Link(client)
        AppLog.remote = { line -> link.sendLog(line) }
        var idx = 0
        var rotations = 0
        var errors = 0
        try {
            while (running && !Thread.currentThread().isInterrupted) {
                val opened = openRecorder(idx) ?: break
                idx = opened.second
                val rec = opened.first
                val why = try { pump(rec, link, rotations < SOURCES.size) }
                          finally { try { rec.stop() } catch (_: Exception) { }; rec.release() }
                when (why) {
                    1 -> { idx = (idx + 1) % SOURCES.size; rotations++ }
                    2 -> {
                        errors++
                        if (errors > 5) { status = "Erro no microfone"; AppLog.add("Muitos erros no microfone: parando."); break }
                        Thread.sleep(500)
                    }
                    else -> break
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            AppLog.remote = null
            link.close()
            client.dispatcher.executorService.shutdown()
            peak = 0f
            linked = false
            if (running) stopSelf()
        }
    }

    override fun onDestroy() {
        running = false
        thread?.interrupt()
        try { wakeLock?.release() } catch (_: Exception) { }
        status = "Parado"
        AppLog.add("Serviço parado")
        super.onDestroy()
    }
}
