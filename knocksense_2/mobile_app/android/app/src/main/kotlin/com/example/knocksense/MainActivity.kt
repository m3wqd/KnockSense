package com.example.knocksense

import android.content.*
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private var sink: EventChannel.EventSink? = null
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == KnockService.EVENT) {
                sink?.success(mapOf("type" to intent.getStringExtra("type"),
                    "message" to intent.getStringExtra("message")))
            }
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "knocksense/control")
            .setMethodCallHandler { call, result ->
                try {
                    when (call.method) {
                        "start" -> {
                            KnockService.server = call.argument<String>("server") ?: ""
                            require(KnockService.server.startsWith("http://") || KnockService.server.startsWith("https://")) { "Некорректный URL" }
                            KnockService.sensitivity = call.argument<Double>("sensitivity") ?: .6
                            KnockService.fireMode = call.argument<Boolean>("fireMode") ?: false
                            ContextCompat.startForegroundService(this, Intent(this, KnockService::class.java))
                            result.success(null)
                        }
                        "stop" -> { stopService(Intent(this, KnockService::class.java)); result.success(null) }
                        "calibrate" -> {
                            require(KnockService.active) { "Сначала включите мониторинг" }
                            startService(Intent(this, KnockService::class.java).setAction("CALIBRATE"))
                            result.success(null)
                        }
                        "test" -> {
                            require(KnockService.active) { "Сначала включите мониторинг" }
                            startService(Intent(this, KnockService::class.java).setAction("TEST"))
                            result.success(null)
                        }
                        "configure" -> {
                            KnockService.sensitivity = call.argument<Double>("sensitivity") ?: .6
                            KnockService.fireMode = call.argument<Boolean>("fireMode") ?: false
                            result.success(null)
                        }
                        "state" -> result.success(KnockService.active)
                        else -> result.notImplemented()
                    }
                } catch (e: Exception) { result.error("NATIVE", e.message, null) }
            }
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, "knocksense/events")
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    sink = events
                    if (!registered) {
                        ContextCompat.registerReceiver(this@MainActivity, receiver,
                            IntentFilter(KnockService.EVENT), ContextCompat.RECEIVER_NOT_EXPORTED)
                        registered = true
                    }
                }
                override fun onCancel(arguments: Any?) { sink = null; removeReceiver() }
            })
    }

    private fun removeReceiver() {
        if (registered) { unregisterReceiver(receiver); registered = false }
    }
    override fun onDestroy() { removeReceiver(); super.onDestroy() }
}
