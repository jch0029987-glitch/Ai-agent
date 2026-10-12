package com.jeremy.ai.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.jeremy.ai.agent.services.AgentNotificationListener

object DeviceTools {

    fun openBrowser(context: Context, url: String): String {
        val formattedUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
            "https://$url"
        } else {
            url
        }

        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(formattedUrl)).apply {
                setPackage("com.android.chrome")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Successfully opened Chrome to $formattedUrl"
        } catch (e: Exception) {
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(formattedUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
                "Opened default browser to $formattedUrl (Chrome direct target unavailable)."
            } catch (fallbackEx: Exception) {
                "Failed to open browser: ${fallbackEx.localizedMessage}"
            }
        }
    }

    fun clickScreenText(text: String): String {
        val service = AgentAccessibilityService.instance ?: return "Error: Accessibility Service not active."
        val success = service.clickByText(text)
        return if (success) "Successfully clicked on '$text'" else "Failed to find clickable element with text '$text'"
    }

    fun typeText(text: String): String {
        val service = AgentAccessibilityService.instance ?: return "Error: Accessibility Service not active."
        val success = service.typeText(text)
        return if (success) "Successfully typed text into focused field." else "Failed to find an editable text field."
    }

    fun getScreenContent(): String {
        val service = AgentAccessibilityService.instance ?: return "Error: Accessibility Service not active."
        return service.getScreenContentSummary()
    }

    fun getRecentNotifications(): String {
        val summary = AgentNotificationListener.getContextSummary()
        return if (summary.isNotBlank()) summary else "No recent notifications captured."
    }

    fun openApp(context: Context, query: String): String {
        val pm = context.packageManager
        val cleanQuery = query.trim().replace("\"", "").replace("/", "").lowercase()

        val genericKeywords = listOf("app", "apps", "list", "menu", "open", "choose", "select")
        if (cleanQuery.isBlank() || cleanQuery in genericKeywords) {
            return openAppPicker(context)
        }

        val targetPackage = if (!cleanQuery.contains(".")) {
            resolveExactOrClosePackage(context, cleanQuery) ?: run {
                return openAppPicker(context)
            }
        } else {
            cleanQuery
        }

        val service = AgentAccessibilityService.instance
        if (service?.openApp(targetPackage) == true) {
            return "Opened application $targetPackage"
        }

        return try {
            val intent = pm.getLaunchIntentForPackage(targetPackage)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "Opened application $targetPackage"
            } else {
                openAppPicker(context)
            }
        } catch (e: Exception) {
            openAppPicker(context)
        }
    }

    fun openAppPicker(context: Context): String {
        return try {
            val intent = Intent(context, AppPickerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Opened interactive app picker menu."
        } catch (e: Exception) {
            "Error opening app picker: ${e.localizedMessage}"
        }
    }

    private fun resolveExactOrClosePackage(context: Context, nameQuery: String): String? {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        
        val selfPackage = context.packageName

        for (info in resolveInfos) {
            val pkg = info.activityInfo.packageName
            if (pkg == selfPackage) continue
            val label = info.loadLabel(pm).toString().lowercase()
            if (label == nameQuery) {
                return pkg
            }
        }

        for (info in resolveInfos) {
            val pkg = info.activityInfo.packageName
            if (pkg == selfPackage) continue
            val label = info.loadLabel(pm).toString().lowercase()
            if (label.startsWith(nameQuery) || label.contains(" $nameQuery")) {
                return pkg
            }
        }
        
        return null
    }

    fun getInstalledApps(context: Context): String {
        return try {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolveInfos = pm.queryIntentActivities(intent, 0)
            
            val appList = resolveInfos.map { 
                val label = it.loadLabel(pm).toString()
                val pkg = it.activityInfo.packageName
                "- $label ($pkg)"
            }.sorted()

            if (appList.isNotEmpty()) {
                "Installed Applications:\n" + appList.joinToString("\n")
            } else {
                "No launchable applications found."
            }
        } catch (e: Exception) {
            "Error retrieving installed apps: ${e.localizedMessage}"
        }
    }
}
