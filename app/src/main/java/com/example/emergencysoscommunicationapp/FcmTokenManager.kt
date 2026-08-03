package com.example.emergencysoscommunicationapp

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessaging

object FcmTokenManager {

    fun getDeviceId(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "device_${System.currentTimeMillis()}"
    }

    fun registerDeviceToken(context: Context, userId: String = "user_1", role: String = "victim") {
        val deviceId = getDeviceId(context)

        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                Log.d("SOS_FIREBASE", "FCM Token retrieved for device $deviceId: ${token.take(10)}...")

                val database = FirebaseDatabase.getInstance().reference

                val deviceData = mapOf(
                    "token" to token,
                    "role" to role,
                    "deviceId" to deviceId,
                    "userId" to userId,
                    "lastUpdated" to System.currentTimeMillis()
                )

                // Store under multi-device structure: users/{userId}/devices/{deviceId}
                database.child("users")
                    .child(userId)
                    .child("devices")
                    .child(deviceId)
                    .setValue(deviceData)
                    .addOnSuccessListener {
                        Log.d("SOS_FIREBASE", "Multi-device FCM token registered under users/$userId/devices/$deviceId")
                    }
                    .addOnFailureListener { e ->
                        Log.e("SOS_FIREBASE", "Failed to register FCM token: ${e.message}")
                    }
            }
            .addOnFailureListener { e ->
                Log.e("SOS_FIREBASE", "Failed to retrieve FCM token: ${e.message}")
            }
    }

    fun linkGuardianToken(context: Context, victimUserId: String = "user_1", guardianUserId: String = "guardian_1") {
        val deviceId = getDeviceId(context)

        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            val database = FirebaseDatabase.getInstance().reference

            val guardianData = mapOf(
                "guardianUserId" to guardianUserId,
                "deviceId" to deviceId,
                "token" to token,
                "linkedAt" to System.currentTimeMillis()
            )

            database.child("guardians")
                .child(victimUserId)
                .child(guardianUserId)
                .setValue(guardianData)
                .addOnSuccessListener {
                    Log.d("SOS_FIREBASE", "Guardian token linked under guardians/$victimUserId/$guardianUserId")
                }
        }
    }
}
