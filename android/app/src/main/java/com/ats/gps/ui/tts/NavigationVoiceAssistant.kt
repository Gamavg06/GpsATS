package com.ats.gps.ui.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import com.ats.gps.ui.navigation.ManeuverType
import java.util.Locale

class NavigationVoiceAssistant(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isInitialized = false
    private var lastAnnouncedDistance = Int.MAX_VALUE
    private var lastRawDistance = Float.MAX_VALUE
    private var lastManeuverName = ""
    private var lastSpeakTimeMs = 0L

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

    fun updateNavigation(
        distanceMeters: Float,
        nextManeuver: String,
        maneuverType: ManeuverType? = null
    ) {
        if (!isInitialized || !distanceMeters.isFinite() || distanceMeters <= 0f) return
        if (maneuverType == ManeuverType.STRAIGHT ||
            nextManeuver.equals("Continúa recto", ignoreCase = true) ||
            nextManeuver.equals("Camino Oculto / Atajo", ignoreCase = true)
        ) {
            return
        }

        if (nextManeuver != lastManeuverName || distanceMeters > lastRawDistance + 80f) {
            lastManeuverName = nextManeuver
            lastAnnouncedDistance = Int.MAX_VALUE
        }
        lastRawDistance = distanceMeters

        val dist = distanceMeters.toInt()

        // Hitos de voz adaptables para velocidades de carretera: 1000m, 500m, 200m, 50m
        val milestone = when {
            dist in 900..1100 -> 1000
            dist in 450..580 -> 500
            dist in 160..250 -> 200
            dist in 20..80 -> 50
            else -> null
        }

        if (milestone != null && milestone < lastAnnouncedDistance) {
            val now = System.currentTimeMillis()
            if (now - lastSpeakTimeMs >= 3500L) {
                lastAnnouncedDistance = milestone
                lastSpeakTimeMs = now
                val text = when (milestone) {
                    1000 -> "En un kilómetro, $nextManeuver"
                    500 -> "En 500 metros, $nextManeuver"
                    200 -> "En 200 metros, $nextManeuver"
                    50 -> "Atención, $nextManeuver"
                    else -> nextManeuver
                }
                speak(text, TextToSpeech.QUEUE_FLUSH)
            }
        }
    }

    fun announceArrival(destinationName: String) {
        speak("Has llegado a tu destino en $destinationName", TextToSpeech.QUEUE_ADD)
    }

    fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH) {
        if (isInitialized) {
            tts?.speak(text, queueMode, null, "NavGuidance")
        }
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    fun shutdown() {
        isInitialized = false
        lastAnnouncedDistance = Int.MAX_VALUE
        lastRawDistance = Float.MAX_VALUE
        lastManeuverName = ""
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
