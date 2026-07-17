package com.x3wars.gl

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.x3wars.engine.Game
import com.x3wars.engine.GameState
import com.x3wars.engine.Level
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * First-person vector renderer for the X3Wars campaign: additive glowing
 * wireframes on black (transparent on the waveguide), a perspective camera at
 * the origin, and a 640x480 ortho stroke-font HUD — the 1983 color-vector
 * look, per eye on the X3's side-by-side viewports. Each battle gets its own
 * palette: YAVIN phosphor green, HOTH ice blue over snowfall, the destroyer
 * deck in steel, ENDOR's amber trunks, the core run in warning teal.
 */
class GLRenderer(private val game: Game) : GLSurfaceView.Renderer {

    var sbs = false

    private var program = 0
    private var aPos = 0; private var aColor = 0
    private var uMVP = 0; private var uPointSize = 0; private var uPoint = 0
    private var width = 1; private var height = 1
    private var lastNanos = 0L

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)
    private val ortho = FloatArray(16)
    private val rgb = FloatArray(3)

    private val lines = Batch(32000)
    private val fx = Batch(8000)
    private val hud = Batch(9000)

    private val rnd = Random(7)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        program = buildProgram(VERT, FRAG)
        aPos = GLES30.glGetAttribLocation(program, "aPos")
        aColor = GLES30.glGetAttribLocation(program, "aColor")
        uMVP = GLES30.glGetUniformLocation(program, "uMVP")
        uPointSize = GLES30.glGetUniformLocation(program, "uPointSize")
        uPoint = GLES30.glGetUniformLocation(program, "uPoint")
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
        lastNanos = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
        Matrix.orthoM(ortho, 0, 0f, 640f, 480f, 0f, -1f, 1f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastNanos = now

        game.update(dt)
        buildScene()
        buildHud()

        val eyes = if (sbs) 2 else 1
        val vw = if (sbs) width / 2 else width
        val aspect = vw.toFloat() / height.toFloat()

        GLES30.glViewport(0, 0, width, height)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)

        val run = isRun()
        val bank = (if (run) -game.rx * 10f else -game.rx * 4f) + game.camRailRoll
        val shX = (rnd.nextFloat() - 0.5f) * 0.5f * game.shake
        val shY = (rnd.nextFloat() - 0.5f) * 0.5f * game.shake
        Matrix.setIdentityM(view, 0)
        Matrix.rotateM(view, 0, bank, 0f, 0f, 1f)
        val camX = (if (run) game.shipX() else game.camRailX) + shX
        val camY = (if (run) game.shipY() else game.camRailY) + shY
        Matrix.translateM(view, 0, -camX, -camY, 0f)

        Matrix.perspectiveM(proj, 0, 57.6f, aspect, 0.4f, 520f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

        for (e in 0 until eyes) {
            GLES30.glViewport(e * vw, 0, vw, height)
            GLES30.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
            GLES30.glUniform1f(uPoint, 0f)
            lines.draw(GLES30.GL_LINES)
            GLES30.glUniform1f(uPoint, 1f)
            GLES30.glUniform1f(uPointSize, 10f)
            fx.draw(GLES30.GL_POINTS)
            GLES30.glUniformMatrix4fv(uMVP, 1, false, ortho, 0)
            GLES30.glUniform1f(uPoint, 0f)
            hud.draw(GLES30.GL_LINES)
        }
    }

    private fun isRun() = game.state == GameState.TRENCH || game.state == GameState.BIKES ||
        game.state == GameState.CORE || game.state == GameState.PORT

    // ------------------------------------------------------------- scene

    private fun buildScene() {
        lines.reset(); fx.reset()
        when (game.state) {
            GameState.TITLE -> { buildStars(0.7f); buildStation(0f, 2f, -190f, 52f, game.time * 4f, 1f) }
            GameState.BRIEFING -> { buildStars(1f); buildVista() }
            GameState.FIGHTERS -> { buildStars(1f); buildVista(); buildSwoopers(); buildBolts(); buildBeams(); buildAimReticle() }
            GameState.DROIDS -> {
                buildStars(0.9f)   // snowfall via game.snowMode
                buildVista()
                buildSnowfield(-6f)
                buildSwoopers(); buildBolts(); buildBeams(); buildAimReticle()
            }
            GameState.FLEET -> {
                buildStars(1f)
                buildVista()
                if (game.fleetMega) buildMegaShip()
                buildSwoopers(); buildBolts(); buildBeams(); buildAimReticle()
            }
            GameState.SURFACE -> {
                buildStars(0.5f)
                buildVista()
                buildGround(-6f, 0.25f, 1f, 0.45f, 0.30f)
                buildTowers(); buildBolts(); buildBeams(); buildAimReticle()
            }
            GameState.WALKERS -> {
                buildStars(0.9f)
                buildVista()
                buildSnowfield(-6f)
                buildTowers(); buildBolts(); buildBeams(); buildAimReticle()
            }
            GameState.DECK -> {
                buildStars(0.5f)
                buildGround(-6f, 0.55f, 0.7f, 0.95f, 0.30f)
                buildTowers(); buildBolts(); buildBeams(); buildAimReticle()
            }
            GameState.TRENCH -> {
                buildTunnel(Game.TRENCH_HALF_W, Game.TRENCH_FLOOR, Game.TRENCH_TOP,
                    0.25f, 1f, 0.45f, ceiling = false)
                buildBarriers(); buildTowers(); buildBolts(); buildBeamsCenter()
            }
            GameState.BIKES -> {
                buildForest()
                buildBarriers(); buildTowers(); buildBolts(); buildBeamsCenter()
            }
            GameState.CORE -> {
                buildTunnel(Game.CORE_HALF_W, Game.CORE_FLOOR, Game.CORE_TOP,
                    0.3f, 0.9f, 0.85f, ceiling = true)
                buildBarriers(); buildBolts(); buildBeamsCenter()
            }
            GameState.PORT -> {
                when (game.portKind) {
                    Game.PORT_GENERATOR -> { buildForest(); buildGenerator() }
                    Game.PORT_CORE -> {
                        buildTunnel(Game.CORE_HALF_W, Game.CORE_FLOOR, Game.CORE_TOP,
                            0.3f, 0.9f, 0.85f, ceiling = true)
                        buildCoreHeart()
                    }
                    else -> {
                        buildTunnel(Game.TRENCH_HALF_W, Game.TRENCH_FLOOR, Game.TRENCH_TOP,
                            0.25f, 1f, 0.45f, ceiling = false)
                        buildExhaustPort()
                    }
                }
                buildBolts()
                if (game.torpedoT >= 0f) buildTorpedoes()
            }
            GameState.MINIWIN -> buildForest()
            GameState.DOCK -> { buildStars(0.5f); buildDockScene() }
            GameState.VICTORY -> buildVictory()
            GameState.GAMEOVER -> buildStars(0.25f)
        }
        buildParticles()
    }

    private fun buildStars(bright: Float) {
        val s = game.stars
        for (i in 0 until s.size / 3) {
            val z = s[i * 3 + 2]
            val a = ((300f + z) / 300f * 0.9f + 0.1f) * bright
            fx.v(s[i * 3], s[i * 3 + 1], z, 0.8f, 0.85f, 1f, a * 0.8f)
        }
    }

    // ------------------------------------------------------ enemy swoopers

    private fun buildSwoopers() {
        for (f in game.fighters) {
            if (!f.alive) continue
            when (f.kind) {
                1 -> buildDroid(f.x, f.y, f.z, f.t)
                2 -> buildGunship(f.x, f.y, f.z)
                3 -> buildHunter(f.x, f.y, f.z, f.t)
                else -> buildInterceptor(f.x, f.y, f.z)
            }
        }
    }

    private fun buildInterceptor(x: Float, y: Float, z: Float) {
        val s = 2.2f
        val r = 0.55f; val g = 0.75f; val b = 1f
        for (side in intArrayOf(-1, 1)) {
            val px = x + side * s
            hexPanel(px, y, z, s * 1.15f, s * 0.42f, r, g, b, 0.95f)
            lines.line(px, y, z, x + side * 0.42f * s, y, z, r, g, b, 0.75f)
        }
        ring(x, y, z, 0.5f * s, 8, r, g, b, 0.95f)
        fx.v(x, y, z, 1f, 1f, 1f, 0.5f)
    }

    /** Probe droid: hovering pod, sensor ring, dangling feeler legs. */
    private fun buildDroid(x: Float, y: Float, z: Float, t: Float) {
        val bob = sin(t * 3f) * 0.3f
        val yy = y + bob
        val r = 0.7f; val g = 0.85f; val b = 1f
        ring(x, yy, z, 1.1f, 8, r, g, b, 0.95f)
        ring(x, yy + 0.5f, z, 0.55f, 6, r, g, b, 0.8f)
        lines.line(x, yy + 0.5f, z, x, yy + 1.5f, z, r, g, b, 0.9f)     // antenna
        fx.v(x, yy + 1.5f, z, 1f, 0.4f, 0.3f, 0.7f + 0.3f * sin(t * 8f)) // blinker
        var i = 0
        while (i < 4) {
            val an = i / 4f * 6.2832f + 0.6f
            val lx = x + cos(an) * 0.8f
            lines.line(lx, yy - 0.4f, z, lx + cos(an) * 0.25f, yy - 1.5f - sin(t * 4f + i) * 0.15f, z, r, g, b, 0.7f)
            i++
        }
    }

    /** Gunship: a wide slab with engine pods — soaks three hits. */
    private fun buildGunship(x: Float, y: Float, z: Float) {
        val r = 0.85f; val g = 0.6f; val b = 1f
        val w = 3.6f; val h = 1.1f
        lines.line(x - w, y - h, z, x + w, y - h, z, r, g, b, 0.95f)
        lines.line(x - w, y + h, z, x + w, y + h, z, r, g, b, 0.95f)
        lines.line(x - w, y - h, z, x - w * 0.8f, y + h, z, r, g, b, 0.95f)
        lines.line(x + w, y - h, z, x + w * 0.8f, y + h, z, r, g, b, 0.95f)
        ring(x - w * 0.65f, y, z, 0.55f, 6, r, g, b, 0.85f)
        ring(x + w * 0.65f, y, z, 0.55f, 6, r, g, b, 0.85f)
        ring(x, y, z, 0.8f, 4, 1f, 0.8f, 0.4f, 0.9f)                    // bridge diamond
    }

    /** Hunter: a lean dart, all speed. */
    private fun buildHunter(x: Float, y: Float, z: Float, t: Float) {
        val r = 1f; val g = 0.55f; val b = 0.75f
        val s = 1.5f
        lines.line(x, y + s * 0.5f, z, x - s, y - s * 0.5f, z, r, g, b, 0.95f)
        lines.line(x, y + s * 0.5f, z, x + s, y - s * 0.5f, z, r, g, b, 0.95f)
        lines.line(x - s, y - s * 0.5f, z, x + s, y - s * 0.5f, z, r, g, b, 0.95f)
        fx.v(x, y - s * 0.2f, z + 0.4f, 1f, 0.7f, 0.4f, 0.6f + 0.4f * sin(t * 20f))
    }

    private fun hexPanel(x: Float, y: Float, z: Float, h: Float, w: Float, r: Float, g: Float, b: Float, a: Float) {
        val ys = floatArrayOf(h, h * 0.5f, -h * 0.5f, -h, -h * 0.5f, h * 0.5f)
        val zs = floatArrayOf(0f, w, w, 0f, -w, -w)
        for (i in 0 until 6) {
            val j = (i + 1) % 6
            lines.line(x, y + ys[i], z + zs[i], x, y + ys[j], z + zs[j], r, g, b, a)
        }
    }

    private fun ring(x: Float, y: Float, z: Float, rad: Float, segs: Int, r: Float, g: Float, b: Float, a: Float) {
        var px = x + rad; var py = y
        for (k in 1..segs) {
            val an = k.toFloat() / segs * 6.2832f
            val nx = x + cos(an) * rad
            val ny = y + sin(an) * rad
            lines.line(px, py, z, nx, ny, z, r, g, b, a)
            px = nx; py = ny
        }
    }

    /** The dagger destroyer looming behind the FLEET act, growing with progress. */
    private fun buildMegaShip() {
        val prog = (game.kills.toFloat() / game.killQuota.coerceAtLeast(1)).coerceIn(0f, 1f)
        val z = -300f + prog * 90f
        val w = 60f; val l = 90f
        val y = 18f
        val r = 0.65f; val g = 0.75f; val b = 0.95f; val a = 0.5f + prog * 0.3f
        // wedge: nose ahead, widening astern
        lines.line(0f, y, z, -w, y + 8f, z - l, r, g, b, a)
        lines.line(0f, y, z, w, y + 8f, z - l, r, g, b, a)
        lines.line(-w, y + 8f, z - l, w, y + 8f, z - l, r, g, b, a * 0.8f)
        lines.line(0f, y, z, 0f, y + 14f, z - l * 0.75f, r, g, b, a * 0.7f)
        lines.line(0f, y + 14f, z - l * 0.75f, -w * 0.7f, y + 8f, z - l, r, g, b, a * 0.6f)
        lines.line(0f, y + 14f, z - l * 0.75f, w * 0.7f, y + 8f, z - l, r, g, b, a * 0.6f)
        // bridge tower
        lines.line(0f, y + 14f, z - l * 0.72f, 0f, y + 19f, z - l * 0.72f, r, g, b, a)
        lines.line(-4f, y + 19f, z - l * 0.72f, 4f, y + 19f, z - l * 0.72f, r, g, b, a)
        fx.v(-4f, y + 19f, z - l * 0.72f, 1f, 1f, 1f, a)
        fx.v(4f, y + 19f, z - l * 0.72f, 1f, 1f, 1f, a)
    }

    private fun buildBolts() {
        for (b in game.bolts) {
            if (!b.alive) continue
            fx.v(b.x, b.y, b.z, 1f, 0.5f, 0.2f, 1f)
            lines.line(b.x, b.y, b.z, b.x - b.vx * 0.06f, b.y - b.vy * 0.06f, b.z - b.vz * 0.06f,
                1f, 0.35f, 0.12f, 0.8f)
            ring(b.x, b.y, b.z, 0.34f, 6, 1f, 0.4f, 0.15f, 0.9f)
        }
    }

    private fun buildBeams() {
        val t = game.beamT
        if (t >= 0.35f) return
        val a = (1f - t / 0.35f) * 0.9f
        val d = 30f
        val cx = game.camRailX; val cy = game.camRailY
        val ax = cx + game.beamX * Game.TANX * d
        val ay = cy + game.beamY * Game.TANY * d
        val gx = if (game.beamRight) 5.4f else -5.4f
        lines.line(cx + gx, cy - 4.4f, -2f, ax, ay, -d, 0.4f, 1f, 0.65f, a)
        lines.line(cx - gx, cy - 4.4f, -2f, ax, ay, -d, 0.4f, 1f, 0.65f, a * 0.55f)
        fx.v(ax, ay, -d, 0.7f, 1f, 0.8f, a)
    }

    private fun buildBeamsCenter() {
        val t = game.beamT
        if (t >= 0.35f) return
        val a = (1f - t / 0.35f) * 0.9f
        val sx = game.shipX(); val sy = game.shipY()
        val gx = if (game.beamRight) 1.6f else -1.6f
        lines.line(sx + gx, sy - 1.2f, -2f, sx, sy, -34f, 0.4f, 1f, 0.65f, a)
        lines.line(sx - gx, sy - 1.2f, -2f, sx, sy, -34f, 0.4f, 1f, 0.65f, a * 0.55f)
        fx.v(sx, sy, -34f, 0.7f, 1f, 0.8f, a)
    }

    private fun buildAimReticle() {
        val d = 30f
        val x = game.camRailX + game.rx * Game.TANX * d
        val y = game.camRailY + game.ry * Game.TANY * d
        val s = 1.5f; val gpx = 0.65f
        val r = 0.35f; val g = 1f; val b = 0.5f; val a = 0.95f
        lines.line(x - s, y - s, -d, x - gpx, y - s, -d, r, g, b, a)
        lines.line(x - s, y - s, -d, x - s, y - gpx, -d, r, g, b, a)
        lines.line(x + s, y - s, -d, x + gpx, y - s, -d, r, g, b, a)
        lines.line(x + s, y - s, -d, x + s, y - gpx, -d, r, g, b, a)
        lines.line(x - s, y + s, -d, x - gpx, y + s, -d, r, g, b, a)
        lines.line(x - s, y + s, -d, x - s, y + gpx, -d, r, g, b, a)
        lines.line(x + s, y + s, -d, x + gpx, y + s, -d, r, g, b, a)
        lines.line(x + s, y + s, -d, x + s, y + gpx, -d, r, g, b, a)
    }

    // ------------------------------------------------------ ground scenes

    private fun buildGround(y: Float, r: Float, g: Float, b: Float, alpha: Float) {
        val scroll = (game.time * game.worldSpeed.coerceAtLeast(40f)) % 8f
        var i = 0
        while (i < 42) {
            val z = -(i * 8f - scroll)
            if (z < -1f) lines.line(-44f, y, z, 44f, y, z, r, g, b, alpha)
            i++
        }
        var x = -40f
        while (x <= 40f) {
            lines.line(x, y, -330f, x, y, -1f, r, g, b, alpha * 0.7f)
            x += 8f
        }
        lines.line(-160f, y, -330f, 160f, y, -330f, r, g, b, alpha + 0.2f)
    }

    /**
     * The scenic backdrop the rail sweeps past — each battle gets a horizon
     * worth the flight: Yavin's ringed gas giant, Hoth's pale sun and aurora,
     * Endor's mottled forest moon with the half-built station hanging beside.
     */
    private fun buildVista() {
        when (game.level) {
            Level.YAVIN -> {
                // ringed gas giant, low on the left
                val cx = -58f; val cy = 12f; val cz = -368f
                ring(cx, cy, cz, 42f, 24, 0.9f, 0.55f, 0.35f, 0.5f)
                ring(cx, cy, cz, 34f, 20, 0.9f, 0.5f, 0.3f, 0.22f)
                var i = 0
                while (i < 3) {
                    val rr = 52f + i * 7f
                    var px = cx + rr; var py = cy
                    var k = 1
                    while (k <= 18) {
                        val an = k / 18f * 6.2832f
                        val nx = cx + cos(an) * rr
                        val ny = cy + sin(an) * rr * 0.22f
                        lines.line(px, py, cz, nx, ny, cz, 1f, 0.75f, 0.45f, 0.35f - i * 0.09f)
                        px = nx; py = ny
                        k++
                    }
                    i++
                }
                // small jungle moon glinting far right
                ring(64f, 22f, -350f, 6f, 10, 0.5f, 0.95f, 0.55f, 0.6f)
            }
            Level.HOTH -> {
                // pale low sun with halo
                ring(48f, 26f, -370f, 9f, 12, 1f, 1f, 1f, 0.75f)
                ring(48f, 26f, -370f, 15f, 12, 0.85f, 0.92f, 1f, 0.22f)
                // aurora curtains
                var i = 0
                while (i < 3) {
                    var px = 0f; var py = 0f
                    var k = 0
                    while (k <= 16) {
                        val x = -80f + k * 10f
                        val y = 34f + i * 7f + sin(x * 0.07f + game.time * 0.5f + i) * 5f
                        if (k > 0) lines.line(px, py, -360f, x, y, -360f, 0.4f, 1f, 0.7f, 0.16f + i * 0.04f)
                        px = x; py = y
                        k++
                    }
                    i++
                }
            }
            Level.ENDOR -> {
                // the forest moon, big and mottled
                val cx = 52f; val cy = 18f; val cz = -368f
                ring(cx, cy, cz, 26f, 22, 0.45f, 0.95f, 0.5f, 0.6f)
                var i = 0
                while (i < 4) {
                    val rr = 6f + i * 5f
                    ring(cx - 8f + i * 5f, cy - 4f + (i % 2) * 7f, cz, rr * 0.35f, 8, 0.4f, 0.85f, 0.45f, 0.25f)
                    i++
                }
                // the unfinished station hanging in the sky
                buildStation(-52f, 24f, -360f, 11f, game.time * 6f, 0.7f, unfinished = true)
            }
        }
    }

    /**
     * The hangar: guide lights converging into a glowing bay, the carrier's
     * hull above, clamps easing shut, shields refilling pip by pip while the
     * story crawls past the canopy.
     */
    private fun buildDockScene() {
        val t = game.stateT
        val r = 0.45f; val g = 0.8f; val b = 1f
        // approach: the bay frame grows toward us for the first seconds
        val approach = (t / 6f).coerceIn(0f, 1f)
        val z = -170f + approach * 130f          // -170 -> -40
        val w = 26f; val h = 12f
        val yOff = -4f
        // carrier hull above the bay
        lines.line(-w * 2.2f, yOff + h + 6f, z - 40f, w * 2.2f, yOff + h + 6f, z - 40f, r, g, b, 0.5f)
        lines.line(-w * 2.2f, yOff + h + 6f, z - 40f, -w * 1.4f, yOff + h, z, r, g, b, 0.4f)
        lines.line(w * 2.2f, yOff + h + 6f, z - 40f, w * 1.4f, yOff + h, z, r, g, b, 0.4f)
        // the bay mouth
        lines.line(-w, yOff - h, z, w, yOff - h, z, r, g, b, 0.9f)
        lines.line(-w, yOff + h, z, w, yOff + h, z, r, g, b, 0.9f)
        lines.line(-w, yOff - h, z, -w, yOff + h, z, r, g, b, 0.9f)
        lines.line(w, yOff - h, z, w, yOff + h, z, r, g, b, 0.9f)
        // interior depth lines
        lines.line(-w, yOff - h, z, -w * 0.55f, yOff - h * 0.55f, z - 55f, r, g, b, 0.4f)
        lines.line(w, yOff - h, z, w * 0.55f, yOff - h * 0.55f, z - 55f, r, g, b, 0.4f)
        lines.line(-w, yOff + h, z, -w * 0.55f, yOff + h * 0.55f, z - 55f, r, g, b, 0.4f)
        lines.line(w, yOff + h, z, w * 0.55f, yOff + h * 0.55f, z - 55f, r, g, b, 0.4f)
        // guide lights: twin converging rows, blinking in sequence
        var i = 0
        while (i < 7) {
            val gz = z + 8f + i * 9f
            if (gz < -6f) {
                val gw = w * (0.35f + 0.09f * i)
                val on = ((game.time * 4f).toInt() + i) % 7 < 3
                val a = if (on) 0.95f else 0.3f
                fx.v(-gw, yOff - h * 0.8f, gz, 0.4f, 1f, 0.6f, a)
                fx.v(gw, yOff - h * 0.8f, gz, 0.4f, 1f, 0.6f, a)
            }
            i++
        }
        // docking clamps ease shut as we settle
        val clamp = ((t - 5f) / 3f).coerceIn(0f, 1f)
        if (clamp > 0f) {
            val cx = w * (1f - clamp * 0.55f)
            lines.line(-cx, yOff - h * 0.2f, -30f, -cx + 4f, yOff, -30f, 1f, 0.75f, 0.3f, 0.8f)
            lines.line(cx, yOff - h * 0.2f, -30f, cx - 4f, yOff, -30f, 1f, 0.75f, 0.3f, 0.8f)
        }
    }

    /**
     * The Hoth snowfield: a pale ground lattice buried under drifts — dense
     * white sastrugi dashes with glints, a bright horizon band, and haze
     * lines that read as wind-blown powder.
     */
    private fun buildSnowfield(y: Float) {
        val scroll = (game.time * game.worldSpeed.coerceAtLeast(40f)) % 8f
        // faint buried grid
        var i = 0
        while (i < 42) {
            val z = -(i * 8f - scroll)
            if (z < -1f) lines.line(-44f, y, z, 44f, y, z, 0.75f, 0.88f, 1f, 0.10f)
            i++
        }
        var x = -40f
        while (x <= 40f) {
            lines.line(x, y, -330f, x, y, -1f, 0.75f, 0.88f, 1f, 0.07f)
            x += 8f
        }
        // sastrugi: short wind-carved dashes, deterministic per cell so the
        // field scrolls as solid ground rather than shimmering noise
        i = 0
        while (i < 40) {
            val z = -(i * 8f - scroll)
            if (z < -2f) {
                var j = 0
                while (j < 9) {
                    val h1 = ((i * 73 + j * 131) % 97) / 97f
                    val h2 = ((i * 37 + j * 61) % 89) / 89f
                    val h3 = ((i * 91 + j * 17) % 83) / 83f
                    val dx = (h1 * 2f - 1f) * 42f
                    val dz = z + (h2 - 0.5f) * 7f
                    val len = 0.5f + h3 * 1.3f
                    val tilt = (h2 - 0.5f) * 0.8f
                    val a = 0.16f + h3 * 0.22f
                    lines.line(dx - len, y + 0.02f, dz, dx + len, y + 0.02f + tilt * 0.1f, dz + tilt, 0.9f, 0.96f, 1f, a)
                    // occasional glint
                    if (h1 > 0.82f) fx.v(dx, y + 0.05f, dz, 1f, 1f, 1f, 0.35f)
                    j++
                }
            }
            i++
        }
        // bright horizon band + wind haze above it
        lines.line(-160f, y, -330f, 160f, y, -330f, 0.95f, 0.98f, 1f, 0.8f)
        lines.line(-160f, y + 0.8f, -328f, 160f, y + 0.8f, -328f, 0.85f, 0.92f, 1f, 0.30f)
        lines.line(-160f, y + 2f, -326f, 160f, y + 2f, -326f, 0.8f, 0.9f, 1f, 0.14f)
        // drifting powder streaks near the ground
        var k = 0
        while (k < 6) {
            val z = -30f - k * 45f
            val off = ((game.time * (14f + k * 3f)) % 90f) - 45f
            lines.line(off - 6f, y + 0.35f, z, off + 6f, y + 0.4f, z, 0.9f, 0.95f, 1f, 0.10f)
            k++
        }
    }

    /** A manned emplacement on the trench wall: mount, dome, inward barrel. */
    private fun buildWallTurret(x: Float, yPos: Float, z: Float) {
        val r = 1f; val g = 0.75f; val b = 0.3f
        val inward = if (x < 0f) 1f else -1f
        // mount plate on the wall
        lines.line(x, yPos - 0.7f, z - 0.7f, x, yPos + 0.7f, z - 0.7f, r, g, b, 0.9f)
        lines.line(x, yPos - 0.7f, z + 0.7f, x, yPos + 0.7f, z + 0.7f, r, g, b, 0.9f)
        lines.line(x, yPos - 0.7f, z - 0.7f, x, yPos - 0.7f, z + 0.7f, r, g, b, 0.9f)
        lines.line(x, yPos + 0.7f, z - 0.7f, x, yPos + 0.7f, z + 0.7f, r, g, b, 0.9f)
        // dome
        ring(x + inward * 0.3f, yPos, z, 0.45f, 6, r, g, b, 0.95f)
        // barrel angled into the trench
        lines.line(x + inward * 0.3f, yPos, z, x + inward * 1.5f, yPos - 0.15f, z + 0.5f, r, g, b, 0.95f)
        fx.v(x + inward * 0.3f, yPos, z, 1f, 0.85f, 0.4f, 0.6f)
    }

    private fun buildTowers() {
        for (t in game.towers) {
            if (!t.alive) continue
            when (t.kind) {
                1 -> buildWalker(t.x, t.z, t.h, t.phase)
                2 -> buildRadar(t.x, t.z, t.h)
                3 -> buildDeckTurret(t.x, t.z, t.h)
                4 -> buildStrider(t.x, t.z, t.phase)
                5 -> buildWallTurret(t.x, t.h, t.z)
                else -> buildCannonTower(t.x, t.z, t.h)
            }
        }
    }

    private fun buildCannonTower(x: Float, z: Float, h: Float) {
        val r = 0.3f; val g = 1f; val b = 0.5f
        val base = -6f
        val top = base + h
        val w = 1.1f
        for (s in intArrayOf(-1, 1)) {
            lines.line(x + s * w, base, z, x + s * w * 0.7f, top, z, r, g, b, 0.9f)
            lines.line(x + s * w * 0.7f, top, z, x + s * w * 0.7f, top + 0.7f, z, 1f, 0.8f, 0.3f, 0.95f)
        }
        lines.line(x - w, base, z, x + w, base, z, r, g, b, 0.8f)
        lines.line(x - w * 0.7f, top, z, x + w * 0.7f, top, z, r, g, b, 0.9f)
        ring(x, top + 0.7f, z, 0.55f, 6, 1f, 0.8f, 0.3f, 0.95f)
    }

    /** The four-legged armored walker, striding through the snow. */
    private fun buildWalker(x: Float, z: Float, h: Float, phase: Float) {
        val r = 0.55f; val g = 0.68f; val b = 0.9f
        val base = -6f
        val hip = base + h * 0.55f
        val bodyH = h * 0.32f
        val bw = 2.6f
        // body box
        lines.line(x - bw, hip, z, x + bw, hip, z, r, g, b, 0.95f)
        lines.line(x - bw, hip + bodyH, z, x + bw, hip + bodyH, z, r, g, b, 0.95f)
        lines.line(x - bw, hip, z, x - bw, hip + bodyH, z, r, g, b, 0.95f)
        lines.line(x + bw, hip, z, x + bw, hip + bodyH, z, r, g, b, 0.95f)
        // neck + head (the target)
        val hy = base + h - 1.2f
        lines.line(x + bw, hip + bodyH * 0.7f, z, x + bw + 1.3f, hy, z, r, g, b, 0.9f)
        lines.line(x + bw + 1.3f, hy - 0.55f, z, x + bw + 2.6f, hy - 0.55f, z, 1f, 0.8f, 0.3f, 0.95f)
        lines.line(x + bw + 1.3f, hy + 0.55f, z, x + bw + 2.6f, hy + 0.55f, z, 1f, 0.8f, 0.3f, 0.95f)
        lines.line(x + bw + 1.3f, hy - 0.55f, z, x + bw + 1.3f, hy + 0.55f, z, 1f, 0.8f, 0.3f, 0.95f)
        lines.line(x + bw + 2.6f, hy - 0.55f, z, x + bw + 2.6f, hy + 0.55f, z, 1f, 0.8f, 0.3f, 0.95f)
        // four striding legs
        var i = 0
        while (i < 4) {
            val lx = x - bw + (i.toFloat() / 3f) * bw * 2f
            val swing = sin(phase + i * 1.5708f) * 0.7f
            val kx = lx + swing
            val ky = (base + hip) / 2f
            lines.line(lx, hip, z, kx, ky, z, r, g, b, 0.9f)
            lines.line(kx, ky, z, kx + swing * 0.4f, base, z, r, g, b, 0.9f)
            i++
        }
    }

    private fun buildRadar(x: Float, z: Float, h: Float) {
        val r = 0.55f; val g = 0.7f; val b = 0.95f
        val base = -6f
        val top = base + h
        lines.line(x, base, z, x, top, z, r, g, b, 0.9f)
        // the dish: two nested rings + feed spike, the sweep glint
        ring(x, top + 0.9f, z, 1.15f, 10, r, g, b, 0.95f)
        ring(x, top + 0.9f, z, 0.55f, 8, r, g, b, 0.8f)
        lines.line(x, top + 0.9f, z, x + cos(game.time * 3f) * 1.15f, top + 0.9f + sin(game.time * 3f) * 1.15f, z, 1f, 0.9f, 0.4f, 0.8f)
    }

    private fun buildDeckTurret(x: Float, z: Float, h: Float) {
        val r = 0.55f; val g = 0.7f; val b = 0.95f
        val base = -6f
        val top = base + h
        val w = 0.9f
        lines.line(x - w, base, z, x - w, top, z, r, g, b, 0.9f)
        lines.line(x + w, base, z, x + w, top, z, r, g, b, 0.9f)
        lines.line(x - w, top, z, x + w, top, z, r, g, b, 0.9f)
        // twin barrels
        lines.line(x - 0.35f, top, z, x - 0.35f, top + 1.3f, z, 1f, 0.8f, 0.3f, 0.95f)
        lines.line(x + 0.35f, top, z, x + 0.35f, top + 1.3f, z, 1f, 0.8f, 0.3f, 0.95f)
    }

    /** The two-legged forest strider, head at cab height. */
    private fun buildStrider(x: Float, z: Float, phase: Float) {
        val r = 0.8f; val g = 0.75f; val b = 0.5f
        val base = -3.4f
        val hip = base + 1.9f
        val cab = -0.6f
        // legs
        for (s in intArrayOf(-1, 1)) {
            val swing = sin(phase + if (s > 0) 0f else 3.1416f) * 0.4f
            lines.line(x + s * 0.5f, hip, z, x + s * 0.9f + swing, base, z, r, g, b, 0.9f)
        }
        lines.line(x - 0.5f, hip, z, x + 0.5f, hip, z, r, g, b, 0.9f)
        // cab box
        lines.line(x - 0.8f, hip, z, x - 0.8f, cab + 0.6f, z, r, g, b, 0.95f)
        lines.line(x + 0.8f, hip, z, x + 0.8f, cab + 0.6f, z, r, g, b, 0.95f)
        lines.line(x - 0.8f, cab + 0.6f, z, x + 0.8f, cab + 0.6f, z, r, g, b, 0.95f)
        lines.line(x - 0.5f, cab - 0.2f, z, x + 0.5f, cab - 0.2f, z, 1f, 0.8f, 0.3f, 0.9f)
    }

    // -------------------------------------------------------- run scenes

    private fun buildTunnel(hw: Float, fl: Float, top: Float, r: Float, g: Float, b: Float, ceiling: Boolean) {
        val scroll = (game.time * game.worldSpeed) % 8f
        for (x in floatArrayOf(-hw, hw)) {
            lines.line(x, fl, -330f, x, fl, -1f, r, g, b, 0.55f)
            lines.line(x, top, -330f, x, top, -1f, r, g, b, 0.55f)
            lines.line(x, (fl + top) / 2f, -330f, x, (fl + top) / 2f, -1f, r, g, b, 0.2f)
        }
        lines.line(0f, fl, -330f, 0f, fl, -1f, r, g, b, 0.18f)
        if (ceiling) lines.line(0f, top, -330f, 0f, top, -1f, r, g, b, 0.18f)
        var i = 0
        while (i < 42) {
            val z = -(i * 8f - scroll)
            if (z < -1.5f) {
                val a = 0.4f
                lines.line(-hw, fl, z, hw, fl, z, r, g, b, a)
                lines.line(-hw, fl, z, -hw, top, z, r, g, b, a)
                lines.line(hw, fl, z, hw, top, z, r, g, b, a)
                if (ceiling) lines.line(-hw, top, z, hw, top, z, r, g, b, a)
                else {
                    lines.line(-hw, top, z, -hw - 2.2f, top, z, r, g, b, a * 0.7f)
                    lines.line(hw, top, z, hw + 2.2f, top, z, r, g, b, a * 0.7f)
                }
            }
            i++
        }
    }

    /** The forest floor and passing canopy for the speeder run. */
    private fun buildForest() {
        val fl = Game.TRENCH_FLOOR
        val scroll = (game.time * game.worldSpeed) % 8f
        val gr = 0.35f; val gg = 0.9f; val gb = 0.4f
        // ground
        var i = 0
        while (i < 42) {
            val z = -(i * 8f - scroll)
            if (z < -1f) lines.line(-30f, fl, z, 30f, fl, z, gr, gg, gb, 0.22f)
            i++
        }
        lines.line(-160f, fl, -330f, 160f, fl, -330f, gr, gg, gb, 0.4f)
        // flanking treeline: trunks at pseudo-random offsets, canopy strokes
        val tr = 0.8f; val tg = 0.6f; val tb = 0.3f
        i = 0
        while (i < 26) {
            val z = -(i * 13f - (game.time * game.worldSpeed) % 13f)
            if (z < -2f) {
                val h1 = 6f + ((i * 37) % 5)
                val off = ((i * 53) % 7) * 0.6f
                for (s in intArrayOf(-1, 1)) {
                    val x = s * (8f + off)
                    lines.line(x, fl, z, x, fl + h1, z, tr, tg, tb, 0.6f)
                    lines.line(x - 1.2f, fl + h1, z, x + 1.2f, fl + h1 * 0.82f, z, gr, gg, gb, 0.5f)
                    lines.line(x + 1.2f, fl + h1, z, x - 1.2f, fl + h1 * 0.82f, z, gr, gg, gb, 0.5f)
                }
            }
            i++
        }
    }

    private fun buildBarriers() {
        for (bar in game.barriers) {
            if (!bar.alive || bar.z > -1.5f) continue
            when (bar.kind) {
                1 -> buildTreeGate(bar)
                2 -> buildPipeGate(bar)
                3 -> buildBranchWall(bar)
                4 -> buildShelf(bar)
                else -> buildLatticeGate(bar)
            }
        }
    }

    private fun buildLatticeGate(bar: com.x3wars.engine.Barrier) {
        val hw = Game.TRENCH_HALF_W
        val fl = Game.TRENCH_FLOOR
        val top = Game.TRENCH_TOP
        val z = bar.z
        val gxl = bar.gapX - bar.gapW / 2f
        val gr = bar.gapX + bar.gapW / 2f
        val gb = bar.gapY - bar.gapH / 2f
        val gt = bar.gapY + bar.gapH / 2f
        val r = 1f; val g = 0.75f; val b = 0.25f; val a = 0.85f
        var x = -hw
        while (x <= hw + 0.01f) {
            if (x < gxl || x > gr) lines.line(x, fl, z, x, top, z, r, g, b, a * 0.55f)
            else {
                lines.line(x, fl, z, x, gb, z, r, g, b, a * 0.55f)
                lines.line(x, gt, z, x, top, z, r, g, b, a * 0.55f)
            }
            x += 1.15f
        }
        lines.line(-hw, fl, z, hw, fl, z, r, g, b, a)
        lines.line(-hw, top, z, hw, top, z, r, g, b, a)
        lines.line(gxl, gb, z, gr, gb, z, 0.4f, 1f, 0.6f, a)
        lines.line(gxl, gt, z, gr, gt, z, 0.4f, 1f, 0.6f, a)
        lines.line(gxl, gb, z, gxl, gt, z, 0.4f, 1f, 0.6f, a)
        lines.line(gr, gb, z, gr, gt, z, 0.4f, 1f, 0.6f, a)
    }

    /** Two great trunks with the safe gap between them. */
    private fun buildTreeGate(bar: com.x3wars.engine.Barrier) {
        val fl = Game.TRENCH_FLOOR
        val z = bar.z
        val gxl = bar.gapX - bar.gapW / 2f
        val gr = bar.gapX + bar.gapW / 2f
        val tr = 0.85f; val tg = 0.62f; val tb = 0.3f
        for (x in floatArrayOf(gxl - 0.4f, gr + 0.4f)) {
            lines.line(x, fl, z, x, fl + 9f, z, tr, tg, tb, 0.95f)
            lines.line(x - 0.35f, fl, z, x - 0.35f, fl + 9f, z, tr, tg, tb, 0.5f)
            lines.line(x + 0.35f, fl, z, x + 0.35f, fl + 9f, z, tr, tg, tb, 0.5f)
            // canopy
            lines.line(x - 1.6f, fl + 9f, z, x + 1.6f, fl + 9f, z, 0.35f, 0.9f, 0.4f, 0.6f)
        }
        // gap marker on the ground
        lines.line(gxl, fl + 0.05f, z, gr, fl + 0.05f, z, 0.4f, 1f, 0.6f, 0.8f)
    }

    /** A solid dividing wall with one whole side open — the maze's S-turns. */
    private fun buildBranchWall(bar: com.x3wars.engine.Barrier) {
        val hw = Game.CORE_HALF_W
        val fl = Game.CORE_FLOOR
        val top = Game.CORE_TOP
        val z = bar.z
        val gxl = bar.gapX - bar.gapW / 2f
        val gr = bar.gapX + bar.gapW / 2f
        val r = 0.95f; val g = 0.45f; val b = 0.35f; val a = 0.9f
        var x = -hw
        while (x <= hw + 0.01f) {
            if (x < gxl || x > gr) {
                lines.line(x, fl, z, x, top, z, r, g, b, a * 0.6f)
                lines.line(x, fl, z, x + 0.45f, top, z, r, g, b, a * 0.25f)  // cross-brace shimmer
            }
            x += 0.75f
        }
        lines.line(-hw, fl, z, hw, fl, z, r, g, b, a)
        lines.line(-hw, top, z, hw, top, z, r, g, b, a)
        // the open passage, outlined in safe teal
        val ox = if (bar.gapX > 0f) gxl else gr
        lines.line(ox, fl, z, ox, top, z, 0.3f, 0.9f, 0.85f, a)
    }

    /** A horizontal shelf: crawl under it or climb over it. */
    private fun buildShelf(bar: com.x3wars.engine.Barrier) {
        val hw = Game.CORE_HALF_W
        val fl = Game.CORE_FLOOR
        val top = Game.CORE_TOP
        val z = bar.z
        val gb = bar.gapY - bar.gapH / 2f
        val gt = bar.gapY + bar.gapH / 2f
        val r = 0.95f; val g = 0.45f; val b = 0.35f; val a = 0.9f
        var y = fl
        while (y <= top + 0.01f) {
            if (y < gb || y > gt) lines.line(-hw, y, z, hw, y, z, r, g, b, a * 0.6f)
            y += 0.65f
        }
        lines.line(-hw, fl, z, -hw, top, z, r, g, b, a)
        lines.line(hw, fl, z, hw, top, z, r, g, b, a)
        // safe band outline
        lines.line(-hw, gb, z, hw, gb, z, 0.3f, 0.9f, 0.85f, a)
        lines.line(-hw, gt, z, hw, gt, z, 0.3f, 0.9f, 0.85f, a)
    }

    /** A conduit ring blocking the duct except for its gap. */
    private fun buildPipeGate(bar: com.x3wars.engine.Barrier) {
        val hw = Game.CORE_HALF_W
        val fl = Game.CORE_FLOOR
        val top = Game.CORE_TOP
        val z = bar.z
        val gxl = bar.gapX - bar.gapW / 2f
        val gr = bar.gapX + bar.gapW / 2f
        val gb = bar.gapY - bar.gapH / 2f
        val gt = bar.gapY + bar.gapH / 2f
        val r = 0.95f; val g = 0.45f; val b = 0.35f; val a = 0.9f
        // horizontal conduits above and below the gap
        var y = fl
        while (y <= top + 0.01f) {
            if (y < gb || y > gt) lines.line(-hw, y, z, hw, y, z, r, g, b, a * 0.5f)
            else {
                lines.line(-hw, y, z, gxl, y, z, r, g, b, a * 0.5f)
                lines.line(gr, y, z, hw, y, z, r, g, b, a * 0.5f)
            }
            y += 0.9f
        }
        lines.line(gxl, gb, z, gr, gb, z, 0.3f, 0.9f, 0.85f, a)
        lines.line(gxl, gt, z, gr, gt, z, 0.3f, 0.9f, 0.85f, a)
        lines.line(gxl, gb, z, gxl, gt, z, 0.3f, 0.9f, 0.85f, a)
        lines.line(gr, gb, z, gr, gt, z, 0.3f, 0.9f, 0.85f, a)
    }

    // ------------------------------------------------------------- ports

    private fun buildExhaustPort() {
        val z = game.portZ
        if (z > -2f) return
        val fl = Game.TRENCH_FLOOR
        val pulse = 0.6f + 0.4f * sin(game.time * 9f)
        val s = 1.6f
        lines.line(-s, fl + 0.02f, z - s, s, fl + 0.02f, z - s, 0.5f, 0.9f, 1f, pulse)
        lines.line(-s, fl + 0.02f, z + s, s, fl + 0.02f, z + s, 0.5f, 0.9f, 1f, pulse)
        lines.line(-s, fl + 0.02f, z - s, -s, fl + 0.02f, z + s, 0.5f, 0.9f, 1f, pulse)
        lines.line(s, fl + 0.02f, z - s, s, fl + 0.02f, z + s, 0.5f, 0.9f, 1f, pulse)
        ringFlat(0f, fl + 0.03f, z, 0.9f, 8, 0.6f, 0.95f, 1f, pulse)
        fx.v(0f, fl + 0.05f, z, 0.7f, 1f, 1f, pulse)
    }

    /** The shield generator: a bunker crowned with a great dish. */
    private fun buildGenerator() {
        val z = game.portZ
        if (z > -2f) return
        val fl = Game.TRENCH_FLOOR
        val pulse = 0.6f + 0.4f * sin(game.time * 6f)
        val r = 0.55f; val g = 0.9f; val b = 1f
        // bunker slab
        lines.line(-4f, fl, z, 4f, fl, z, r, g, b, 0.9f)
        lines.line(-4f, fl, z, -3f, fl + 2.2f, z, r, g, b, 0.9f)
        lines.line(4f, fl, z, 3f, fl + 2.2f, z, r, g, b, 0.9f)
        lines.line(-3f, fl + 2.2f, z, 3f, fl + 2.2f, z, r, g, b, 0.9f)
        // the dish
        ring(0f, fl + 4.4f, z, 2.4f, 12, r, g, b, pulse)
        ring(0f, fl + 4.4f, z, 1.1f, 8, r, g, b, pulse * 0.8f)
        lines.line(0f, fl + 2.2f, z, 0f, fl + 4.4f, z, r, g, b, 0.9f)
        fx.v(0f, fl + 4.4f, z, 0.8f, 1f, 1f, pulse)
    }

    /** The reactor heart at the end of the core run. */
    private fun buildCoreHeart() {
        val z = game.portZ
        if (z > -2f) return
        val pulse = 0.5f + 0.5f * sin(game.time * 7f)
        val r = 1f; val g = 0.5f; val b = 0.35f
        ring(0f, 0f, z, 2.2f, 8, r, g, b, 0.9f)
        ring(0f, 0f, z, 1.4f, 6, 0.3f, 0.9f, 0.85f, pulse)
        // crackling spokes
        var i = 0
        while (i < 4) {
            val an = i / 4f * 6.2832f + game.time * 2f
            lines.line(0f, 0f, z, cos(an) * 2.2f, sin(an) * 2.2f, z, r, g, b, pulse * 0.8f)
            i++
        }
        fx.v(0f, 0f, z, 1f, 0.8f, 0.6f, pulse)
    }

    private fun ringFlat(x: Float, y: Float, z: Float, rad: Float, segs: Int, r: Float, g: Float, b: Float, a: Float) {
        var px = x + rad; var pz = z
        for (k in 1..segs) {
            val an = k.toFloat() / segs * 6.2832f
            val nx = x + cos(an) * rad
            val nz = z + sin(an) * rad
            lines.line(px, y, pz, nx, y, nz, r, g, b, a)
            px = nx; pz = nz
        }
    }

    private fun buildTorpedoes() {
        val t = game.torpedoT.coerceIn(0f, 1f)
        val sx = game.shipX(); val sy = game.shipY()
        val fl = Game.TRENCH_FLOOR
        val z0 = -4f
        val z1 = game.portZ
        // The generator and core sit at mid-height; the exhaust port in the floor.
        val targetY = when (game.portKind) {
            Game.PORT_GENERATOR -> fl + 4.4f
            Game.PORT_CORE -> 0f
            else -> fl
        }
        for (side in intArrayOf(-1, 1)) {
            val x0 = sx + side * 1.4f
            val y0 = sy - 1f
            val x = x0 + (0f - x0) * t
            val y = y0 + (targetY - y0) * (t * t)
            val z = z0 + (z1 - z0) * t
            fx.v(x, y, z, 0.5f, 0.95f, 1f, 1f)
            ring(x, y, z, 0.3f, 6, 0.5f, 0.95f, 1f, 0.9f)
            val tx = x0 + (0f - x0) * (t * 0.82f)
            val ty = y0 + (targetY - y0) * (t * 0.82f) * (t * 0.82f)
            val tz = z0 + (z1 - z0) * (t * 0.82f)
            lines.line(x, y, z, tx, ty, tz, 0.4f, 0.85f, 1f, 0.7f)
        }
    }

    // ------------------------------------------------------------ victory

    private fun buildVictory() {
        buildStars(0.6f)
        val t = game.stateT
        val cx = 0f; val cy = 2f; val cz = -120f
        when (game.victoryKind) {
            1 -> {   // the destroyer breaks amidships
                if (t < 2.2f) {
                    val jit = t * 1.2f
                    val a = 1f - (t / 2.2f) * 0.4f
                    buildWreckWedge(cx, cy, cz, jit, a)
                }
            }
            else -> {
                if (t < 2.2f) {
                    val expand = 1f + t * 0.55f
                    val jit = t * 0.9f
                    val white = (t / 2.2f).coerceIn(0f, 1f)
                    buildStation(cx, cy, cz, 40f * expand, game.time * 9f, 1f - white * 0.4f, jit,
                        unfinished = game.victoryKind == 2)
                }
            }
        }
        if (t > 0.35f) {
            val rw = (t - 0.35f) * 95f
            val a = (1f - (t - 0.35f) / 4.2f).coerceIn(0f, 1f)
            ring(cx, cy, cz, rw, 40, 0.65f, 0.9f, 1f, a * 0.9f)
            ring(cx, cy, cz, rw * 0.86f, 40, 1f, 0.75f, 0.4f, a * 0.6f)
        }
    }

    private fun buildWreckWedge(cx: Float, cy: Float, cz: Float, jit: Float, a: Float) {
        val w = 46f; val l = 70f
        val r = 0.65f; val g = 0.75f; val b = 0.95f
        fun j() = (rnd.nextFloat() - 0.5f) * jit * 6f
        lines.line(cx + j(), cy + j(), cz, cx - w + j(), cy + 7f + j(), cz - l, r, g, b, a)
        lines.line(cx + j(), cy + j(), cz, cx + w + j(), cy + 7f + j(), cz - l, r, g, b, a)
        lines.line(cx - w + j(), cy + 7f + j(), cz - l, cx + w + j(), cy + 7f + j(), cz - l, r, g, b, a * 0.8f)
        lines.line(cx + j(), cy + j(), cz, cx + j(), cy + 12f + j(), cz - l * 0.75f, r, g, b, a * 0.7f)
    }

    private fun buildStation(cx: Float, cy: Float, cz: Float, rad: Float, spin: Float, alpha: Float,
                             jitter: Float = 0f, unfinished: Boolean = false) {
        val r = 0.45f; val g = 0.95f; val b = 0.6f
        val segs = 20
        var lat = -2
        while (lat <= 2) {
            val phi = lat * 0.5f
            val y = cy + rad * sin(phi)
            val rr = rad * cos(phi)
            var px = 0f; var pz = 0f; var first = true
            for (k in 0..segs) {
                // The half-built station: a bite missing from one flank.
                if (unfinished && lat >= 0 && k > segs * 0.62f && k < segs * 0.88f) { first = true; continue }
                val an = k.toFloat() / segs * 6.2832f + spin * 0.008f
                val jx = if (jitter > 0f) (rnd.nextFloat() - 0.5f) * jitter else 0f
                val jy = if (jitter > 0f) (rnd.nextFloat() - 0.5f) * jitter else 0f
                val nx = cx + cos(an) * rr + jx
                val nz = cz + sin(an) * rr * 0.35f + jy
                if (!first) lines.line(px, y, pz, nx, y, nz, r, g, b, alpha * 0.6f)
                px = nx; pz = nz; first = false
            }
            lat++
        }
        var m = 0
        while (m < 7) {
            val am = m.toFloat() / 6f * 3.1416f - 1.5708f + spin * 0.004f
            var py = 0f; var px = 0f; var first = true
            for (k in 0..segs) {
                val phi = (k.toFloat() / segs - 0.5f) * 3.1416f
                val jx = if (jitter > 0f) (rnd.nextFloat() - 0.5f) * jitter else 0f
                val y = cy + rad * sin(phi) + jx
                val x = cx + rad * cos(phi) * sin(am)
                if (!first) lines.line(px, py, cz, x, y, cz, r, g, b, alpha * 0.55f)
                px = x; py = y; first = false
            }
            m++
        }
        if (unfinished) {
            // exposed skeletal ribs on the missing flank
            var i = 0
            while (i < 4) {
                val an = (0.65f + i * 0.07f) * 6.2832f
                lines.line(cx + cos(an) * rad * 0.5f, cy + rad * 0.15f, cz,
                    cx + cos(an) * rad, cy + rad * (0.3f + i * 0.1f), cz, r, g, b, alpha * 0.5f)
                i++
            }
        }
        ring(cx - rad * 0.42f, cy + rad * 0.38f, cz - 1f, rad * 0.2f, 12, r, g, b, alpha)
        ring(cx - rad * 0.42f, cy + rad * 0.38f, cz - 1f, rad * 0.09f, 8, r, g, b, alpha * 0.8f)
        lines.line(cx - rad, cy, cz, cx + rad, cy, cz, r, g, b, alpha * 0.9f)
    }

    private fun buildParticles() {
        for (p in game.particles) {
            if (!p.alive) continue
            val k = (p.life / p.maxLife).coerceIn(0f, 1f)
            hsv(p.hue, 1f - k * 0.3f, 1f)
            fx.v(p.x, p.y, p.z, rgb[0], rgb[1], rgb[2], k)
        }
    }

    // --------------------------------------------------------------- HUD

    private val sink = object : StrokeFont.LineSink {
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float) {
            hud.line(x0, y0, 0f, x1, y1, 0f, tr, tg, tb, ta)
        }
    }
    private var tr = 0f; private var tg = 1f; private var tb = 0.5f; private var ta = 1f

    private fun text(s: String, x: Float, y: Float, sc: Float, r: Float, g: Float, b: Float, a: Float = 1f) {
        tr = r; tg = g; tb = b; ta = a
        StrokeFont.draw(s, x, y, sc, sink)
    }

    private fun textC(s: String, cx: Float, y: Float, sc: Float, r: Float, g: Float, b: Float, a: Float = 1f) =
        text(s, cx - StrokeFont.width(s, sc) / 2f, y, sc, r, g, b, a)

    private fun buildHud() {
        hud.reset()
        val g = game

        if (g.hitFlash > 0.01f) frame(6f, 1f, 0.2f, 0.15f, g.hitFlash * 0.9f)
        if (g.r2FlashT > 0.01f) {
            val a = g.r2FlashT.coerceAtMost(1f)
            textC("R2 RESTORES SHIELDS +2", 320f, 210f, 1.7f, 0.4f, 1f, 0.9f, a)
        }
        if (g.whiteFlash > 0.01f) {
            var i = 0
            while (i < 16) {
                val y = 15f + i * 30f
                hud.line(0f, y, 0f, 640f, y, 0f, 1f, 1f, 1f, g.whiteFlash * 0.8f)
                i++
            }
        }

        when (g.state) {
            GameState.TITLE -> {
                textC("X3WARS", 320f, 150f, 6f, 0.45f, 1f, 0.6f)
                textC("YAVIN - HOTH - ENDOR", 320f, 195f, 1.6f, 0.6f, 0.85f, 1f)
                textC("SWIPE TO AIM - CANNONS AUTO-FIRE", 320f, 300f, 1.3f, 0.75f, 0.8f, 0.9f)
                textC("TAP FOR TORPEDOES AT EACH BATTLES HEART", 320f, 322f, 1.3f, 0.75f, 0.8f, 0.9f)
                val blink = 0.5f + 0.5f * sin(g.time * 5f)
                textC("TAP TO LAUNCH", 320f, 400f, 2.2f, 0.45f, 1f, 0.6f, blink)
                textC("HIGH " + g.hiScore, 320f, 435f, 1.3f, 1f, 0.85f, 0.4f)
            }
            GameState.BRIEFING -> {
                textC(g.briefTitle, 320f, 210f, 4f, 0.45f, 1f, 0.6f)
                textC(g.briefSub, 320f, 250f, 1.7f, 1f, 0.85f, 0.4f)
                hudCommon()
            }
            GameState.FIGHTERS -> { sceneHeader("INTERCEPTORS"); killsLine(); hudCommon() }
            GameState.DROIDS -> { sceneHeader("HUNT THE PROBES"); killsLine(); hudCommon() }
            GameState.WALKERS -> { sceneHeader("THE WALKERS"); killsLine(); hudCommon() }
            GameState.FLEET -> {
                sceneHeader(if (g.fleetMega) "THE FLEET" else "THE FLEET ABOVE")
                killsLine(); hudCommon()
            }
            GameState.DECK -> { sceneHeader("THE DESTROYER RUN"); killsLine(); hudCommon() }
            GameState.SURFACE -> {
                sceneHeader("THE SURFACE")
                text("SWEEP " + g.surfaceT.toInt(), 16f, 452f, 1.4f, 0.6f, 0.85f, 1f)
                hudCommon()
            }
            GameState.TRENCH -> {
                sceneHeader("THE TRENCH")
                rangeLine()
                centerCross(); hudCommon()
            }
            GameState.BIKES -> {
                sceneHeader("THE FOREST RUN")
                rangeLine()
                centerCross(); hudCommon()
            }
            GameState.CORE -> {
                sceneHeader("INTO THE CORE")
                rangeLine()
                centerCross(); hudCommon()
            }
            GameState.PORT -> {
                centerCross()
                buildTargeting()
                hudCommon()
            }
            GameState.DOCK -> {
                buildCrawl()
                // shield pips refill one by one down in the corner
                val pips = (2f + g.stateT * 1.2f).toInt().coerceAtMost(8)
                var i = 0
                while (i < pips) {
                    val x = 16f + i * 16f
                    hud.line(x, 470f, 0f, x + 10f, 470f, 0f, 0.45f, 1f, 0.6f, 0.95f)
                    hud.line(x, 465f, 0f, x, 475f, 0f, 0.45f, 1f, 0.6f, 0.95f)
                    i++
                }
                text("DOCKED - REPAIRS UNDERWAY", 16f, 452f, 1.2f, 0.5f, 0.85f, 1f, 0.8f)
                if (g.stateT > 5f) {
                    val blink = 0.4f + 0.3f * sin(g.time * 4f)
                    textC("TAP TO LAUNCH", 320f, 452f, 1.2f, 0.45f, 1f, 0.6f, blink)
                }
            }
            GameState.MINIWIN -> {
                textC("THE SHIELD IS DOWN", 320f, 210f, 2.6f, 1f, 0.9f, 0.5f)
                textC("+10000", 320f, 248f, 1.7f, 0.45f, 1f, 0.6f)
                hudCommon()
            }
            GameState.VICTORY -> {
                if (g.stateT > 1.2f) {
                    val line1 = when (g.victoryKind) {
                        1 -> "THE DESTROYER"
                        2 -> "THE NEW STATION"
                        else -> "THE BATTLE STATION"
                    }
                    val line2 = when (g.victoryKind) { 1 -> "IS DOWN"; else -> "IS DESTROYED" }
                    textC(line1, 320f, 190f, 2.6f, 1f, 0.9f, 0.5f)
                    textC(line2, 320f, 225f, 2.6f, 1f, 0.9f, 0.5f)
                    textC("+25000", 320f, 265f, 1.7f, 0.45f, 1f, 0.6f)
                }
                hudCommon()
            }
            GameState.GAMEOVER -> {
                textC("SHIP LOST", 320f, 200f, 3.6f, 1f, 0.5f, 0.35f)
                textC("SCORE " + g.score, 320f, 245f, 1.9f, 0.6f, 0.85f, 1f)
                textC("HIGH " + g.hiScore, 320f, 272f, 1.4f, 1f, 0.85f, 0.4f)
                if (g.stateT > 1.2f) {
                    val blink = 0.5f + 0.5f * sin(g.time * 5f)
                    textC("TAP TO FLY AGAIN", 320f, 380f, 1.8f, 0.45f, 1f, 0.6f, blink)
                }
            }
        }
    }

    private fun sceneHeader(s: String) {
        if (game.stateT < 2.2f) textC(s, 320f, 150f, 2.4f, 0.45f, 1f, 0.6f, 1f - game.stateT / 2.4f)
        // The required objective lingers a little longer as a mission order.
        if (game.stateT < 5f && game.objective.isNotEmpty()) {
            val a = (1f - (game.stateT - 3f) / 2f).coerceIn(0f, 1f)
            textC("OBJECTIVE: " + game.objective, 320f, 182f, 1.4f, 1f, 0.85f, 0.4f, a)
        }
    }

    private fun killsLine() =
        text("KILLS " + game.kills + "/" + game.killQuota, 16f, 452f, 1.4f, 0.6f, 0.85f, 1f)

    private fun rangeLine() =
        textC("RANGE " + game.rangeM.toInt().coerceAtLeast(0), 320f, 452f, 1.7f, 1f, 0.85f, 0.4f)

    private fun hudCommon() {
        val g = game
        text("SCORE " + g.score, 16f, 28f, 1.4f, 0.6f, 0.85f, 1f)
        val partLabel = if (g.part == 1) levelTag() else "P" + g.part + " " + levelTag()
        text(partLabel, 640f - 16f - StrokeFont.width(partLabel, 1.4f), 28f, 1.4f, 0.6f, 0.85f, 1f)
        var i = 0
        while (i < g.shields) {
            val x = 16f + i * 16f
            hud.line(x, 470f, 0f, x + 10f, 470f, 0f, 0.45f, 1f, 0.6f, 0.95f)
            hud.line(x, 465f, 0f, x, 475f, 0f, 0.45f, 1f, 0.6f, 0.95f)
            i++
        }
    }

    private fun levelTag() = when (game.level) {
        Level.YAVIN -> "YAVIN"
        Level.HOTH -> "HOTH"
        Level.ENDOR -> "ENDOR"
    }

    private fun centerCross() {
        val r = 0.45f; val g2 = 1f; val b = 0.6f
        hud.line(300f, 240f, 0f, 314f, 240f, 0f, r, g2, b, 0.9f)
        hud.line(326f, 240f, 0f, 340f, 240f, 0f, r, g2, b, 0.9f)
        hud.line(320f, 224f, 0f, 320f, 234f, 0f, r, g2, b, 0.9f)
        hud.line(320f, 246f, 0f, 320f, 256f, 0f, r, g2, b, 0.9f)
    }

    /**
     * The story crawl: golden lines rising from the canopy toward the stars,
     * shrinking and fading as they climb — one new line about every two
     * seconds, sized to stay readable through the waveguide.
     */
    private fun buildCrawl() {
        val g = game
        val progress = (g.stateT - 1.5f) * 0.09f   // ~11 s bottom-to-top per line
        drawCrawlLine(g.crawlTitle, progress + 0.09f, 3.0f, 1f, 0.8f, 0.25f)
        var i = 0
        while (i < g.crawlLines.size) {
            drawCrawlLine(g.crawlLines[i], progress - (i + 1) * 0.09f, 2.1f, 1f, 0.85f, 0.35f)
            i++
        }
    }

    /** One crawl line at param t: 0 = entering low and large, 1 = far and gone. */
    private fun drawCrawlLine(s: String, t: Float, baseScale: Float, r: Float, g2: Float, b: Float) {
        if (s.isEmpty() || t < 0f || t > 1f) return
        val y = 430f - t * 350f
        val sc = baseScale * (1f - t * 0.72f)
        val a = when {
            t < 0.06f -> t / 0.06f
            t > 0.82f -> (1f - t) / 0.18f
            else -> 1f
        }
        textC(s, 320f, y, sc, r, g2, b, a)
    }

    private fun buildTargeting() {
        val g = game
        if (!g.targetingOff) {
            val close = ((-g.portZ - 18f) / 280f).coerceIn(0f, 1f)
            val s = 40f + close * 150f
            val r = 1f; val gg = 0.75f; val b = 0.25f
            bracket(320f - s, 240f - s * 0.7f, 14f, 1f, r, gg, b)
            bracket(320f + s, 240f - s * 0.7f, 14f, -1f, r, gg, b)
            bracket(320f - s, 240f + s * 0.7f, 14f, 1f, r, gg, b, true)
            bracket(320f + s, 240f + s * 0.7f, 14f, -1f, r, gg, b, true)
            textC("TARGETING " + (-g.portZ).toInt().coerceAtLeast(0), 320f, 420f, 1.4f, r, gg, b)
        } else {
            val flick = sin(g.time * 37f) * sin(g.time * 11f)
            if (g.stateT < 3.6f && flick > 0.4f) {
                textC("TARGETING OFF", 320f, 420f, 1.4f, 1f, 0.4f, 0.25f, 0.5f)
            }
            val inWindow = g.portZ > -95f && g.portZ < -18f
            if (inWindow && g.torpedoT < 0f) {
                val blink = 0.5f + 0.5f * sin(g.time * 8f)
                textC("TRUST YOURSELF - TAP", 320f, 300f, 2f, 0.5f, 0.95f, 1f, blink)
            }
        }
    }

    private fun bracket(x: Float, y: Float, s: Float, dir: Float, r: Float, g: Float, b: Float, bottom: Boolean = false) {
        val vy = if (bottom) -s else s
        hud.line(x, y, 0f, x + s * dir, y, 0f, r, g, b, 0.9f)
        hud.line(x, y, 0f, x, y + vy, 0f, r, g, b, 0.9f)
    }

    private fun frame(w: Float, r: Float, g: Float, b: Float, a: Float) {
        hud.line(w, w, 0f, 640f - w, w, 0f, r, g, b, a)
        hud.line(w, 480f - w, 0f, 640f - w, 480f - w, 0f, r, g, b, a)
        hud.line(w, w, 0f, w, 480f - w, 0f, r, g, b, a)
        hud.line(640f - w, w, 0f, 640f - w, 480f - w, 0f, r, g, b, a)
    }

    // ------------------------------------------------------------ plumbing

    private fun hsv(h: Float, s: Float, v: Float) {
        val i = (h * 6f).toInt() % 6
        val f = h * 6f - (h * 6f).toInt()
        val p = v * (1f - s); val q = v * (1f - f * s); val t = v * (1f - (1f - f) * s)
        when (if (i < 0) i + 6 else i) {
            0 -> { rgb[0] = v; rgb[1] = t; rgb[2] = p }
            1 -> { rgb[0] = q; rgb[1] = v; rgb[2] = p }
            2 -> { rgb[0] = p; rgb[1] = v; rgb[2] = t }
            3 -> { rgb[0] = p; rgb[1] = q; rgb[2] = v }
            4 -> { rgb[0] = t; rgb[1] = p; rgb[2] = v }
            else -> { rgb[0] = v; rgb[1] = p; rgb[2] = q }
        }
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun sh(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, sh(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, sh(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        return p
    }

    inner class Batch(maxVerts: Int) {
        private val fb: FloatBuffer =
            ByteBuffer.allocateDirect(maxVerts * 7 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        private val cap = maxVerts
        var count = 0; private set
        fun reset() { fb.position(0); count = 0 }
        fun v(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float, a: Float) {
            if (count >= cap) return
            fb.put(x); fb.put(y); fb.put(z); fb.put(r); fb.put(g); fb.put(b); fb.put(a); count++
        }
        fun line(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, r: Float, g: Float, b: Float, a: Float) {
            v(x0, y0, z0, r, g, b, a); v(x1, y1, z1, r, g, b, a)
        }
        fun draw(mode: Int) {
            if (count == 0) return
            fb.position(0); GLES30.glVertexAttribPointer(aPos, 3, GLES30.GL_FLOAT, false, 28, fb); GLES30.glEnableVertexAttribArray(aPos)
            fb.position(3); GLES30.glVertexAttribPointer(aColor, 4, GLES30.GL_FLOAT, false, 28, fb); GLES30.glEnableVertexAttribArray(aColor)
            GLES30.glDrawArrays(mode, 0, count)
        }
    }

    companion object {
        private const val VERT = """#version 300 es
        in vec3 aPos; in vec4 aColor; uniform mat4 uMVP; uniform float uPointSize; out vec4 vColor;
        void main() { gl_Position = uMVP * vec4(aPos, 1.0); gl_PointSize = uPointSize; vColor = aColor; }"""
        private const val FRAG = """#version 300 es
        precision mediump float; in vec4 vColor; uniform float uPoint; out vec4 fragColor;
        void main() {
            if (uPoint > 0.5) { vec2 d = gl_PointCoord - vec2(0.5); float r2 = dot(d, d); if (r2 > 0.25) discard; fragColor = vec4(vColor.rgb, vColor.a * (1.0 - r2 * 4.0)); }
            else { fragColor = vColor; }
        }"""
    }
}
