package com.example.emergencysoscommunicationapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val PREFS_SMS_DEDUPE = "SMS_DEDUPE_PREFS"
        private const val DEDUPE_WINDOW_MS = 10000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (sms in messages) {
                val messageBody = sms.displayMessageBody ?: continue

                if (messageBody.contains("SOS", ignoreCase = true)) {
                    Log.d("SOS_NAVIGATION", "Incoming SMS SOS alert detected: $messageBody")

                    try {
                        abortBroadcast()
                    } catch (e: Exception) {
                        Log.w("SOS_NAVIGATION", "Cannot abort broadcast: ${e.message}")
                    }

                    val prefs = context.getSharedPreferences(PREFS_SMS_DEDUPE, Context.MODE_PRIVATE)
                    val lastTime = prefs.getLong("last_sms_time", 0L)
                    val currentTime = System.currentTimeMillis()

                    if (currentTime - lastTime < DEDUPE_WINDOW_MS) {
                        Log.d("SOS_NAVIGATION", "Duplicate SMS notification suppressed (time delta: ${currentTime - lastTime}ms)")
                        return
                    }

                    prefs.edit().putLong("last_sms_time", currentTime).apply()

                    val channelId = "sos_alert_channel"
                    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val channelName = "Emergency SOS Alerts"
                        val channel = NotificationChannel(
                            channelId,
                            channelName,
                            NotificationManager.IMPORTANCE_HIGH
                        ).apply {
                            description = "Emergency SOS Alerts"
                            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                            vibrationPattern = longArrayOf(0, 500, 500, 500)
                            enableVibration(true)

                            val soundUri = Uri.parse("android.resource://${context.packageName}/${R.raw.alert}")
                            val audioAttributes = AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                            setSound(soundUri, audioAttributes)
                        }
                        notificationManager.createNotificationChannel(channel)
                    }

                    val receiverIntent = Intent(context, ReceiverMapActivity::class.java).apply {
                        action = "com.example.emergencysoscommunicationapp.OPEN_LIVE_TRACKING"
                        data = Uri.parse("emergencysos://livetrack?senderUserId=user_1")
                        putExtra("senderUserId", "user_1")
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }

                    val pendingIntent = PendingIntent.getActivity(
                        context,
                        FirebaseMessagingService.ACTIVE_SOS_NOTIFICATION_ID,
                        receiverIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )

                    val soundUri = Uri.parse("android.resource://${context.packageName}/${R.raw.alert}")

                    val notificationBuilder = NotificationCompat.Builder(context, channelId)
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setContentTitle("🚨 Emergency SOS Alert Received")
                        .setContentText(messageBody)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(messageBody))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .setVibrate(longArrayOf(0, 500, 500, 500))
                        .setSound(soundUri)
                        .setAutoCancel(true)
                        .setContentIntent(pendingIntent)

                    try {
                        NotificationManagerCompat.from(context).notify(
                            FirebaseMessagingService.ACTIVE_SOS_NOTIFICATION_ID,
                            notificationBuilder.build()
                        )
                        Log.d("SOS_NAVIGATION", "SMS Receiver posted notification targeting ReceiverMapActivity")
                    } catch (e: SecurityException) {
                        Log.e("SOS_NAVIGATION", "Permission missing for POST_NOTIFICATIONS: ${e.message}")
                    }
                }
            }
        }
    }
}
