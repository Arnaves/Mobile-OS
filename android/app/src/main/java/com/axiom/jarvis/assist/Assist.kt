package com.axiom.jarvis.assist

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.axiom.jarvis.MainActivity

/** Lets AXIOM be chosen as the phone's default digital assistant. */
class AxiomVoiceService : VoiceInteractionService()

class AxiomSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = object : VoiceInteractionSession(this) {
        override fun onShow(args: Bundle?, showFlags: Int) {
            super.onShow(args, showFlags)
            startAssistantActivity(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_VOICE, true))
            hide()
        }
    }
}

/** Required by the assistant API; AXIOM uses the phone's own speech recognizer instead. */
class AxiomRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent?, listener: Callback?) { listener?.error(SpeechRecognizer.ERROR_CLIENT) }
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
