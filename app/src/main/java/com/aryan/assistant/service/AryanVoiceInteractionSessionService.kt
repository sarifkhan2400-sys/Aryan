package com.aryan.assistant.service

import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

class AryanVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: android.os.Bundle?): VoiceInteractionSession {
        return AryanVoiceSession(this)
    }
}
