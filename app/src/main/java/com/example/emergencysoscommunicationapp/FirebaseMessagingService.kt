package com.example.emergencysoscommunicationapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class FirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        const val ACTIVE_SOS_NOTIFICATION_ID = 1001
        private const val PREFS_PROCESSED_SESSIONS = "PROCESSED_SOS_SESSIONS"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("SOS_FIREBASE", "New FCM token generated: ${token.take(10)}...")
        FcmTokenManager.registerDeviceToken(this, userId = "user_1", role = "victim")
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val sosSessionId = message.data["sosSessionId"] ?: message.data["sessionId"] ?: ""
        val senderUserId = message.data["senderUserId"] ?: "user_1"
        val role = message.data["role"] ?: "guardian"

        Log.d("SOS_NOTIFICATION", "senderUserId=$senderUserId")
        Log.d("SOS_NOTIFICATION", "sosSessionId=$sosSessionId")

        // Exclude victim device: Do not notify the device that activated the SOS
        if (role.equals("victim", ignoreCase = true) || isCurrentDeviceSender(senderUserId)) {
            Log.d("SOS_NAVIGATION", "Ignored notification on sender device for user $senderUserId")
            return
        }

        // Deduplication check: Ignore if this session notification was already processed
        if (sosSessionId.isNotBlank() && isSessionAlreadyProcessed(sosSessionId)) {
            Log.d("SOS_NAVIGATION", "Duplicate FCM notification ignored for session $sosSessionId")
            return
        }

        if (sosSessionId.isNotBlank()) {
            markSessionProcessed(sosSessionId)
        }

        val title = message.notification?.title ?: message.data["title"] ?: "🚨 Emergency SOS Alert"
        val body = message.notification?.body ?: message.data["body"] ?: "Emergency SOS active. Tap to view live location."

        showSOSNotification(title, body, senderUserId, sosSessionId)
    }

    private fun isCurrentDeviceSender(senderUserId: String): Boolean {
        val prefs = getSharedPreferences("SOS_STATE_PREFS", Context.MODE_PRIVATE)
        return prefs.getBoolean("is_sos_active", false)
    }

    private fun isSessionAlreadyProcessed(sessionId: String): Boolean {
        val prefs = getSharedPreferences(PREFS_PROCESSED_SESSIONS, Context.MODE_PRIVATE)
        return prefs.contains(sessionId)
    }

    private fun markSessionProcessed(sessionId: String) {
        val prefs = getSharedPreferences(PREFS_PROCESSED_SESSIONS, Context.MODE_PRIVATE)
        prefs.edit().putLong(sessionId, System.currentTimeMillis()).apply()
    }

    private fun showSOSNotification(title: String, body: String, senderUserId: String, sosSessionId: String) {
        val channelId = "sos_alert_channel"

        val receiverIntent = Intent(this, ReceiverMapActivity::class.java).apply {
            action = "com.example.emergencysoscommunicationapp.OPEN_LIVE_TRACKING"
            data = Uri.parse(
                "emergencysos://livetrack" +
                "?senderUserId=${Uri.encode(senderUserId)}" +
                "&sosSessionId=${Uri.encode(sosSessionId)}"
            )
            putExtra("senderUserId", senderUserId)
            putExtra("sosSessionId", sosSessionId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val requestCode = if (sosSessionId.isNotBlank()) sosSessionId.hashCode() else ACTIVE_SOS_NOTIFICATION_ID

        val pendingIntent = PendingIntent.getActivity(
            this,
            requestCode,
            receiverIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundUri = Uri.parse("android.resource://$packageName/${R.raw.alert}")
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "SOS Guardian Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High Priority Emergency SOS Notifications for Guardians"
                setSound(
                    soundUri,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 500, 500)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(ACTIVE_SOS_NOTIFICATION_ID, notification)
        Log.d("SOS_NAVIGATION", "Posted guardian notification targeting ReceiverMapActivity with data=${receiverIntent.data}")
    }
}
