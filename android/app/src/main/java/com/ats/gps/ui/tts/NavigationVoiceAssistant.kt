package com.ats.gps.ui.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class NavigationVoiceAssistant(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var isInitialized = false
    private var lastAnnouncedDistance = -1
    private var lastManeuverName = ""

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val locales = listOf(
                Locale.forLanguageTag("es-ES"),
                Locale.forLanguageTag("es"),
                Locale.getDefault()
            )
            for (locale in locales) {
                val result = tts?.setLanguage(locale)
                if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                    isInitialized = true
                    break
                }
            }
        }
    }

    fun updateNavigation(distanceMeters: Float, nextManeuver: String) {
        if (!isInitialized || distanceMeters <= 0f) return

        if (nextManeuver != lastManeuverName) {
            lastManeuverName = nextManeuver
            lastAnnouncedDistance = -1
        }

        val dist = distanceMeters.toInt()

        // Hitos de voz adaptables para velocidades de carretera: 1000m, 500m, 200m, 50m
        val milestone = when {
            dist in 900..1100 -> 1000
            dist in 450..580 -> 500
            dist in 160..250 -> 200
            dist in 20..80 -> 50
            else -> null
        }

        if (milestone != null && milestone != lastAnnouncedDistance) {
            lastAnnouncedDistance = milestone
            val text = when (milestone) {
                1000 -> "En un kilometro, $nextManeuver"
                500 -> "En 500 metros, $nextManeuver"
                200 -> "En 200 metros, $nextManeuver"
                50 -> "Atencion, $nextManeuver"
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
