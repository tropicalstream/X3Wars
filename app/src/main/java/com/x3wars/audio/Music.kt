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
 * Scene music — bring your own soundtrack.
 *
 * Authoring happens in the repo: `music/<scene>/_prompt.txt` holds an
 * AI-music prompt per scene; generated tracks are dropped into those folders
 * and integrated into `app/src/main/assets/music/<scene>/`, shipping inside
 * the APK. The matching scene loops a random bundled track.
 *
 * An on-device override also works for quick experiments: any tracks in
 * `Android/data/com.x3wars/files/music/<scene>/` are preferred over the
 * bundled ones. Empty everywhere = the scene plays without music. Playback
 * is a single MediaPlayer on its own thread — never the GL thread.
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

        /**
         * An AI-music prompt per scene, written into each folder as
         * _prompt.txt — paste into your generator of choice, drop the MP3
         * back in the same folder.
         */
        val PROMPTS = mapOf(
            "title" to "Epic retro space-opera main theme, heroic brass fanfare over sweeping " +
                "strings, 1980s arcade grandeur with analog synth undertones, triumphant and " +
                "adventurous, builds to a bold statement then loops cleanly. Instrumental, 100 BPM.",
            "yavin_space" to "Driving orchestral space-battle music, urgent staccato strings and " +
                "punchy brass hits, dogfight energy, snare ostinato, soaring heroic counter-melody, " +
                "instrumental, loopable, 140 BPM.",
            "yavin_surface" to "Tense propulsive orchestral-synth hybrid, low brass pulses and " +
                "arpeggiated analog bass, skimming-over-metal-plains momentum, rising danger " +
                "figures, instrumental, loopable, 128 BPM.",
            "yavin_trench" to "Claustrophobic accelerating battle music, relentless percussion, " +
                "ticking-clock ostinato, brass stabs closing in, thin high strings holding a nerve " +
                "note, builds toward a fateful single-shot climax but never resolves, instrumental, 132 BPM.",
            "hoth_droids" to "Cold sparse tension music, icy string harmonics and glassy synth pads, " +
                "distant timpani, snowfall stillness with creeping mechanical menace underneath, " +
                "instrumental, loopable, 90 BPM.",
            "hoth_walkers" to "Ominous mechanical war-march, heavy low brass and pounding slow " +
                "percussion like giant footfalls, dread and inevitability, dark imperial menace, " +
                "instrumental, loopable, 84 BPM.",
            "hoth_fleet" to "Massive orchestral fleet-battle music, dark imperial motif looming " +
                "beneath heroic swirling strings, huge dynamic swells as a warship approaches, " +
                "instrumental, loopable, 120 BPM.",
            "hoth_deck" to "Aggressive strafing-run music, urgent brass rhythms over metallic " +
                "industrial percussion, alarms and adrenaline, victorious edge breaking through, " +
                "instrumental, loopable, 138 BPM.",
            "endor_forest" to "High-velocity chase music through ancient forest, galloping " +
                "percussion, whirling woodwinds and racing strings, playful but dangerous, " +
                "branches whipping past, instrumental, loopable, 150 BPM.",
            "endor_space" to "Grand climactic space-battle music, full orchestra at war, heroic " +
                "theme fighting through dissonant imperial brass, desperate and hopeful at once, " +
                "instrumental, loopable, 134 BPM.",
            "endor_core" to "Claustrophobic reactor-run music, pulsing warning-klaxon synth bass, " +
                "tight percussive rhythm in narrow metal corridors, rising heat and heartbeat, " +
                "explosive escape energy at the loop point, instrumental, 126 BPM.",
            "victory" to "Triumphant celebration fanfare, jubilant brass and bells, medal-ceremony " +
                "grandeur with warm strings, relieved joy after impossible odds, instrumental, " +
                "short loop, 108 BPM.",
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
        // Authoring lives in the repo's music/ folders; nothing to set up here.
    }

    private fun isTrack(name: String) =
        name.endsWith(".mp3", true) || name.endsWith(".ogg", true) || name.endsWith(".m4a", true)

    /** Switch scenes (loops a random track), or stop with null. */
    fun play(scene: String?) {
        handler?.post {
            if (scene == current && player != null) return@post
            current = scene
            stopOnThread()
            if (scene == null) return@post

            // On-device override first, then the tracks bundled in the APK.
            val dir = File(File(context.getExternalFilesDir(null), "music"), scene)
            val local = dir.listFiles { f -> f.isFile && isTrack(f.name) }
            if (local != null && local.isNotEmpty()) {
                startPlayer { it.setDataSource(local[rng.nextInt(local.size)].absolutePath) }
                return@post
            }
            val bundled = runCatching {
                context.assets.list("music/$scene")?.filter { isTrack(it) }
            }.getOrNull()
            if (bundled.isNullOrEmpty()) return@post
            val pick = bundled[rng.nextInt(bundled.size)]
            startPlayer {
                val fd = context.assets.openFd("music/$scene/$pick")
                it.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                fd.close()
            }
        }
    }

    private fun startPlayer(source: (MediaPlayer) -> Unit) {
        runCatching {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            source(mp)
            mp.isLooping = true
            mp.setVolume(volume, volume)
            mp.prepare()
            mp.start()
            player = mp
        }.onFailure { Log.w(TAG, "music start", it) }
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
