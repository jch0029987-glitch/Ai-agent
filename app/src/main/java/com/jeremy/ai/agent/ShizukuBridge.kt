package com.jeremy.ai.agent

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class ShizukuShellService() : IShizukuShellService.Stub() {

    override fun execShell(command: String, timeoutMs: Long): String {
        return try {
            val process = ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val effectiveTimeout = timeoutMs.coerceIn(1_000L, 120_000L)
            val finished = process.waitFor(effectiveTimeout, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return "Error: Shizuku shell command timed out after ${effectiveTimeout}ms."
            }
            process.inputStream.bufferedReader().use { it.readText() }.trim()
        } catch (e: Exception) {
            "Error running Shizuku shell command: ${e.localizedMessage ?: e.javaClass.simpleName}"
        }
    }

    override fun destroy() {
        runCatching { System.exit(0) }
    }
}

object ShizukuBridge {
    private const val TAG = "ShizukuBridge"
    private const val REQ_CODE_SHIZUKU = 4097

    @Volatile
    private var binderReady = false

    @Volatile
    private var shellService: IShizukuShellService? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        binderReady = true
        Log.i(TAG, "Shizuku binder received")
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        binderReady = false
        shellService = null
        Log.w(TAG, "Shizuku binder dead")
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQ_CODE_SHIZUKU) {
            Log.i(TAG, "Shizuku permission result: granted=${grantResult == PackageManager.PERMISSION_GRANTED}")
        }
    }

    fun registerListeners() {
        binderReady = Shizuku.pingBinder()
        runCatching { Shizuku.addBinderReceivedListenerSticky(binderReceivedListener) }
        runCatching { Shizuku.addBinderDeadListener(binderDeadListener) }
        runCatching { Shizuku.addRequestPermissionResultListener(permissionResultListener) }
    }

    fun unregisterListeners() {
        runCatching { Shizuku.removeBinderReceivedListener(binderReceivedListener) }
        runCatching { Shizuku.removeBinderDeadListener(binderDeadListener) }
        runCatching { Shizuku.removeRequestPermissionResultListener(permissionResultListener) }
    }

    fun isBinderAlive(): Boolean {
        val alive = binderReady || Shizuku.pingBinder()
        if (alive) binderReady = true
        return alive
    }

    fun isAvailableAndGranted(): Boolean {
        if (!isBinderAlive()) return false
        if (Shizuku.isPreV11()) return false
        return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }

    fun requestPermissionIfNeeded(): String {
        if (!isBinderAlive()) {
            return "Shizuku binder is not active. Start Shizuku first."
        }
        if (Shizuku.isPreV11()) {
            return "Shizuku API version is pre-v11; runtime request unsupported."
        }
        return if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            "Shizuku permission already granted (UID ${Shizuku.getUid()})."
        } else {
            Shizuku.requestPermission(REQ_CODE_SHIZUKU)
            "Requested Shizuku permission. Approve it in your Shizuku dialog/app."
        }
    }

    fun getStatusSummary(): String {
        val alive = isBinderAlive()
        val preV11 = if (alive) Shizuku.isPreV11() else false
        val granted = isAvailableAndGranted()
        val uid = if (alive && !preV11) runCatching { Shizuku.getUid() }.getOrDefault(-1) else -1
        val version = if (alive) runCatching { Shizuku.getVersion() }.getOrDefault(-1) else -1
        return "Shizuku Binder: `${if (alive) "alive" else "offline"}` | " +
            "Granted: `$granted` | UID: `$uid` | Version: `$version`"
    }

    private suspend fun ensureBound(context: Context): IShizukuShellService? {
        shellService?.let { existing ->
            if (existing.asBinder().isBinderAlive) return existing
            shellService = null
        }
        if (!isAvailableAndGranted()) return null

        return withTimeoutOrNull(5_000L) {
            suspendCoroutine { cont ->
                var resumed = false
                val args = Shizuku.UserServiceArgs(
                    ComponentName(context.packageName, ShizukuShellService::class.java.name)
                )
                    .daemon(false)
                    .processNameSuffix("shizuku_shell")
                    .debuggable(BuildConfig.DEBUG)
                    .version(1)

                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        val bound = service?.let(IShizukuShellService.Stub::asInterface)
                        shellService = bound
                        if (!resumed) {
                            resumed = true
                            cont.resume(bound)
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        shellService = null
                        if (!resumed) {
                            resumed = true
                            cont.resume(null)
                        }
                    }
                }

                runCatching {
                    Shizuku.bindUserService(args, connection)
                }.onFailure {
                    if (!resumed) {
                        resumed = true
                        cont.resume(null)
                    }
                }
            }
        }
    }

    suspend fun executeCommand(command: String, context: Context, timeoutSeconds: Long = 30L): String? =
        withContext(Dispatchers.IO) {
            val service = ensureBound(context) ?: return@withContext null
            return@withContext try {
                service.execShell(command, timeoutSeconds * 1000L)
            } catch (e: Exception) {
                Log.w(TAG, "Shizuku execShell failed, clearing cached service", e)
                shellService = null
                null
            }
        }
}
