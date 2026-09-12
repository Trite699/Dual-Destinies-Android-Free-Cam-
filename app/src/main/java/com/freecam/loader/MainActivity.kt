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
import java.io.File
import java.io.FileOutputStream

/**
 * Entry point. This app does NOT attach to another process by itself —
 * Android's sandbox forbids that without elevated privileges. What it does:
 *
 *  1. Confirms the Shizuku service is running and permission is granted.
 *  2. Copies the bundled `frida-inject` binary + hook.js out of assets
 *     into this app's private, executable storage dir.
 *  3. Runs `frida-inject -n <process> -s hook.js` via Shizuku — Frida's
 *     own standalone injector, no frida-server daemon needed.
 *  4. Starts the floating overlay (OverlayService) so you can drive the
 *     camera once hook.js reports it's attached.
 *
 * Shizuku itself must already be installed and running (either paired
 * over ADB on a non-rooted device, or started from root) — see README.md.
 * You must supply your own legally-owned copy of the game; this project
 * contains no game files, only the loader/mod tooling.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var processInput: EditText

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

    /**
     * Runs a shell command via Shizuku and returns (exitCode, combinedOutput).
     *
     * Shizuku.newProcess() is no longer a public method in current versions
     * of dev.rikka.shizuku:api (Rikka is steering everyone towards
     * UserService instead), but it's still there and still works — this is
     * the standard reflection workaround documented by Shizuku's own
     * maintainers/issue tracker for apps that just need simple shell exec.
     */
    private fun runViaShizuku(cmd: String): Pair<Int, String> {
        return try {
            val clazz = Class.forName("rikka.shizuku.Shizuku")
            val method = clazz.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(
                null, arrayOf("sh", "-c", cmd), null, null
            ) as rikka.shizuku.ShizukuRemoteProcess

            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            val code = process.waitFor()
            code to (out + err)
        } catch (e: Exception) {
            -1 to (e.message ?: "unknown error running via Shizuku")
        }
    }

    /** Copies an asset out to a private, executable directory. */
    private fun extractAsset(name: String): File {
        val outFile = File(filesDir, name)
        assets.open(name).use { input ->
            FileOutputStream(outFile).use { output -> input.copyTo(output) }
        }
        outFile.setExecutable(true)
        return outFile
    }

    private fun extractAndInject() {
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            log("Shizuku permission not granted yet -- tap 'Check Root' first.")
            return
        }

        log("Extracting frida-inject + hook.js from assets...")
        val injectBin = try {
            extractAsset("frida-inject")
        } catch (e: Exception) {
            log("Missing assets/frida-inject -- CI fetches this automatically " +
                "(see .github/workflows/build.yml); local builds must run " +
                "scripts/fetch-frida-inject.sh first. ${e.message}")
            return
        }
        val hookScript = try {
            extractAsset("hook.js")
        } catch (e: Exception) {
            log("Missing assets/hook.js: ${e.message}")
            return
        }

        val target = processInput.text.toString().trim()
        if (target.isEmpty()) {
            log("Enter the game's process/package name first.")
            return
        }

        log("Chmod + launching frida-inject against '$target' (it must already be running)...")
        runViaShizuku("chmod 755 ${injectBin.absolutePath}")
        // -n attaches by process name; attaching to an already-running instance
        // avoids racing the game's own startup/anti-tamper checks.
        val cmd = "${injectBin.absolutePath} -n \"$target\" -s ${hookScript.absolutePath}"
        Thread {
            val (code, out) = runViaShizuku(cmd)
            log("frida-inject exited ($code):\n$out")
        }.start()
        log("Injection command dispatched -- watch the log above. " +
            "If it reports 'attached', tap 'Start Overlay' below.")
    }
}
