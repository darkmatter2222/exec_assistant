package com.execassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File

class TranscriptionService : Service(), RecognitionListener {

    private val binder = LocalBinder()
    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var lastPartial = ""
    
    var onPartialResult: ((String) -> Unit)? = null
    var onFinalResult: ((String) -> Unit)? = null
    var onStatusUpdate: ((String) -> Unit)? = null
    var onDebugUpdate: ((String) -> Unit)? = null

    companion object {
        private const val TAG = "TranscriptionService"
        private const val CHANNEL_ID = "transcription_channel"
        private const val NOTIFICATION_ID = 1
        private const val SAMPLE_RATE = 16000f
    }

    inner class LocalBinder : Binder() {
        fun getService(): TranscriptionService = this@TranscriptionService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification("Initializing..."))
        
        if (model == null) {
            loadModel()
        } else if (speechService == null) {
            startListening()
        }
        
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Transcription Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Continuous speech transcription"
                setShowBadge(false)
            }
            
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🎤 Transcribing")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(status: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(status))
    }

    private fun loadModel() {
        onStatusUpdate?.invoke("📥 Loading speech model...")
        onDebugUpdate?.invoke("🔧 Initializing Vosk...")
        
        val modelDir = File(filesDir, "model-en-us")
        
        if (modelDir.exists() && modelDir.isDirectory) {
            onDebugUpdate?.invoke("📂 Model found, loading...")
            loadModelFromPath(modelDir.absolutePath)
        } else {
            onDebugUpdate?.invoke("📦 Unpacking model from assets...")
            copyModelFromAssets()
        }
    }

    private fun copyModelFromAssets() {
        Thread {
            try {
                val modelDir = File(filesDir, "model-en-us")
                if (!modelDir.exists()) {
                    copyAssetFolder("model-en-us", modelDir)
                }
                android.os.Handler(mainLooper).post {
                    loadModelFromPath(modelDir.absolutePath)
                }
            } catch (e: Exception) {
                android.os.Handler(mainLooper).post {
                    onStatusUpdate?.invoke("❌ Model copy failed")
                    onDebugUpdate?.invoke("❌ Copy error: ${e.message}")
                }
                Log.e(TAG, "Model copy failed", e)
            }
        }.start()
    }

    private fun copyAssetFolder(assetPath: String, targetDir: File) {
        val assetManager = assets
        val files = assetManager.list(assetPath) ?: return
        
        targetDir.mkdirs()
        
        for (file in files) {
            val assetFilePath = "$assetPath/$file"
            val targetFile = File(targetDir, file)
            
            try {
                assetManager.open(assetFilePath).use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                copyAssetFolder(assetFilePath, targetFile)
            }
        }
    }

    private fun loadModelFromPath(path: String) {
        try {
            onDebugUpdate?.invoke("📂 Loading model...")
            model = Model(path)
            onDebugUpdate?.invoke("✅ Model loaded")
            startListening()
        } catch (e: Exception) {
            onStatusUpdate?.invoke("❌ Model load failed")
            onDebugUpdate?.invoke("❌ Load error: ${e.message}")
            Log.e(TAG, "Model load failed", e)
        }
    }

    private fun startListening() {
        if (model == null) {
            onDebugUpdate?.invoke("⚠️ Model not loaded")
            return
        }

        try {
            val recognizer = Recognizer(model, SAMPLE_RATE)
            recognizer.setMaxAlternatives(3)
            recognizer.setWords(true)
            
            speechService = SpeechService(recognizer, SAMPLE_RATE)
            speechService?.startListening(this)
            
            onStatusUpdate?.invoke("🎤 LISTENING (background enabled)")
            updateNotification("Listening...")
            onDebugUpdate?.invoke("🎤 Continuous listening started!")
            
        } catch (e: Exception) {
            onStatusUpdate?.invoke("❌ Failed to start")
            onDebugUpdate?.invoke("❌ Error: ${e.message}")
            Log.e(TAG, "Start listening failed", e)
        }
    }

    private fun restartListening() {
        try {
            speechService?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping service", e)
        }
        speechService = null
        
        android.os.Handler(mainLooper).postDelayed({
            if (model != null) {
                startListening()
            }
        }, 100)
    }

    // RecognitionListener callbacks

    override fun onPartialResult(hypothesis: String?) {
        if (hypothesis.isNullOrEmpty()) return
        
        try {
            val json = org.json.JSONObject(hypothesis)
            val partial = json.optString("partial", "")
            if (partial.isNotEmpty()) {
                lastPartial = partial
                onPartialResult?.invoke(partial)
                updateNotification("\"$partial\"")
            }
        } catch (e: Exception) {
            lastPartial = hypothesis
            onPartialResult?.invoke(hypothesis)
        }
    }

    override fun onResult(hypothesis: String?) {
        Log.d(TAG, "onResult: $hypothesis")
        
        var text = ""
        
        if (!hypothesis.isNullOrEmpty()) {
            try {
                val json = org.json.JSONObject(hypothesis)
                text = json.optString("text", "")
            } catch (e: Exception) {
                Log.e(TAG, "Parse error", e)
            }
        }
        
        if (text.isEmpty() && lastPartial.isNotEmpty()) {
            text = lastPartial
            onDebugUpdate?.invoke("🔄 Using last partial")
        }
        
        if (text.isNotEmpty()) {
            onFinalResult?.invoke(text)
            onDebugUpdate?.invoke("✅ \"$text\"")
            updateNotification("Listening...")
        }
        
        lastPartial = ""
    }

    override fun onFinalResult(hypothesis: String?) {
        Log.d(TAG, "onFinalResult: $hypothesis")
        onDebugUpdate?.invoke("🏁 Final result")
        
        if (!hypothesis.isNullOrEmpty()) {
            try {
                val json = org.json.JSONObject(hypothesis)
                val text = json.optString("text", "")
                if (text.isNotEmpty()) {
                    onFinalResult?.invoke(text)
                    onDebugUpdate?.invoke("✅ Final: \"$text\"")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Final parse error", e)
            }
        }
        
        onDebugUpdate?.invoke("🔄 Restarting...")
        restartListening()
    }

    override fun onError(error: Exception?) {
        onDebugUpdate?.invoke("❌ Error: ${error?.message}")
        Log.e(TAG, "Speech error", error)
        restartListening()
    }

    override fun onTimeout() {
        onDebugUpdate?.invoke("⏰ Timeout - restarting...")
        restartListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        speechService?.stop()
        speechService?.shutdown()
        model?.close()
    }
}
