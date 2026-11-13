package com.freecam.loader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Companion app for the patched game (no root / Shizuku needed).
 *
 * The game itself is repackaged with Frida's gadget by patcher/patch_apk.py; the gadget
 * runs hook.js inside the game and listens on 127.0.0.1:27042. This app only:
 *  1. gets the "draw over other apps" permission,
 *  2. can test that the hook inside the running game is reachable,
 *  3. starts the floating joystick overlay (OverlayService), which streams camera
 *     deltas to the hook over that local socket.
 *
 * You must supply your own legally-owned copy of the game; this project contains no
 * game files, only tooling.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView

    companion object {
        private const val HOOK_HOST = "127.0.0.1"
        private const val HOOK_PORT = 27042 // must match LISTEN_PORT in hook.js
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logView = findViewById(R.id.logView)
        scroll = findViewById(R.id.logScroll)

        findViewById<Button>(R.id.btnOverlayPerm).setOnClickListener { requestOverlayPermission() }
        findViewById<Button>(R.id.btnTestHook).setOnClickListener { testHook() }
        findViewById<Button>(R.id.btnOverlayStart).setOnClickListener { startOverlay() }

        log(
            "1. Patch the game with patcher/patch_apk.py and install the patched build.\n" +
                "2. Launch the PATCHED game and load into a scene.\n" +
                "3. Come back here: Overlay Perm -> Test Hook -> Start Overlay.\n" +
                "4. Switch to the game and use the floating pad."
        )
    }

    private fun log(msg: String) {
        runOnUiThread {
            logView.append("$msg\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        } else {
            log("Overlay permission already granted.")
        }
    }

    /** Opens (and immediately closes) a connection to the hook inside the game. */
    private fun testHook() {
        log("Testing hook at $HOOK_HOST:$HOOK_PORT ...")
        Thread {
            try {
                Socket().use { it.connect(InetSocketAddress(HOOK_HOST, HOOK_PORT), 1500) }
                log("Hook reachable -- the patched game is running and hook.js is listening.")
            } catch (e: Exception) {
                log(
                    "Hook NOT reachable (${e.javaClass.simpleName}: ${e.message}).\n" +
                        "- Is the PATCHED game running (not the original)?\n" +
                        "- Has it finished loading? Try again once you're in a scene.\n" +
                        "- The game needs INTERNET permission to open the local socket."
                )
            }
        }.start()
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            log("Grant overlay permission first (tap 'Overlay Perm').")
            return
        }
        startService(Intent(this, OverlayService::class.java))
        log("Overlay started. Switch to the game.")
    }
}
