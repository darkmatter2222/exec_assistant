package com.execassistant

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var transcriptText: TextView
    private lateinit var statusText: TextView
    private lateinit var liveText: TextView
    private lateinit var debugText: TextView
    private lateinit var scrollView: ScrollView

    private var transcriptionService: TranscriptionService? = null
    private var serviceBound = false
    
    private val fullTranscript = StringBuilder()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    companion object {
        private const val TAG = "ExecAssistant"
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TranscriptionService.LocalBinder
            transcriptionService = binder.getService()
            serviceBound = true
            
            // Set up callbacks
            transcriptionService?.onPartialResult = { partial ->
                runOnUiThread {
                    liveText.text = "\"$partial\""
                }
            }
            
            transcriptionService?.onFinalResult = { text ->
                runOnUiThread {
                    val timestamp = dateFormat.format(Date())
                    fullTranscript.append("[$timestamp] $text\n\n")
                    transcriptText.text = fullTranscript.toString()
                    scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                    liveText.text = "Speak anytime..."
                }
            }
            
            transcriptionService?.onStatusUpdate = { status ->
                runOnUiThread {
                    statusText.text = status
                }
            }
            
            transcriptionService?.onDebugUpdate = { message ->
                runOnUiThread {
                    val timestamp = dateFormat.format(Date())
                    val currentText = debugText.text.toString()
                    val lines = currentText.split("\n").takeLast(20)
                    val newText = (lines + "[$timestamp] $message").joinToString("\n")
                    debugText.text = newText
                }
            }
            
            updateDebug("✅ Service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            transcriptionService = null
            serviceBound = false
            updateDebug("❌ Service disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        transcriptText = findViewById(R.id.transcriptText)
        statusText = findViewById(R.id.statusText)
        liveText = findViewById(R.id.liveText)
        debugText = findViewById(R.id.debugText)
        scrollView = findViewById(R.id.scrollView)

        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        
        // Android 13+ needs POST_NOTIFICATIONS for foreground service
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (needed.isNotEmpty()) {
            updateStatus("🔒 Requesting permissions...")
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            startTranscriptionService()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                updateDebug("✅ Permissions granted")
                startTranscriptionService()
            } else {
                updateStatus("❌ Permissions denied")
                updateDebug("⚠️ Need microphone permission")
            }
        }
    }

    private fun startTranscriptionService() {
        updateStatus("🚀 Starting service...")
        updateDebug("🔧 Starting foreground service...")
        
        val intent = Intent(this, TranscriptionService::class.java)
        
        // Start as foreground service
        ContextCompat.startForegroundService(this, intent)
        
        // Bind to it for callbacks
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun updateStatus(status: String) {
        statusText.text = status
        Log.d(TAG, "Status: $status")
    }

    private fun updateDebug(message: String) {
        val timestamp = dateFormat.format(Date())
        val currentText = debugText.text.toString()
        val lines = currentText.split("\n").takeLast(20)
        val newText = (lines + "[$timestamp] $message").joinToString("\n")
        debugText.text = newText
        Log.d(TAG, "Debug: $message")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        // Note: Service keeps running! Only stop if user explicitly wants to
    }
}
