package com.example.emergencysoscommunicationapp

import org.junit.Test
import org.junit.Assert.*
import java.util.Date

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        val yesterday = 1784678400000L
        val today = System.currentTimeMillis()
        println("Yesterday: " + yesterday.toInt())
        println("Today: " + today.toInt())
        assertEquals(4, 2 + 2)
    }
}