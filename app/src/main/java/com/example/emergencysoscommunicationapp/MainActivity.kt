package com.example.emergencysoscommunicationapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val splashHandler = Handler(Looper.getMainLooper())

    private val openHomeRunnable = Runnable {
        if (
            !isFinishing &&
            !isDestroyed &&
            !isNotificationOrDeepLinkLaunch &&
            hasWindowFocus()
        ) {
            Log.d(
                "SOS_NAVIGATION",
                "Splash completed normally. Opening HomeActivity."
            )

            startActivity(
                Intent(this, HomeActivity::class.java)
            )

            finish()
        } else {
            Log.d(
                "SOS_NAVIGATION",
                "HomeActivity launch cancelled because MainActivity is not active."
            )
        }
    }

    private var isNotificationOrDeepLinkLaunch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(
            "SOS_NAVIGATION",
            "MainActivity.onCreate: intent=$intent, data=${intent.data}"
        )

        setContentView(R.layout.activity_main)

        requestNotificationPermission()

        isNotificationOrDeepLinkLaunch =
            isLiveTrackingIntent(intent)

        if (isNotificationOrDeepLinkLaunch) {
            Log.d(
                "SOS_NAVIGATION",
                "MainActivity received live-tracking intent. Opening ReceiverMapActivity."
            )

            openReceiverMap(intent)
            return
        }

        splashHandler.postDelayed(
            openHomeRunnable,
            3000L
        )
    }

    private fun isLiveTrackingIntent(intent: Intent?): Boolean {
        if (intent == null) return false

        val uri = intent.data

        val isDeepLink =
            uri?.scheme == "emergencysos" &&
                    uri.host == "livetrack"

        val isNotificationAction =
            intent.action ==
                    "com.example.emergencysoscommunicationapp.OPEN_LIVE_TRACKING"

        return isDeepLink || isNotificationAction
    }

    private fun openReceiverMap(sourceIntent: Intent) {
        // Cancel the splash timer before opening the tracking screen.
        splashHandler.removeCallbacks(openHomeRunnable)

        val receiverIntent =
            Intent(this, ReceiverMapActivity::class.java).apply {

                action =
                    "com.example.emergencysoscommunicationapp.OPEN_LIVE_TRACKING"

                data = sourceIntent.data

                sourceIntent.getStringExtra("senderUserId")
                    ?.let {
                        putExtra("senderUserId", it)
                    }

                sourceIntent.getStringExtra("sosSessionId")
                    ?.let {
                        putExtra("sosSessionId", it)
                    }

                flags =
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

        startActivity(receiverIntent)
        finish()
    }

    private fun requestNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.POST_NOTIFICATIONS
                ),
                101
            )
        }
    }

    override fun onPause() {
        super.onPause()

        /*
         * Very important:
         * If MainActivity is covered by ReceiverMapActivity,
         * cancel the pending HomeActivity launch.
         */
        splashHandler.removeCallbacks(openHomeRunnable)

        Log.d(
            "SOS_NAVIGATION",
            "MainActivity.onPause: splash callback removed"
        )
    }

    override fun onDestroy() {
        splashHandler.removeCallbacksAndMessages(null)

        Log.d(
            "SOS_NAVIGATION",
            "MainActivity.onDestroy: all callbacks removed"
        )

        super.onDestroy()
    }
}