package com.ats.gps.ui.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class NavigationVoiceAssistant(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var isInitialized = false
    private var lastAnnouncedDistance = -1

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("es", "ES"))
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                isInitialized = true
            }
        }
    }

    fun updateNavigation(distanceMeters: Float, nextManeuver: String) {
        if (!isInitialized || distanceMeters <= 0f) return

        val dist = distanceMeters.toInt()

        // Hitos de voz: 1000m, 500m, 200m, 50m
        val milestone = when {
            dist in 950..1050 -> 1000
            dist in 470..530 -> 500
            dist in 180..220 -> 200
            dist in 30..60 -> 50
            else -> null
        }

        if (milestone != null && milestone != lastAnnouncedDistance) {
            lastAnnouncedDistance = milestone
            val text = when (milestone) {
                1000 -> "En un kilómetro, $nextManeuver"
                500 -> "En 500 metros, $nextManeuver"
                200 -> "En 200 metros, $nextManeuver"
                50 -> "Gira ahora, $nextManeuver"
                else -> nextManeuver
            }
            speak(text)
        }
    }

    fun announceArrival(destinationName: String) {
        speak("Has llegado a tu destino en $destinationName")
    }

    fun speak(text: String) {
        if (isInitialized) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "NavGuidance")
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
