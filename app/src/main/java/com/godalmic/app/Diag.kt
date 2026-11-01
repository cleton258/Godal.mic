package com.godalmic.app

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.PowerManager
import android.provider.Settings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlin.math.abs
import kotlin.math.max

/** ok: true = certo, false = problema, null = aviso. fix = ação do botão "Reparar". */
data class Check(val name: String, val ok: Boolean?, val detail: String, val fix: String? = null)

object Diag {
    fun run(c: Context): List<Check> {
        val out = ArrayList<Check>()

        val perm = c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        out += Check("Permissão do microfone", perm,
            if (perm) "Concedida" else "Negada: o Reparar pede a permissão", "perm")

        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = nm.areNotificationsEnabled()
        out += Check("Notificações", notif,
            if (notif) "Ativas (mantêm a gravação com a tela apagada)" else "Desativadas: o Android pode parar a gravação", "notif")

        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        val bat = pm.isIgnoringBatteryOptimizations(c.packageName)
        out += Check("Bateria sem restrição", if (bat) true else null,
            if (bat) "Liberada" else "Com economia de bateria: pode parar com a tela apagada", "battery")

        val adbOn = try { Settings.Global.getInt(c.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1 } catch (_: Exception) { false }
        out += Check("Depuração USB", adbOn,
            if (adbOn) "Ligada" else "Desligada: sem ela o PC não cria o túnel USB", "dev")

        out += Check("Serviço de gravação", MicService.running,
            if (MicService.running) "Ativo · fonte ${MicService.sourceName}" else "Parado: toque em Iniciar", "restart")

        if (!MicService.running && perm) {
            val (ok, d) = micTest()
            out += Check("Teste do microfone", ok, d)
        } else if (MicService.running) {
            val p = (MicService.peak * 32768).toInt()
            out += Check("Sinal do microfone", if (p >= 3) true else null,
                if (p >= 3) "Chegando som (pico $p)" else "Sem som agora (fale para testar)")
        }

        val tunnel = tcpOpen()
        out += Check("Túnel USB até o PC (porta ${MicService.PORT})", tunnel,
            if (tunnel) "Aberto" else "Fechado: o GODALmic precisa estar aberto no PC, com o cabo e a Depuração USB autorizada (toque em 'Permitir' no aviso)",
            "usb")

        if (tunnel) {
            try {
                val con = URL("http://127.0.0.1:${MicService.PORT}/health").openConnection() as HttpURLConnection
                con.connectTimeout = 1500; con.readTimeout = 1500
                val j = JSONObject(con.inputStream.bufferedReader().readText())
                val err = j.optString("error")
                val cable = j.optString("cable")
                when {
                    err.isNotEmpty() -> out += Check("GODALmic no PC", false, "O PC reportou: $err")
                    cable.isEmpty() -> out += Check("GODALmic no PC", false, "VB-Cable não encontrado no PC (instale e reinicie o PC)")
                    else -> out += Check("GODALmic no PC", true, "Respondendo · saída: $cable")
                }
            } catch (e: Exception) {
                out += Check("GODALmic no PC", false, "Não respondeu (${e.javaClass.simpleName})")
            }
        }

        out += Check("Conexão de áudio com o PC", if (MicService.linked) true else false,
            if (MicService.linked) "Conectado · enviados ${MicService.sent / 1024} KB" else "Sem conexão: veja o log abaixo")
        return out
    }

    private fun tcpOpen(): Boolean {
        val s = Socket()
        return try { s.connect(InetSocketAddress("127.0.0.1", MicService.PORT), 1200); true }
        catch (_: Exception) { false } finally { try { s.close() } catch (_: Exception) { } }
    }

    private fun micTest(): Pair<Boolean, String> {
        return try {
            val rate = 48000
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(min, 9600))
            if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return Pair(false, "Não abriu (outro app usando o microfone?)") }
            r.startRecording()
            val buf = ShortArray(2400)
            var peak = 0
            var got = 0
            val t0 = System.currentTimeMillis()
            while (got < 24000 && System.currentTimeMillis() - t0 < 1500) {
                val n = r.read(buf, 0, buf.size)
                if (n <= 0) break
                got += n
                for (i in 0 until n) peak = max(peak, abs(buf[i].toInt()))
            }
            r.stop(); r.release()
            if (peak >= 3) Pair(true, "Abriu e captou som (pico $peak)") else Pair(false, "Abriu, mas só silêncio")
        } catch (e: Exception) {
            Pair(false, "Erro: ${e.message}")
        }
    }
}
