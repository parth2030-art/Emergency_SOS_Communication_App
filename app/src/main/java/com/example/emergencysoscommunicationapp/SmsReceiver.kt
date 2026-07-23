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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (sms in messages) {
                val messageBody = sms.displayMessageBody ?: continue

                if (messageBody.contains("SOS", ignoreCase = true)) {
                    // SOS Notification Start
                    val channelId = "SOS_CHANNEL"
                    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val channelName = "Emergency SOS Alerts"
                        val channel = NotificationChannel(
                            channelId,
                            channelName,
                            NotificationManager.IMPORTANCE_HIGH
                        ).apply {
                            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                            vibrationPattern = longArrayOf(0, 500, 500, 500)
                            enableVibration(true)

                            val soundUri = Uri.parse("android.resource://${context.packageName}/raw/alert")
                            val audioAttributes = AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                            setSound(soundUri, audioAttributes)
                        }
                        notificationManager.createNotificationChannel(channel)
                    }

                    val openIntent = Intent(context, HomeActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    val pendingIntent = PendingIntent.getActivity(
                        context,
                        0,
                        openIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )

                    val soundUri = Uri.parse("android.resource://${context.packageName}/raw/alert")

                    val notificationBuilder = NotificationCompat.Builder(context, channelId)
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setContentTitle("🚨 Emergency SOS Alert")
                        .setContentText(messageBody)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(messageBody))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .setVibrate(longArrayOf(0, 500, 500, 500))
                        .setSound(soundUri)
                        .setAutoCancel(true)
                        .setContentIntent(pendingIntent)

                    try {
                        NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), notificationBuilder.build())
                    } catch (e: SecurityException) {
                        // Permission not granted for POST_NOTIFICATIONS
                    }
                    // SOS Notification End
                }
            }
        }
    }
}
