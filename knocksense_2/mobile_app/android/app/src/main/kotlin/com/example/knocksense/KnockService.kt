package com.example.knocksense

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import android.annotation.SuppressLint
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class KnockService : Service() {
    companion object {
        const val EVENT = "com.example.knocksense.EVENT"
        const val MONITOR = "monitor"
        const val ALARM = "alarm"
        @Volatile var active = false
        @Volatile var sensitivity = .60
        @Volatile var fireMode = false
        @Volatile var server = "http://192.168.1.100:8000"
        @Volatile var calibrating = false
        private const val RATE = 16000
    }

    private val sender = Executors.newSingleThreadExecutor()
    private val inFlight = AtomicBoolean(false)
    private var audio: AudioRecord? = null
    private var worker: Thread? = null
    private var calibrateSuccesses = 0
    @Volatile private var running = false
    private val ui = Handler(Looper.getMainLooper())
    private val deviceId by lazy { "device_" + android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID).take(32) }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("monitor", "Мониторинг KnockSense", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("knock", "Оповещения о стуке", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 350, 150, 350, 150, 600)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> { stopSelf(); return START_NOT_STICKY }
            "CALIBRATE" -> { calibrating = true; calibrateSuccesses = 0; event("status", "Калибровка: 3 серии по 3 стука"); return START_NOT_STICKY }
            "TEST" -> { alert("knock"); return START_NOT_STICKY }
        }
        if (running) return START_NOT_STICKY
        val notif = NotificationCompat.Builder(this, "monitor")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("KnockSense слушает дверь")
            .setContentText("Мониторинг активен • нажмите, чтобы открыть")
            .setContentIntent(PendingIntent.getActivity(this, 3,
                Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else startForeground(1, notif)
        startAudio()
        return START_NOT_STICKY
    }

    private fun startAudio() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            event("error", "Нет доступа к микрофону"); stopSelf(); return
        }
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) { event("error", "AudioRecord недоступен"); stopSelf(); return }
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); event("error", "Ошибка микрофона"); stopSelf(); return }
        try { rec.startRecording() } catch (e: Exception) {
            rec.release(); event("error", e.message ?: "Ошибка записи"); stopSelf(); return
        }
        audio = rec
        running = true
        active = true
        event("status", "Мониторинг активен")
        worker = thread(name = "KnockAudio") {
            val chunk = ShortArray(320)
            val window = ShortArray(RATE * 2)
            var offset = 0
            var buffered = 0
            var sinceLastWindow = 0
            while (running) {
                val n = try { rec.read(chunk, 0, chunk.size) } catch (_: Exception) { -1 }
                if (n <= 0) break
                for (i in 0 until n) {
                    window[offset] = chunk[i]
                    offset = (offset + 1) % window.size
                    if (buffered < window.size) buffered++
                    sinceLastWindow++
                    if (buffered == window.size && sinceLastWindow >= RATE) {
                        val samples = ShortArray(window.size) { j ->
                            window[(offset + j) % window.size]
                        }
                        sinceLastWindow = 0
                        if (inFlight.compareAndSet(false, true)) {
                            sender.execute {
                                try { upload(samples) } catch (e: Exception) {
                                    event("error", "Нет связи с сервером: ${e.message}")
                                } finally { inFlight.set(false) }
                            }
                        }
                    }
                }
            }
            if (running) stopSelf()
        }
    }

    private fun wav(samples: ShortArray): ByteArray {
        val dataSize = samples.size * 2
        val out = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()); out.putInt(36 + dataSize)
        out.put("WAVEfmt ".toByteArray()); out.putInt(16)
        out.putShort(1); out.putShort(1)
        out.putInt(RATE); out.putInt(RATE * 2)
        out.putShort(2); out.putShort(16)
        out.put("data".toByteArray()); out.putInt(dataSize)
        for (s in samples) out.putShort(s)
        return out.array()
    }

    private fun upload(samples: ShortArray) {
        val training = calibrating
        val path = if (training) "/api/calibrate" else "/api/detect"
        val url = URL(server.trimEnd('/') + path)
        val boundary = "KnockSenseBoundary"
        val out = ByteArrayOutputStream()
        fun field(name: String, value: String) {
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        }
        field("device_id", deviceId)
        if (!training) {
            field("sensitivity", sensitivity.toString())
            field("fire_mode", fireMode.toString().lowercase())
        }
        out.write("--$boundary\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"sound.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
        out.write(wav(samples))
        out.write("\r\n--$boundary--\r\n".toByteArray())
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.outputStream.use { it.write(out.toByteArray()) }
            val status = conn.responseCode
            val response = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (status !in 200..299) {
                if (training && status == 422) event("status", "Для калибровки постучите трижды")
                else event("error", "Сервер: HTTP $status")
                return
            }
            val obj = JSONObject(response)
            if (training) {
                calibrateSuccesses = obj.optInt("examples", 0)
                event("status", "Калибровка: $calibrateSuccesses/3 образцов")
                if (obj.optBoolean("ready")) {
                    calibrating = false
                    event("calibrated", "Калибровка завершена")
                }
            } else {
                if (obj.optBoolean("fire_tone")) alert("fire")
                else if (obj.optBoolean("knock")) alert("knock")
            }
        } finally { conn.disconnect() }
    }

    private var lastAlert = 0L
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun alert(kind: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAlert < 4000) return
        lastAlert = now
        val message = if (kind == "fire") "Возможный звук сигнализации!" else "Возможно, стучат в дверь!"
        val vib: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 350, 150, 350, 150, 600), -1))
        val n = NotificationCompat.Builder(this, "knock")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("KnockSense")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true).build()
        getSystemService(NotificationManager::class.java).notify((now % 100000).toInt() + 10, n)
        event(kind, message)
    }

    private fun event(type: String, value: String) {
        val intent = Intent(EVENT).setPackage(packageName)
            .putExtra("type", type).putExtra("message", value)
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        running = false
        active = false
        calibrating = false
        try { audio?.stop() } catch (_: Exception) {}
        worker?.interrupt()
        // release after read loop has a chance to exit
        try { worker?.join(600) } catch (_: InterruptedException) {}
        audio?.release(); audio = null
        sender.shutdownNow()
        event("status", "Мониторинг остановлен")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
