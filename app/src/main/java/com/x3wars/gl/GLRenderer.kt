package com.x3wars.gl

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.x3wars.engine.Game
import com.x3wars.engine.GameState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * First-person vector renderer for X3Wars: additive glowing wireframes on
 * black (transparent on the waveguide), a perspective camera at the origin,
 * and a 640x480 ortho HUD in stroke font — the 1983 color-vector look,
 * reborn per eye on the X3's side-by-side viewports.
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

    private val lines = Batch(30000)
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

        // Camera: at the origin (the cockpit), banking with the stick in the
        // trench, kicked by hits. In the trench the ship position IS the camera.
        val inTrench = game.state == GameState.TRENCH || game.state == GameState.PORT
        val bank = if (inTrench) -game.rx * 10f else -game.rx * 4f
        val shX = (rnd.nextFloat() - 0.5f) * 0.5f * game.shake
        val shY = (rnd.nextFloat() - 0.5f) * 0.5f * game.shake
        Matrix.setIdentityM(view, 0)
        Matrix.rotateM(view, 0, bank, 0f, 0f, 1f)
        val camX = (if (inTrench) game.shipX() else 0f) + shX
        val camY = (if (inTrench) game.shipY() else 0f) + shY
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

    // ------------------------------------------------------------- scene

    private fun buildScene() {
        lines.reset(); fx.reset()
        when (game.state) {
            GameState.TITLE -> { buildStars(0.7f); buildStation(0f, 2f, -190f, 52f, game.time * 4f, 1f) }
            GameState.BRIEFING -> buildStars(1f)
            GameState.FIGHTERS -> {
                buildStars(1f)
                buildFighters(); buildBolts(); buildBeams(); buildAimReticle()
            }
            GameState.SURFACE -> {
                buildStars(0.5f)
                buildSurface(); buildTowers(); buildBolts(); buildBeams(); buildAimReticle()
            }
            GameState.TRENCH -> {
                buildTrench(); buildBarriers(); buildBolts(); buildBeamsCenter()
            }
            GameState.PORT -> {
                buildTrench(); buildPort(); buildBolts()
                if (game.torpedoT >= 0f) buildTorpedoes()
            }
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

    // The classic twin-panel interceptor: two flat hexagonal wings joined by
    // struts to a small round pod. Drawn nose-on, weaving.
    private fun buildFighters(): Unit {
        for (f in game.fighters) {
            if (!f.alive) continue
            val s = 2.2f
            val x = f.x; val y = f.y; val z = f.z
            val r = 0.55f; val g = 0.75f; val b = 1f
            val a = 0.95f
            for (side in intArrayOf(-1, 1)) {
                val px = x + side * s
                // Hex panel in the YZ-ish plane (seen nearly edge-on = a tall slab).
                hexPanel(px, y, z, s * 1.15f, s * 0.42f, r, g, b, a)
                // strut to the pod
                lines.line(px, y, z, x + side * 0.42f * s, y, z, r, g, b, a * 0.8f)
            }
            // Round pod: an octagon facing the player.
            ring(x, y, z, 0.5f * s, 8, r, g, b, a)
            fx.v(x, y, z, 1f, 1f, 1f, 0.5f)
        }
    }

    private fun hexPanel(x: Float, y: Float, z: Float, h: Float, w: Float, r: Float, g: Float, b: Float, a: Float) {
        // Vertical hexagon: top point, two upper corners, two lower, bottom point.
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

    private fun buildBolts() {
        for (b in game.bolts) {
            if (!b.alive) continue
            fx.v(b.x, b.y, b.z, 1f, 0.5f, 0.2f, 1f)
            // a short hot trail
            lines.line(b.x, b.y, b.z, b.x - b.vx * 0.06f, b.y - b.vy * 0.06f, b.z - b.vz * 0.06f,
                1f, 0.35f, 0.12f, 0.8f)
            ring(b.x, b.y, b.z, 0.34f, 6, 1f, 0.4f, 0.15f, 0.9f)
        }
    }

    /** Twin cannon beams converging on the aim point (fighters/surface acts). */
    private fun buildBeams() {
        val t = game.beamT
        if (t >= 0.35f) return
        val a = (1f - t / 0.35f) * 0.9f
        val d = 30f
        val ax = game.beamX * Game.TANX * d
        val ay = game.beamY * Game.TANY * d
        val gx = if (game.beamRight) 5.4f else -5.4f
        lines.line(gx, -4.4f, -2f, ax, ay, -d, 0.4f, 1f, 0.65f, a)
        lines.line(-gx, -4.4f, -2f, ax, ay, -d, 0.4f, 1f, 0.65f, a * 0.55f)
        fx.v(ax, ay, -d, 0.7f, 1f, 0.8f, a)
    }

    /** In the trench the guns fire dead ahead from the ship (the camera). */
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

    /** World-space reticle brackets at the aim point — always exactly aligned. */
    private fun buildAimReticle() {
        val d = 30f
        val x = game.rx * Game.TANX * d
        val y = game.ry * Game.TANY * d
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

    // ------------------------------------------------------- station surface

    private fun buildSurface() {
        val r = 0.25f; val g = 1f; val b = 0.45f
        val y = -6f
        val scroll = (game.time * (90f)) % 8f
        // cross lines racing toward the camera
        var i = 0
        while (i < 42) {
            val z = -(i * 8f - scroll)
            if (z < -1f) lines.line(-44f, y, z, 44f, y, z, r, g, b, 0.30f)
            i++
        }
        // longitudinal rails
        var x = -40f
        while (x <= 40f) {
            lines.line(x, y, -330f, x, y, -1f, r, g, b, 0.22f)
            x += 8f
        }
        // horizon
        lines.line(-160f, y, -330f, 160f, y, -330f, r, g, b, 0.5f)
    }

    private fun buildTowers() {
        val r = 0.3f; val g = 1f; val b = 0.5f
        for (t in game.towers) {
            if (!t.alive) continue
            val base = -6f
            val top = base + t.h
            val w = 1.1f
            for (s in intArrayOf(-1, 1)) {
                lines.line(t.x + s * w, base, t.z, t.x + s * w * 0.7f, top, t.z, r, g, b, 0.9f)
                lines.line(t.x + s * w * 0.7f, top, t.z, t.x + s * w * 0.7f, top + 0.7f, t.z, 1f, 0.8f, 0.3f, 0.95f)
            }
            lines.line(t.x - w, base, t.z, t.x + w, base, t.z, r, g, b, 0.8f)
            lines.line(t.x - w * 0.7f, top, t.z, t.x + w * 0.7f, top, t.z, r, g, b, 0.9f)
            // the turret head
            ring(t.x, top + 0.7f, t.z, 0.55f, 6, 1f, 0.8f, 0.3f, 0.95f)
        }
    }

    // ------------------------------------------------------------- trench

    private fun buildTrench() {
        val r = 0.25f; val g = 1f; val b = 0.45f
        val hw = Game.TRENCH_HALF_W
        val fl = Game.TRENCH_FLOOR
        val top = Game.TRENCH_TOP
        val scroll = (game.time * game.worldSpeed) % 8f
        // long rails: floor edges, wall tops
        for (x in floatArrayOf(-hw, hw)) {
            lines.line(x, fl, -330f, x, fl, -1f, r, g, b, 0.55f)
            lines.line(x, top, -330f, x, top, -1f, r, g, b, 0.55f)
            lines.line(x, (fl + top) / 2f, -330f, x, (fl + top) / 2f, -1f, r, g, b, 0.2f)
        }
        lines.line(0f, fl, -330f, 0f, fl, -1f, r, g, b, 0.18f)
        // ribs racing past
        var i = 0
        while (i < 42) {
            val z = -(i * 8f - scroll)
            if (z < -1.5f) {
                val a = 0.4f
                lines.line(-hw, fl, z, hw, fl, z, r, g, b, a)          // floor tie
                lines.line(-hw, fl, z, -hw, top, z, r, g, b, a)        // left rib
                lines.line(hw, fl, z, hw, top, z, r, g, b, a)          // right rib
                lines.line(-hw, top, z, -hw - 2.2f, top, z, r, g, b, a * 0.7f)  // rim flares
                lines.line(hw, top, z, hw + 2.2f, top, z, r, g, b, a * 0.7f)
            }
            i++
        }
    }

    private fun buildBarriers() {
        for (bar in game.barriers) {
            if (!bar.alive || bar.z > -1.5f) continue
            val hw = Game.TRENCH_HALF_W
            val fl = Game.TRENCH_FLOOR
            val top = Game.TRENCH_TOP
            val z = bar.z
            val gxl = bar.gapX - bar.gapW / 2f
            val gr = bar.gapX + bar.gapW / 2f
            val gb = bar.gapY - bar.gapH / 2f
            val gt = bar.gapY + bar.gapH / 2f
            val r = 1f; val g = 0.75f; val b = 0.25f; val a = 0.85f
            // Lattice everywhere except the gap: verticals
            var x = -hw
            while (x <= hw + 0.01f) {
                if (x < gxl || x > gr) lines.line(x, fl, z, x, top, z, r, g, b, a * 0.55f)
                else {
                    lines.line(x, fl, z, x, gb, z, r, g, b, a * 0.55f)
                    lines.line(x, gt, z, x, top, z, r, g, b, a * 0.55f)
                }
                x += 1.15f
            }
            // frame + gap outline
            lines.line(-hw, fl, z, hw, fl, z, r, g, b, a)
            lines.line(-hw, top, z, hw, top, z, r, g, b, a)
            lines.line(gxl, gb, z, gr, gb, z, 0.4f, 1f, 0.6f, a)
            lines.line(gxl, gt, z, gr, gt, z, 0.4f, 1f, 0.6f, a)
            lines.line(gxl, gb, z, gxl, gt, z, 0.4f, 1f, 0.6f, a)
            lines.line(gr, gb, z, gr, gt, z, 0.4f, 1f, 0.6f, a)
        }
    }

    private fun buildPort() {
        val z = game.portZ
        if (z > -2f) return
        val fl = Game.TRENCH_FLOOR
        val pulse = 0.6f + 0.4f * sin(game.time * 9f)
        // The exhaust port: a glowing square pit in the floor with a core.
        val s = 1.6f
        lines.line(-s, fl + 0.02f, z - s, s, fl + 0.02f, z - s, 0.5f, 0.9f, 1f, pulse)
        lines.line(-s, fl + 0.02f, z + s, s, fl + 0.02f, z + s, 0.5f, 0.9f, 1f, pulse)
        lines.line(-s, fl + 0.02f, z - s, -s, fl + 0.02f, z + s, 0.5f, 0.9f, 1f, pulse)
        lines.line(s, fl + 0.02f, z - s, s, fl + 0.02f, z + s, 0.5f, 0.9f, 1f, pulse)
        ringFlat(0f, fl + 0.03f, z, 0.9f, 8, 0.6f, 0.95f, 1f, pulse)
        fx.v(0f, fl + 0.05f, z, 0.7f, 1f, 1f, pulse)
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
        for (side in intArrayOf(-1, 1)) {
            val x0 = sx + side * 1.4f
            val y0 = sy - 1f
            // Quadratic arc: out, then dive into the port.
            val x = x0 + (0f - x0) * t
            val y = y0 + (fl - y0) * (t * t)
            val z = z0 + (z1 - z0) * t
            fx.v(x, y, z, 0.5f, 0.95f, 1f, 1f)
            ring(x, y, z, 0.3f, 6, 0.5f, 0.95f, 1f, 0.9f)
            // trail
            val tx = x0 + (0f - x0) * (t * 0.82f)
            val ty = y0 + (fl - y0) * (t * 0.82f) * (t * 0.82f)
            val tz = z0 + (z1 - z0) * (t * 0.82f)
            lines.line(x, y, z, tx, ty, tz, 0.4f, 0.85f, 1f, 0.7f)
        }
    }

    // ------------------------------------------------------------ victory

    private fun buildVictory() {
        buildStars(0.6f)
        val t = game.stateT
        val cx = 0f; val cy = 2f; val cz = -120f
        if (t < 2.2f) {
            // The station tears itself apart: wireframe sphere, expanding and
            // shuddering, brightening to white.
            val expand = 1f + t * 0.55f
            val jit = t * 0.9f
            val white = (t / 2.2f).coerceIn(0f, 1f)
            buildStation(cx, cy, cz, 40f * expand, game.time * 9f, 1f - white * 0.4f, jit)
        }
        // Shockwave ring in the station plane.
        if (t > 0.35f) {
            val rw = (t - 0.35f) * 95f
            val a = (1f - (t - 0.35f) / 4.2f).coerceIn(0f, 1f)
            ring(cx, cy, cz, rw, 40, 0.65f, 0.9f, 1f, a * 0.9f)
            ring(cx, cy, cz, rw * 0.86f, 40, 1f, 0.75f, 0.4f, a * 0.6f)
        }
    }

    /** The battle station: a wireframe moon with its signature crater dish. */
    private fun buildStation(cx: Float, cy: Float, cz: Float, rad: Float, spin: Float, alpha: Float, jitter: Float = 0f) {
        val r = 0.45f; val g = 0.95f; val b = 0.6f
        val segs = 20
        // latitude rings
        var lat = -2
        while (lat <= 2) {
            val phi = lat * 0.5f
            val y = cy + rad * sin(phi)
            val rr = rad * cos(phi)
            var px = 0f; var pz = 0f; var first = true
            for (k in 0..segs) {
                val an = k.toFloat() / segs * 6.2832f + spin * 0.008f
                val jx = if (jitter > 0f) (rnd.nextFloat() - 0.5f) * jitter else 0f
                val jy = if (jitter > 0f) (rnd.nextFloat() - 0.5f) * jitter else 0f
                val nx = cx + cos(an) * rr + jx
                val nz = cz + sin(an) * rr * 0.35f + jy   // squashed = drawn like a globe
                if (!first) lines.line(px, y, pz, nx, y, nz, r, g, b, alpha * 0.6f)
                px = nx; pz = nz; first = false
            }
            lat++
        }
        // meridians (front-facing arcs)
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
        // the crater dish, upper hemisphere
        ring(cx - rad * 0.42f, cy + rad * 0.38f, cz - 1f, rad * 0.2f, 12, r, g, b, alpha)
        ring(cx - rad * 0.42f, cy + rad * 0.38f, cz - 1f, rad * 0.09f, 8, r, g, b, alpha * 0.8f)
        // equator trench line
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

        // Shield-hit red wash / port-strike white flash as frame borders.
        if (g.hitFlash > 0.01f) frame(6f, 1f, 0.2f, 0.15f, g.hitFlash * 0.9f)
        if (g.whiteFlash > 0.01f) {
            var i = 0
            while (i < 16) { // cheap full-screen white: dense horizontal lines
                val y = 15f + i * 30f
                hud.line(0f, y, 0f, 640f, y, 0f, 1f, 1f, 1f, g.whiteFlash * 0.8f)
                i++
            }
        }

        when (g.state) {
            GameState.TITLE -> {
                textC("X3WARS", 320f, 150f, 6f, 0.45f, 1f, 0.6f)
                textC("THE BATTLE FOR THE MOON THAT ISNT", 320f, 195f, 1.4f, 0.6f, 0.85f, 1f)
                textC("SWIPE TO AIM - CANNONS FIRE THEMSELVES", 320f, 300f, 1.3f, 0.75f, 0.8f, 0.9f)
                textC("TAP AT THE PORT TO LOOSE THE TORPEDOES", 320f, 322f, 1.3f, 0.75f, 0.8f, 0.9f)
                val blink = 0.5f + 0.5f * sin(g.time * 5f)
                textC("TAP TO LAUNCH", 320f, 400f, 2.2f, 0.45f, 1f, 0.6f, blink)
                textC("HIGH " + g.hiScore, 320f, 435f, 1.3f, 1f, 0.85f, 0.4f)
            }
            GameState.BRIEFING -> {
                textC(g.briefTitle, 320f, 210f, 4f, 0.45f, 1f, 0.6f)
                textC(g.briefSub, 320f, 250f, 1.7f, 1f, 0.85f, 0.4f)
                hudCommon()
            }
            GameState.FIGHTERS -> {
                if (g.stateT < 2.2f) textC("INTERCEPTORS", 320f, 150f, 2.4f, 1f, 0.6f, 0.4f, 1f - g.stateT / 2.4f)
                text("KILLS " + g.kills + "/" + g.killQuota, 16f, 452f, 1.4f, 0.6f, 0.85f, 1f)
                hudCommon()
            }
            GameState.SURFACE -> {
                if (g.stateT < 2.2f) textC("THE SURFACE", 320f, 150f, 2.4f, 0.45f, 1f, 0.6f, 1f - g.stateT / 2.4f)
                text("SWEEP " + g.surfaceT.toInt(), 16f, 452f, 1.4f, 0.6f, 0.85f, 1f)
                hudCommon()
            }
            GameState.TRENCH -> {
                if (g.stateT < 2.2f) textC("THE TRENCH", 320f, 150f, 2.4f, 0.45f, 1f, 0.6f, 1f - g.stateT / 2.4f)
                textC("RANGE " + g.rangeM.toInt().coerceAtLeast(0), 320f, 452f, 1.7f, 1f, 0.85f, 0.4f)
                centerCross()
                hudCommon()
            }
            GameState.PORT -> {
                centerCross()
                buildTargeting()
                hudCommon()
            }
            GameState.VICTORY -> {
                if (g.stateT > 1.2f) {
                    textC("THE BATTLE STATION", 320f, 190f, 2.6f, 1f, 0.9f, 0.5f)
                    textC("IS DESTROYED", 320f, 225f, 2.6f, 1f, 0.9f, 0.5f)
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

    private fun hudCommon() {
        val g = game
        text("SCORE " + g.score, 16f, 28f, 1.4f, 0.6f, 0.85f, 1f)
        text("WAVE " + g.wave, 560f, 28f, 1.4f, 0.6f, 0.85f, 1f)
        // Shields: bracket pips lower-left.
        var i = 0
        while (i < g.shields) {
            val x = 16f + i * 16f
            hud.line(x, 470f, 0f, x + 10f, 470f, 0f, 0.45f, 1f, 0.6f, 0.95f)
            hud.line(x, 465f, 0f, x, 475f, 0f, 0.45f, 1f, 0.6f, 0.95f)
            i++
        }
    }

    private fun centerCross() {
        val r = 0.45f; val g2 = 1f; val b = 0.6f
        hud.line(300f, 240f, 0f, 314f, 240f, 0f, r, g2, b, 0.9f)
        hud.line(326f, 240f, 0f, 340f, 240f, 0f, r, g2, b, 0.9f)
        hud.line(320f, 224f, 0f, 320f, 234f, 0f, r, g2, b, 0.9f)
        hud.line(320f, 246f, 0f, 320f, 256f, 0f, r, g2, b, 0.9f)
    }

    /** The targeting computer: converging brackets — until it's let go. */
    private fun buildTargeting() {
        val g = game
        if (!g.targetingOff) {
            val close = ((-g.portZ - 18f) / 280f).coerceIn(0f, 1f)   // 0 = on top of it
            val s = 40f + close * 150f
            val r = 1f; val gg = 0.75f; val b = 0.25f
            bracket(320f - s, 240f - s * 0.7f, 14f, 1f, r, gg, b)
            bracket(320f + s, 240f - s * 0.7f, 14f, -1f, r, gg, b)
            bracket(320f - s, 240f + s * 0.7f, 14f, 1f, r, gg, b, true)
            bracket(320f + s, 240f + s * 0.7f, 14f, -1f, r, gg, b, true)
            textC("TARGETING " + (-g.portZ).toInt().coerceAtLeast(0), 320f, 420f, 1.4f, r, gg, b)
        } else {
            // Off. A last flicker, then only the voice and the feeling.
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
