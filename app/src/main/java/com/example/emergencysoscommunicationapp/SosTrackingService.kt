package com.example.emergencysoscommunicationapp

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground Service for robust, unkillable background SOS location tracking.
 * Complies with Android 10+ and Android 14+ (API 34/35) FOREGROUND_SERVICE_LOCATION requirements.
 * Hosts continuous FusedLocationProviderClient updates, updates Firebase in real-time,
 * persists unsynced coordinates into an offline queue during connectivity failures,
 * dispatches throttled offline fallback SMS when internet is unavailable,
 * monitors battery percentage only while SOS is active, alerts contacts on low battery,
 * and maintains an ongoing notification with a one-tap "STOP SOS" action.
 */
class SosTrackingService : Service() {

    companion object {
        const val TAG = "SosTrackingService"
        const val ACTION_START_SOS = "com.example.emergencysoscommunicationapp.ACTION_START_SOS"
        const val ACTION_STOP_SOS = "com.example.emergencysoscommunicationapp.ACTION_STOP_SOS"
        const val ACTION_START_RECORDING = "com.example.emergencysoscommunicationapp.ACTION_START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.example.emergencysoscommunicationapp.ACTION_STOP_RECORDING"
        const val CHANNEL_ID = "sos_foreground_service_channel"
        const val NOTIFICATION_ID = 9999

        @Volatile
        var isServiceRunning: Boolean = false
            private set
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var isLocationCallbackRegistered = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var recordingReceiver: BroadcastReceiver? = null
    private var lastKnownBatteryLevel: Int? = null
    private var lastLatitude: Double = 0.0
    private var lastLongitude: Double = 0.0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SosTrackingService.onCreate()")
        createNotificationChannel()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Register network monitoring callback to auto-sync pending offline locations on reconnect
        networkCallback = NetworkMonitor.registerNetworkCallback(
            context = this,
            onAvailable = {
                Log.d(TAG, "Network connection restored. Syncing pending offline queue and pending audio upload if available.")
                if (OfflineLocationQueue.hasQueuedLocations(this@SosTrackingService)) {
                    OfflineLocationQueue.syncQueueToFirebase(this@SosTrackingService)
                }
                AudioDeliveryManager.retryPendingUpload(this@SosTrackingService)
            },
            onLost = {
                Log.w(TAG, "Network connection lost during active SOS tracking.")
            }
        )

        // Register dynamic battery monitoring receiver for the active SOS session
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level >= 0 && scale > 0) {
                        val pct = (level * 100 / scale.toFloat()).toInt()
                        lastKnownBatteryLevel = pct
                        val sessionId = SosStateManager.getActiveSessionId(this@SosTrackingService)
                        val userId = SosStateManager.getUserId(this@SosTrackingService)

                        // 1. Evaluate low-battery alert (dispatches SMS once per session if pct <= threshold)
                        BatteryAlertManager.evaluateAndSendAlert(this@SosTrackingService, pct, sessionId)

                        // 2. Publish battery level to Firebase for guardian visibility
                        BatteryAlertManager.publishBatteryToFirebase(userId, pct)
                    }
                }
            }
        }
        try {
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            Log.d(TAG, "Battery monitoring receiver registered for active SOS session")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register battery receiver: ${e.message}")
        }

        // Register receiver for emergency audio recording state changes
        recordingReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AudioRecordingManager.ACTION_RECORDING_STATE_CHANGED) {
                    Log.d(TAG, "Recording state changed received in service: isRecording=${AudioRecordingManager.isRecording}")
                    updateNotificationContent(lastLatitude, lastLongitude, isOffline = !NetworkMonitor.isInternetAvailable(this@SosTrackingService))
                    if (!AudioRecordingManager.isRecording && !SosStateManager.isSosActive(this@SosTrackingService)) {
                        stopTrackingAndFinish()
                    }
                }
            }
        }
        val recordingFilter = IntentFilter(AudioRecordingManager.ACTION_RECORDING_STATE_CHANGED)
        ContextCompat.registerReceiver(this, recordingReceiver, recordingFilter, ContextCompat.RECEIVER_NOT_EXPORTED)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation ?: return
                lastLatitude = location.latitude
                lastLongitude = location.longitude
                Log.d(TAG, "Background SOS location update: lat=${location.latitude}, lng=${location.longitude}")
                // Cache location for offline fallback usage
                OfflineSosManager.cacheLocation(this@SosTrackingService, location.latitude, location.longitude)
                handleLocationUpdate(location.latitude, location.longitude)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_SOS
        Log.d(TAG, "onStartCommand: action=$action, isRunning=$isServiceRunning")

        if (action == ACTION_STOP_SOS) {
            stopTrackingAndFinish()
            return START_NOT_STICKY
        }

        if (action == ACTION_STOP_RECORDING) {
            Log.d(TAG, "ACTION_STOP_RECORDING received in SosTrackingService")
            AudioRecordingManager.stopRecording(this)
            updateNotificationContent(lastLatitude, lastLongitude, isOffline = !NetworkMonitor.isInternetAvailable(this))
            if (!SosStateManager.isSosActive(this)) {
                stopTrackingAndFinish()
                return START_NOT_STICKY
            }
            return START_STICKY
        }

        val isStartingRecordingOnly = action == ACTION_START_RECORDING && !SosStateManager.isSosActive(this)

        if (isServiceRunning) {
            Log.d(TAG, "SosTrackingService is already running. Refreshing notification/tracking if needed.")
            if (action == ACTION_START_SOS && !isLocationCallbackRegistered) {
                startContinuousLocationTracking()
            }
            updateNotificationContent(lastLatitude, lastLongitude, isOffline = !NetworkMonitor.isInternetAvailable(this))
            return START_STICKY
        }

        isServiceRunning = true

        val initialNotification = buildForegroundNotification(
            title = if (isStartingRecordingOnly) "🔴 Emergency Audio Recording" else "🚨 Emergency SOS Active",
            body = if (isStartingRecordingOnly) "🎙️ Audio recording in progress. App-private storage." else "Broadcasting live location to guardians..."
        )

        updateForegroundServiceType(initialNotification)

        if (!isStartingRecordingOnly) {
            // Query initial battery immediately to catch already-low battery or app restarts
            val currentBattery = BatteryAlertManager.getBatteryLevel(this)
            if (currentBattery != null) {
                lastKnownBatteryLevel = currentBattery
                val sessionId = SosStateManager.getActiveSessionId(this)
                val userId = SosStateManager.getUserId(this)
                BatteryAlertManager.evaluateAndSendAlert(this, currentBattery, sessionId)
                BatteryAlertManager.publishBatteryToFirebase(userId, currentBattery)
            }

            startContinuousLocationTracking()
        }

        return START_STICKY
    }

    private fun updateForegroundServiceType(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var serviceType = 0
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (SosStateManager.isSosActive(this)) {
                        serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    }
                    if (AudioRecordingManager.isRecording) {
                        serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    }
                    if (serviceType == 0) {
                        serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    }
                }
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set/update foreground service: ${e.message}", e)
        }
    }

    private fun startContinuousLocationTracking() {
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            Log.e(TAG, "Cannot start tracking: Location permissions missing")
            stopTrackingAndFinish()
            return
        }

        if (isLocationCallbackRegistered) return

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            5000L
        )
            .setMinUpdateIntervalMillis(3000L)
            .setMinUpdateDistanceMeters(5.0f)
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            isLocationCallbackRegistered = true
            Log.d(TAG, "FusedLocationProviderClient tracking active in foreground service.")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException requesting location updates: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Exception requesting location updates: ${e.message}")
        }
    }

    /**
     * Processes location coordinates.
     * When online, uploads directly to Firebase (including battery info) and flushes any pending queue.
     * When offline or upon Firebase delivery failure, persists in local queue and triggers throttled fallback SMS.
     */
    private fun handleLocationUpdate(latitude: Double, longitude: Double) {
        val userId = SosStateManager.getUserId(this)
        val sessionId = SosStateManager.getActiveSessionId(this)
        val timestamp = System.currentTimeMillis()

        if (!NetworkMonitor.isInternetAvailable(this)) {
            Log.w(TAG, "Device is offline. Enqueueing location and checking offline fallback SMS.")
            OfflineLocationQueue.enqueueLocation(
                this,
                OfflineLocationQueue.QueuedLocation(
                    latitude = latitude,
                    longitude = longitude,
                    timestamp = timestamp,
                    status = "SOS_ACTIVE",
                    sessionId = sessionId,
                    senderUserId = userId,
                    batteryLevel = lastKnownBatteryLevel
                )
            )
            updateNotificationContent(latitude, longitude, isOffline = true)
            OfflineSosManager.sendOfflineFallbackSms(this, sessionId)
            return
        }

        val locationData = mutableMapOf<String, Any>(
            "latitude" to latitude,
            "longitude" to longitude,
            "time" to timestamp,
            "timestamp" to timestamp,
            "status" to "SOS_ACTIVE",
            "sessionId" to sessionId,
            "senderUserId" to userId
        )
        if (lastKnownBatteryLevel != null) {
            locationData["batteryLevel"] = lastKnownBatteryLevel!!
            locationData["batteryTimestamp"] = timestamp
        }

        try {
            FirebaseDatabase.getInstance().reference
                .child("sos_locations")
                .child(userId)
                .setValue(locationData)
                .addOnSuccessListener {
                    Log.d(TAG, "Foreground location published to Firebase: lat=$latitude, lng=$longitude")
                    updateNotificationContent(latitude, longitude, isOffline = false)
                    // Sync any older queued records if they exist
                    if (OfflineLocationQueue.hasQueuedLocations(this@SosTrackingService)) {
                        OfflineLocationQueue.syncQueueToFirebase(this@SosTrackingService)
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Failed to publish location to Firebase: ${e.message}. Enqueueing locally.")
                    OfflineLocationQueue.enqueueLocation(
                        this@SosTrackingService,
                        OfflineLocationQueue.QueuedLocation(
                            latitude = latitude,
                            longitude = longitude,
                            timestamp = timestamp,
                            status = "SOS_ACTIVE",
                            sessionId = sessionId,
                            senderUserId = userId,
                            batteryLevel = lastKnownBatteryLevel
                        )
                    )
                    updateNotificationContent(latitude, longitude, isOffline = true)
                    OfflineSosManager.sendOfflineFallbackSms(this@SosTrackingService, sessionId)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Exception publishing to Firebase: ${e.message}")
            OfflineLocationQueue.enqueueLocation(
                this,
                OfflineLocationQueue.QueuedLocation(
                    latitude = latitude,
                    longitude = longitude,
                    timestamp = timestamp,
                    status = "SOS_ACTIVE",
                    sessionId = sessionId,
                    senderUserId = userId,
                    batteryLevel = lastKnownBatteryLevel
                )
            )
            updateNotificationContent(latitude, longitude, isOffline = true)
            OfflineSosManager.sendOfflineFallbackSms(this, sessionId)
        }
    }

    private fun updateNotificationContent(latitude: Double = lastLatitude, longitude: Double = lastLongitude, isOffline: Boolean = false) {
        val isSos = SosStateManager.isSosActive(this)
        val isRec = AudioRecordingManager.isRecording

        val title = when {
            isSos && isRec -> "🚨 SOS Active • 🔴 Recording Audio"
            isSos && isOffline -> "🚨 Emergency SOS (Offline)"
            isSos -> "🚨 Emergency SOS Active"
            isRec -> "🔴 Emergency Audio Recording"
            else -> "Emergency SOS Active"
        }

        val sdf = SimpleDateFormat("hh:mm:ss a", Locale.getDefault())
        val timeStr = sdf.format(Date())
        val batteryStr = if (lastKnownBatteryLevel != null) " | 🔋 $lastKnownBatteryLevel%" else ""
        val bodyBuilder = StringBuilder()

        if (isSos) {
            if (isOffline) {
                bodyBuilder.append("⚠️ Offline (GPS Queued): %.4f, %.4f | %s%s".format(latitude, longitude, timeStr, batteryStr))
            } else if (latitude != 0.0 || longitude != 0.0) {
                bodyBuilder.append("Live GPS: %.4f, %.4f | %s%s".format(latitude, longitude, timeStr, batteryStr))
            } else {
                bodyBuilder.append("Broadcasting live location to guardians...%s".format(batteryStr))
            }
        }

        if (isRec) {
            if (bodyBuilder.isNotEmpty()) bodyBuilder.append("\n")
            bodyBuilder.append("🎙️ Audio recording active. Saved to private storage.")
        }

        val body = if (bodyBuilder.isEmpty()) "Emergency SOS Service Active" else bodyBuilder.toString()

        val updatedNotification = buildForegroundNotification(title = title, body = body)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, updatedNotification)

        updateForegroundServiceType(updatedNotification)
    }

    private fun buildForegroundNotification(title: String, body: String): Notification {
        val openIntent = Intent(this, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopSosIntent = Intent(this, SosTrackingService::class.java).apply {
            action = ACTION_STOP_SOS
        }
        val stopSosPendingIntent = PendingIntent.getService(
            this,
            1,
            stopSosIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openPendingIntent)

        if (AudioRecordingManager.isRecording) {
            val stopRecIntent = Intent(this, SosTrackingService::class.java).apply {
                action = ACTION_STOP_RECORDING
            }
            val stopRecPendingIntent = PendingIntent.getService(
                this,
                2,
                stopRecIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(R.drawable.ic_stop, "STOP RECORDING", stopRecPendingIntent)
        }

        if (SosStateManager.isSosActive(this)) {
            builder.addAction(R.drawable.ic_status_dot, "STOP SOS", stopSosPendingIntent)
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Emergency SOS Active Tracking",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Ongoing notification displayed while live emergency SOS tracking is active"
                enableVibration(true)
                setShowBadge(true)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun stopTrackingAndFinish() {
        Log.d(TAG, "stopTrackingAndFinish() requested.")

        // Finalize emergency audio recording if active
        if (AudioRecordingManager.isRecording) {
            Log.d(TAG, "SOS stopped: stopping active audio recording...")
            val savedFile = AudioRecordingManager.stopRecording(this)
            if (savedFile != null) {
                Log.i(TAG, "Emergency recording finalized at: ${savedFile.absolutePath}")
            }
        }

        if (recordingReceiver != null) {
            try {
                unregisterReceiver(recordingReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering recordingReceiver: ${e.message}")
            }
            recordingReceiver = null
        }

        NetworkMonitor.unregisterNetworkCallback(this, networkCallback)
        networkCallback = null

        // Unregister battery receiver and reset alert state when SOS stops
        if (batteryReceiver != null) {
            try {
                unregisterReceiver(batteryReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering battery receiver: ${e.message}")
            }
            batteryReceiver = null
        }
        BatteryAlertManager.resetAlertState(this)

        OfflineSosManager.resetFallbackState(this)

        if (isLocationCallbackRegistered) {
            try {
                fusedLocationClient.removeLocationUpdates(locationCallback)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing location updates: ${e.message}")
            }
            isLocationCallbackRegistered = false
        }

        isServiceRunning = false

        val userId = SosStateManager.getUserId(this)
        SosStateManager.setSosInactive(this)

        // If offline when stopping, queue SOS_STOPPED to prevent old queue from overwriting or reactivating
        if (!NetworkMonitor.isInternetAvailable(this)) {
            val cached = OfflineSosManager.getLastCachedLocation(this)
            OfflineLocationQueue.enqueueLocation(
                this,
                OfflineLocationQueue.QueuedLocation(
                    latitude = cached?.latitude ?: 0.0,
                    longitude = cached?.longitude ?: 0.0,
                    timestamp = System.currentTimeMillis(),
                    status = "SOS_STOPPED",
                    sessionId = "",
                    senderUserId = userId,
                    batteryLevel = lastKnownBatteryLevel
                )
            )
            Log.d(TAG, "Enqueued SOS_STOPPED status while offline for user $userId")
        } else {
            SosStateManager.updateFirebaseStatus("SOS_STOPPED", userId)
        }

        // Broadcast to running activities (HomeActivity, LocationActivity) to update UI immediately
        val stoppedIntent = Intent(SosStateManager.ACTION_SOS_STOPPED).apply {
            setPackage(packageName)
        }
        sendBroadcast(stoppedIntent)

        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping foreground: ${e.message}")
        }

        stopSelf()
    }

    override fun onDestroy() {
        Log.d(TAG, "SosTrackingService.onDestroy()")
        NetworkMonitor.unregisterNetworkCallback(this, networkCallback)
        networkCallback = null

        if (batteryReceiver != null) {
            try {
                unregisterReceiver(batteryReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering battery receiver in onDestroy: ${e.message}")
            }
            batteryReceiver = null
        }

        if (recordingReceiver != null) {
            try {
                unregisterReceiver(recordingReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering recordingReceiver in onDestroy: ${e.message}")
            }
            recordingReceiver = null
        }

        if (isServiceRunning) {
            stopTrackingAndFinish()
        }
        super.onDestroy()
    }
}
