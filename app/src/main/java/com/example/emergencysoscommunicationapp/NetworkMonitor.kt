package com.example.emergencysoscommunicationapp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log

/**
 * Utility to monitor real-time network connectivity using supported Android APIs.
 * Note: Network availability alone is not treated as proof of Firebase write success;
 * Firebase writes are always confirmed via their respective task callbacks.
 */
object NetworkMonitor {

    private const val TAG = "NetworkMonitor"

    /**
     * Checks if active network currently has validated internet capability.
     */
    fun isInternetAvailable(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false

            val activeNetwork = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false

            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking internet connectivity: ${e.message}")
            false
        }
    }

    /**
     * Registers a ConnectivityManager.NetworkCallback to listen for network state transitions.
     * Returns the registered callback instance for unregistration.
     */
    fun registerNetworkCallback(
        context: Context,
        onAvailable: () -> Unit,
        onLost: () -> Unit
    ): ConnectivityManager.NetworkCallback? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    Log.d(TAG, "Network became available")
                    onAvailable()
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    Log.d(TAG, "Network connection lost")
                    onLost()
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    super.onCapabilitiesChanged(network, networkCapabilities)
                    val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    val isValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    if (hasInternet && isValidated) {
                        onAvailable()
                    }
                }
            }

            cm.registerDefaultNetworkCallback(callback)
            Log.d(TAG, "Default network callback registered successfully")
            callback
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback: ${e.message}")
            null
        }
    }

    /**
     * Safely unregisters a previously registered ConnectivityManager.NetworkCallback.
     */
    fun unregisterNetworkCallback(
        context: Context,
        callback: ConnectivityManager.NetworkCallback?
    ) {
        if (callback == null) return
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(callback)
            Log.d(TAG, "Network callback unregistered successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister network callback: ${e.message}")
        }
    }
}
