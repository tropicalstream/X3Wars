package com.x3wars.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File
import kotlin.random.Random

/**
 * Scene music from user-supplied MP3s — bring your own soundtrack.
 *
 * On first boot the app creates one folder per scene under its app-specific
 * external storage (no permissions needed, visible over USB/adb):
 *
 *   Android/data/com.x3wars/files/music/
 *     title/  yavin_space/  yavin_surface/  yavin_trench/
 *     hoth_droids/  hoth_walkers/  hoth_fleet/  hoth_deck/
 *     endor_forest/  endor_space/  endor_core/  victory/
 *
 * Drop any .mp3 (or .ogg/.m4a) files in; the matching scene loops a random
 * one. Empty folder = that scene simply plays without music. Playback is a
 * single MediaPlayer on its own thread — never the GL thread.
 */
class Music(private val context: Context) {

    companion object {
        val SCENES = arrayOf(
            "title",
            "yavin_space", "yavin_surface", "yavin_trench",
            "hoth_droids", "hoth_walkers", "hoth_fleet", "hoth_deck",
            "endor_forest", "endor_space", "endor_core",
            "victory",
        )
        private const val TAG = "X3WarsMusic"
    }

    @Volatile var volume = 0.45f

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var player: MediaPlayer? = null
    private var current: String? = null
    private val rng = Random(System.nanoTime())

    fun load() {
        thread = HandlerThread("x3wars-music").apply { start() }
        handler = Handler(thread!!.looper)
        handler?.post {
            // Create the drop-in folders so they're discoverable.
            runCatching {
                val root = File(context.getExternalFilesDir(null), "music")
                for (s in SCENES) File(root, s).mkdirs()
            }.onFailure { Log.w(TAG, "music dirs", it) }
        }
    }

    /** Switch to a scene's folder (loops a random track), or stop with null. */
    fun play(scene: String?) {
        handler?.post {
            if (scene == current && player != null) return@post
            current = scene
            stopOnThread()
            if (scene == null) return@post
            val dir = File(File(context.getExternalFilesDir(null), "music"), scene)
            val tracks = dir.listFiles { f ->
                f.isFile && (f.name.endsWith(".mp3", true) ||
                    f.name.endsWith(".ogg", true) || f.name.endsWith(".m4a", true))
            }
            if (tracks == null || tracks.isEmpty()) return@post
            val pick = tracks[rng.nextInt(tracks.size)]
            runCatching {
                val mp = MediaPlayer()
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                )
                mp.setDataSource(pick.absolutePath)
                mp.isLooping = true
                mp.setVolume(volume, volume)
                mp.prepare()
                mp.start()
                player = mp
            }.onFailure { Log.w(TAG, "music ${pick.name}", it) }
        }
    }

    fun pause() { handler?.post { runCatching { player?.pause() } } }

    fun resume() { handler?.post { runCatching { player?.start() } } }

    private fun stopOnThread() {
        player?.let { runCatching { it.stop(); it.release() } }
        player = null
    }

    fun release() {
        handler?.post { stopOnThread() }
        thread?.quitSafely()
        thread = null
        handler = null
    }
}
