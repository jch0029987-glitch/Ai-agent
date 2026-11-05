package com.jeremy.ai.backend

import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

object TermuxPluginBridge {

    fun executeInTermux(context: Context, command: String): String {
        try {
            // Define a shared output file where Termux will write the result
            val outputFile = File(context.filesDir, "termux_output.txt")
            val wrappedCommand = "$command > ${outputFile.absolutePath} 2>&1"

            // Target Termux's official RunCommandService
            val intent = Intent().apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                action = "com.termux.RUN_COMMAND"
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/sh")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", wrappedCommand))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            }

            context.startForegroundService(intent)
            
            // Brief pause for Termux execution and file write
            Thread.sleep(400)

            return if (outputFile.exists()) {
                outputFile.readText().take(4000).ifBlank { "Command executed in Termux with no output." }
            } else {
                "Command dispatched to Termux successfully."
            }
        } catch (e: Exception) {
            return "Termux plugin error: ${e.localizedMessage}"
        }
    }
}
