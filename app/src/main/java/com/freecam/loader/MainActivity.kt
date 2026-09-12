package com.freecam.loader

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku

/**
 * Entry point. This app does NOT attach to another process by itself --
 * Android's sandbox forbids that without elevated privileges. What it does:
 *
 *  1. Confirms the Shizuku service is running and permission is granted.
 *  2. Streams the bundled `frida-inject` binary + hook.js from this app's
 *     assets straight into /data/local/tmp via a Shizuku-privileged
 *     process (NOT via this app's own private storage -- Shizuku's shell
 *     runs as a different UID and can't read app-private files, so they
 *     have to land somewhere universally accessible instead).
 *  3. Runs `frida-inject -n <process> -s hook.js` via Shizuku -- Frida's
 *     own standalone injector, no frida-server daemon needed.
 *  4. Starts the floating overlay (OverlayService) so you can drive the
 *     camera once hook.js reports it's attached.
 *
 * Shizuku itself must already be installed and running (either paired
 * over ADB on a non-rooted device, or started from root) -- see README.md.
 * You must supply your own legally-owned copy of the game; this project
 * contains no game files, only the loader/mod tooling.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var processInput: EditText

    companion object {
        private const val REMOTE_INJECT_PATH = "/data/local/tmp/freecam_frida_inject"
        private const val REMOTE_HOOK_PATH = "/data/local/tmp/freecam_hook.js"
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        log(
            if (grantResult == PackageManager.PERMISSION_GRANTED) "Shizuku permission granted."
            else "Shizuku permission denied."
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logView = findViewById(R.id.logView)
        processInput = findViewById(R.id.processNameInput)
        val scroll = findViewById<ScrollView>(R.id.logScroll)

        Shizuku.addRequestPermissionResultListener(permissionListener)

        findViewById<Button>(R.id.btnCheckRoot).setOnClickListener { checkShizuku() }
        findViewById<Button>(R.id.btnOverlayPerm).setOnClickListener { requestOverlayPermission() }
        findViewById<Button>(R.id.btnInject).setOnClickListener { extractAndInject() }
        findViewById<Button>(R.id.btnOverlayStart).setOnClickListener {
            startService(Intent(this, OverlayService::class.java))
        }

        fun autoScroll() = scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        log(
            "Ready. Target process defaults to the game's known package name;\n" +
                "edit it above if yours differs (check with 'adb shell pidof <pkg>').\n" +
                "Tap 'Check Root' first -- it actually checks Shizuku status now."
        )
        processInput.setText("com.capcom.dgs.google") // placeholder -- verify on your device
        autoScroll()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permissionListener)
    }

    private fun log(msg: String) {
        runOnUiThread { logView.append("$msg\n") }
    }

    /** Checks Shizuku is alive + permitted, requesting permission if needed. */
    private fun checkShizuku() {
        if (!Shizuku.pingBinder()) {
            log("Shizuku service not running. Start the Shizuku app first " +
                "(pair over ADB if unrooted, or launch from root) and try again.")
            return
        }
        if (Shizuku.isPreV11()) {
            log("Shizuku version too old (pre-v11, missing newProcess API).")
            return
        }
        val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        if (granted) {
            log("Shizuku OK -- permission already granted.")
        } else {
            log("Requesting Shizuku permission...")
            Shizuku.requestPermission(0)
        }
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } else {
            log("Overlay permission already granted.")
        }
    }

    /** Obtains a Shizuku remote process via reflection (newProcess is non-public in current API). */
    private fun newShizukuProcess(cmd: Array<String>): rikka.shizuku.ShizukuRemoteProcess {
        val clazz = Class.forName("rikka.shizuku.Shizuku")
        val method = clazz.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(null, cmd, null, null) as rikka.shizuku.ShizukuRemoteProcess
    }

    /** Runs a shell command via Shizuku and returns (exitCode, combinedOutput). */
    private fun runViaShizuku(cmd: String): Pair<Int, String> {
        return try {
            val process = newShizukuProcess(arrayOf("sh", "-c", cmd))
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            val code = process.waitFor()
            code to (out + err)
        } catch (e: Exception) {
            -1 to (e.message ?: "unknown error running via Shizuku")
        }
    }

    /**
     * Streams an asset's raw bytes into a Shizuku-privileged `cat > remotePath`
     * process's stdin, then chmods it executable. This deliberately never
     * touches this app's own private storage -- Shizuku's shell (root or ADB
     * shell UID) generally cannot read files inside another app's sandboxed
     * /data/user/0/<pkg>/ directory, which is exactly what caused
     * "inaccessible or not found" when we extracted there first.
     */
    private fun pushAssetToDevice(assetName: String, remotePath: String): Boolean {
        return try {
            val bytes = assets.open(assetName).use { it.readBytes() }
            val process = newShizukuProcess(arrayOf("sh", "-c", "cat > $remotePath"))
            process.outputStream.use { it.write(bytes) }
            val code = process.waitFor()
            if (code != 0) {
                val err = process.errorStream.bufferedReader().readText()
                log("Failed writing $remotePath (exit $code): $err")
                return false
            }
            val (chmodCode, chmodOut) = runViaShizuku("chmod 755 $remotePath")
            if (chmodCode != 0) {
                log("chmod failed on $remotePath (exit $chmodCode): $chmodOut")
                return false
            }
            true
        } catch (e: Exception) {
            log("Error pushing $assetName -> $remotePath: ${e.message}")
            false
        }
    }

    private fun extractAndInject() {
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            log("Shizuku permission not granted yet -- tap 'Check Root' first.")
            return
        }

        val target = processInput.text.toString().trim()
        if (target.isEmpty()) {
            log("Enter the game's process/package name first.")
            return
        }

        log("Pushing frida-inject + hook.js to $REMOTE_INJECT_PATH / $REMOTE_HOOK_PATH...")
        Thread {
            val injectOk = pushAssetToDevice("frida-inject", REMOTE_INJECT_PATH)
            if (!injectOk) {
                log("Could not stage frida-inject -- see error above. " +
                    "Check assets/frida-inject exists (CI fetches it automatically).")
                return@Thread
            }
            val hookOk = pushAssetToDevice("hook.js", REMOTE_HOOK_PATH)
            if (!hookOk) {
                log("Could not stage hook.js -- see error above.")
                return@Thread
            }

            log("Launching frida-inject against '$target' (it must already be running)...")
            // -n attaches by process name; attaching to an already-running instance
            // avoids racing the game's own startup/anti-tamper checks.
            val cmd = "$REMOTE_INJECT_PATH -n \"$target\" -s $REMOTE_HOOK_PATH"
            val (code, out) = runViaShizuku(cmd)
            log("frida-inject exited ($code):\n$out")
        }.start()
        log("Injection dispatched -- watch the log above. " +
            "If it reports 'attached', tap 'Start Overlay' below.")
    }
}
