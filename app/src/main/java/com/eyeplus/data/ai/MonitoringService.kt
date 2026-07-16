package com.eyeplus.data.ai

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.eyeplus.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground service for 24/7 AI-powered camera monitoring.
 *
 * Runs periodic analysis of camera frames using ML Kit (on-device)
 * and Gemini API (cloud) when persons are detected.
 */
class MonitoringService : Service() {

    data class ServiceState(
        val isRunning: Boolean = false,
        val lastAnalysis: FrameAnalysis? = null,
        val eventsToday: Int = 0,
        val checkInterval: Long = DEFAULT_INTERVAL_MS,
        val lastError: String? = null
    )

    private var serviceJob: Job? = null
    private var personDetector: PersonDetector? = null
    private var geminiAnalyzer: GeminiAnalyzer? = null

    // These would be set from outside
    var subStreamUrl: String = ""
    var geminiApiKey: String = ""
    var onFrameCapture: (suspend () -> Bitmap?)? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        personDetector = PersonDetector()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_STOP -> stop()
            ACTION_UPDATE_INTERVAL -> {
                val interval = intent.getLongExtra("interval", DEFAULT_INTERVAL_MS)
                updateInterval(interval)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Start the monitoring loop.
     */
    private fun start() {
        if (state.value.isRunning) return

        geminiAnalyzer = GeminiAnalyzer(apiKey = geminiApiKey)
        state.value = ServiceState(isRunning = true)

        val notification = createNotification("AI dozor aktivní")
        startForeground(NOTIFICATION_ID, notification)

        serviceJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            monitoringLoop()
        }

        Log.d(TAG, "Monitoring service started")
    }

    /**
     * Main monitoring loop - periodically captures frames and analyzes them.
     */
    private suspend fun monitoringLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                val currentState = state.value

                // Capture a frame from the RTSP stream
                val frame = onFrameCapture?.invoke()

                if (frame != null) {
                    // Step 1: On-device person detection (free, fast)
                    val detection = personDetector?.detect(frame) ?: OnDeviceDetection()

                    if (detection.hasPerson) {
                        // Step 2: Person detected - run Gemini analysis
                        val analysis = geminiAnalyzer?.analyzeFrame(frame) ?: FrameAnalysis()

                        state.value = currentState.copy(
                            lastAnalysis = analysis,
                            eventsToday = currentState.eventsToday + 1,
                            checkInterval = PERSON_CHECK_INTERVAL
                        )

                        // Handle alerts
                        if (analysis.alertLevel == "high" || analysis.suspiciousActivity) {
                            sendAlertNotification(analysis)
                        }
                    } else {
                        // No person - back to normal interval
                        state.value = currentState.copy(
                            checkInterval = DEFAULT_INTERVAL_MS
                        )
                    }
                }

                // Wait for next check
                delay(state.value.checkInterval)

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Monitoring loop error: ${e.message}", e)
                state.value = state.value.copy(lastError = e.message)
                delay(10_000L) // Wait 10s before retrying
            }
        }
    }

    /**
     * Stop the monitoring service.
     */
    private fun stop() {
        serviceJob?.cancel()
        serviceJob = null
        personDetector?.close()
        state.value = ServiceState()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()

        Log.d(TAG, "Monitoring service stopped")
    }

    /**
     * Update the check interval.
     */
    private fun updateInterval(interval: Long) {
        state.value = state.value.copy(checkInterval = interval)
    }

    /**
     * Send an alert notification for suspicious activity.
     */
    private fun sendAlertNotification(analysis: FrameAnalysis) {
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("EyePlus AI - Upozornění")
            .setContentText(analysis.description.take(100))
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Počet osob: ${analysis.personCount}\n" +
                    analysis.description + "\n" +
                    "Úroveň: ${analysis.alertLevel}"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(
                System.currentTimeMillis().toInt(),
                notification
            )
        } catch (e: Exception) {
            Log.e(TAG, "Notification error: ${e.message}", e)
        }
    }

    /**
     * Create the notification channel for foreground service and alerts.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "EyePlus AI dozor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Nepřetržitý AI dozor kamery"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Create the persistent notification for the foreground service.
     */
    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("EyePlus AI")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MonitoringService"
        private const val NOTIFICATION_CHANNEL_ID = "eyeplus_monitoring"
        private const val NOTIFICATION_ID = 1001
        const val DEFAULT_INTERVAL_MS = 30_000L // 30s between checks
        const val PERSON_CHECK_INTERVAL = 5_000L // 5s when person detected

        val state = MutableStateFlow(ServiceState())

        const val ACTION_START = "com.eyeplus.action.START_MONITORING"
        const val ACTION_STOP = "com.eyeplus.action.STOP_MONITORING"
        const val ACTION_UPDATE_INTERVAL = "com.eyeplus.action.UPDATE_INTERVAL"

        fun startIntent(context: Context): Intent {
            return Intent(context, MonitoringService::class.java).apply {
                action = ACTION_START
            }
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, MonitoringService::class.java).apply {
                action = ACTION_STOP
            }
        }
    }
}
