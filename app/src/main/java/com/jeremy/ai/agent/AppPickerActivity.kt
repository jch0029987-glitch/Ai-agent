package com.jeremy.ai.agent

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle

class AppPickerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = pm.queryIntentActivities(intent, 0).sortedBy { 
            it.loadLabel(pm).toString().lowercase() 
        }

        val appLabels = resolveInfos.map { it.loadLabel(pm).toString() }.toTypedArray()
        val appPackages = resolveInfos.map { id -> id.activityInfo.packageName }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select App to Open")
            .setItems(appLabels) { _, which ->
                val selectedPkg = appPackages[which]
                try {
                    val launchIntent = pm.getLaunchIntentForPackage(selectedPkg)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launchIntent)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                finish()
            }
            .setOnCancelListener {
                finish()
            }
            .show()
    }
}
