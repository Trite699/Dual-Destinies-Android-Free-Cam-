package com.freecam.loader

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import java.io.OutputStream
import java.net.Socket
import kotlin.math.max
import kotlin.math.min

/**
 * Floating control pad. Independent process from the game, so it can't call
 * into the game's memory directly — instead it connects to a TCP socket that
 * hook.js opens (via Frida's built-in `Socket.listen()`) inside the game
 * process, and streams movement deltas as newline-delimited JSON lines:
 *   {"dx":0.02,"dy":0.0,"dz":-0.01,"yaw":0.0,"pitch":0.0}
 *
 * hook.js is responsible for turning these into calls to
 * uFreeCamera::applyWorldOffset each frame.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var padView: View? = null
    private var socket: Socket? = null
    private var out: OutputStream? = null
    private val handler = Handler(Looper.getMainLooper())

    // Current stick deflection, [-1, 1] per axis
    private var moveX = 0f
    private var moveY = 0f
    private var verticalDelta = 0f // set momentarily by up/down buttons

    companion object {
        private const val HOOK_HOST = "127.0.0.1"
        private const val HOOK_PORT = 27042 // must match the listen port in hook.js
        private const val SEND_INTERVAL_MS = 50L // ~20 Hz
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        connectSocket()
        showPad()
        handler.post(sendLoop)
    }

    private fun connectSocket() {
        Thread {
            try {
                val s = Socket(HOOK_HOST, HOOK_PORT)
                socket = s
                out = s.getOutputStream()
            } catch (e: Exception) {
                // hook.js probably isn't listening yet (game not injected, or
                // still initializing). The send loop retries lazily below.
            }
        }.start()
    }

    private val sendLoop = object : Runnable {
        override fun run() {
            sendState()
            handler.postDelayed(this, SEND_INTERVAL_MS)
        }
    }

    private fun sendState() {
        val o = out
        if (o == null) {
            connectSocket()
            return
        }
        val json = """{"dx":$moveX,"dy":$verticalDelta,"dz":$moveY}""" + "\n"
        verticalDelta = 0f // momentary, not held
        try {
            o.write(json.toByteArray())
            o.flush()
        } catch (e: Exception) {
            out = null
            socket = null
        }
    }

    /** Minimal drag-pad + up/down buttons, no external view deps. */
    private fun showPad() {
        val root = FrameLayout(this)

        val pad = View(this).apply {
            setBackgroundColor(Color.argb(120, 40, 40, 40))
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        val cx = v.width / 2f
                        val cy = v.height / 2f
                        moveX = clamp((event.x - cx) / cx)
                        moveY = clamp((event.y - cy) / cy)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        moveX = 0f
                        moveY = 0f
                    }
                }
                true
            }
        }
        root.addView(pad, FrameLayout.LayoutParams(260, 260))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(context).apply {
                text = "Up"
                setOnClickListener { verticalDelta = 1f }
            })
            addView(Button(context).apply {
                text = "Down"
                setOnClickListener { verticalDelta = -1f }
            })
            addView(Button(context).apply {
                text = "Close"
                setOnClickListener { stopSelf() }
            })
        }
        root.addView(
            buttons,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM }
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 200
        }

        windowManager.addView(root, params)
        padView = root
    }

    private fun clamp(v: Float) = max(-1f, min(1f, v))

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        padView?.let { windowManager.removeView(it) }
        try { socket?.close() } catch (_: Exception) {}
    }
}
