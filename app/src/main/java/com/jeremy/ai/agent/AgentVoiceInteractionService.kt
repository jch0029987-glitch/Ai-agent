package com.jeremy.ai.agent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

class AgentVoiceInteractionService : VoiceInteractionService() {
    // Manages background state for system voice interaction
}

class AgentVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return AgentVoiceInteractionSession(this)
    }
}

class AgentVoiceInteractionSession(private val context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        hide() // Hide default empty session frame
        
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("TRIGGERED_BY_ASSISTANT", true)
        }
        context.startActivity(intent)
    }
}
