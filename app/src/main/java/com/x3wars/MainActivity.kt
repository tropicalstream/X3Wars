package com.x3wars

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.x3wars.audio.Music
import com.x3wars.audio.Sfx
import com.x3wars.audio.Voice
import com.x3wars.engine.Game
import com.x3wars.engine.GameHost
import com.x3wars.gl.GLRenderer
import kotlin.math.abs
import kotlin.math.max

/**
 * X3Wars. TWO controls, no settings menu:
 *  - SWIPE on the temple pad = steer the aim reticle (and, in the trench,
 *    the ship): one discrete step per gesture, four directions. The pad's
 *    horizontal dx sign is inverted vs the physical gesture (suite gotcha),
 *    so forward-swipe = right.
 *  - TAP (arrives as a KEY on the glasses) = launch torpedoes at the port;
 *    also starts/retries. The cannons fire themselves.
 */
class MainActivity : Activity(), GameHost {

    private lateinit var store: SettingsStore
    private lateinit var sfx: Sfx
    private lateinit var voice: Voice
    private lateinit var music: Music
    private lateinit var game: Game
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: GLRenderer

    // De-dupe one physical press that may arrive as both KEY and touch.
    private var lastAction = 0L
    private var downX = 0f
    private var downY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        sfx = Sfx(this).also { it.loadAsync() }
        voice = Voice(this).also { it.load() }
        music = Music(this).also { it.load() }
        sfx.duckProvider = { voice.isSpeaking } // sound effects duck while the crew talks
        game = Game(store, this)
        renderer = GLRenderer(game).also { it.sbs = store.sbs }

        glView = object : GLSurfaceView(this) {}.apply {
            setEGLContextClientVersion(3)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        setContentView(glView)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        game.boot()
        // Screenshot/debug warp: adb shell am start ... --es warp <state>
        intent.getStringExtra("warp")?.let { w -> glView.queueEvent { game.debugWarp(w) } }
    }

    // ------------------------------------------------------------ GameHost

    override fun sfx(id: Int, pitch: Float, vol: Float) = sfx.play(id, pitch, vol)
    override fun startRumble(rate: Float) = sfx.startRumble(rate)
    override fun stopRumble() = sfx.stopRumble()
    override fun say(id: String, urgent: Boolean) = voice.say(id, urgent)
    override fun music(scene: String?) = music.play(scene)

    // --------------------------------------------------------------- input

    private fun act(run: () -> Unit) {
        val now = SystemClock.uptimeMillis()
        if (now - lastAction < 25) return // KEY+touch echo of one physical press
        lastAction = now
        glView.queueEvent(run)
    }

    private fun fireTap() = act { game.tap() }
    private fun aim(dir: Int) = act { game.aim(dir) }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> { fireTap(); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { aim(0); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { aim(1); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { aim(2); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { aim(3); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Ignore the left temple volume pad.
        if (ev.device?.name?.contains("cyttsp6", ignoreCase = true) == true) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y }
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                val dead = max(16f, 0.02f * resources.displayMetrics.widthPixels)
                if (abs(dx) < dead && abs(dy) < dead) {
                    fireTap()
                } else if (abs(dx) >= abs(dy)) {
                    // Per on-device test: dx > 0 aims right, dx < 0 aims left.
                    aim(if (dx < 0) 2 else 3)
                } else {
                    aim(if (dy < 0) 0 else 1)
                }
            }
        }
        return true
    }

    // ------------------------------------------------------------ lifecycle

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        glView.onResume()
        music.resume()
    }

    override fun onPause() {
        sfx.stopRumble()
        music.pause()
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        sfx.release()
        voice.release()
        music.release()
        super.onDestroy()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }
}
