package com.example.emergencysoscommunicationapp

data class LiveLocation(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracy: Float = 0f,
    val timestamp: Long = 0L,
    val active: Boolean = false
)