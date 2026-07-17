package com.x3wars.engine

import com.x3wars.SettingsStore
import com.x3wars.audio.Sfx
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * X3Wars — a first-person vector rail assault on a moon-sized battle station,
 * in three acts per wave, staged the way the 1983 vector classics did it:
 *
 *   FIGHTERS  open space; interceptors swoop and hurl energy bolts. The bolts
 *             are the real threat — and they can be shot down.
 *   SURFACE   skimming the station; cannon towers rise and fire.
 *   TRENCH    walls tight, catwalk barriers to thread, range counting down to
 *             the thermal exhaust port…
 *   PORT      …where the targeting computer is let go, one tap sends the
 *             torpedoes, and the station goes up in the finest wireframe
 *             fireball this side of the galaxy.
 *
 * Controls: swipes steer the aim reticle (in the trench the ship follows it);
 * the cannons fire themselves; TAP launches the torpedoes at the port.
 * Everything runs on the GL thread (update inside onDrawFrame) — no
 * cross-thread state, no per-frame allocation in the hot loops.
 */
enum class GameState { TITLE, BRIEFING, FIGHTERS, SURFACE, TRENCH, PORT, VICTORY, GAMEOVER }

interface GameHost {
    fun sfx(id: Int, pitch: Float = 1f, vol: Float = 1f)
    fun startRumble()
    fun stopRumble()
    fun say(id: String, urgent: Boolean = false)
}

class Fighter {
    var x = 0f; var y = 0f; var z = -260f
    var baseX = 0f; var baseY = 0f
    var ampX = 0f; var ampY = 0f; var w1 = 0f; var w2 = 0f; var ph = 0f
    var holdZ = -40f
    var t = 0f
    var fireCd = 2f
    var alive = false
    var leaving = false
}

class Bolt {
    var x = 0f; var y = 0f; var z = 0f
    var vx = 0f; var vy = 0f; var vz = 0f
    var alive = false
}

class Tower {
    var x = 0f; var z = -320f
    var h = 6f
    var fireCd = 1.5f
    var alive = false
}

class Barrier {
    var z = -320f
    var gapX = 0f; var gapY = 0f          // gap centre in ship-space units
    var gapW = 2.4f; var gapH = 2.2f
    var scored = false
    var alive = false
}

class Particle {
    var x = 0f; var y = 0f; var z = 0f
    var vx = 0f; var vy = 0f; var vz = 0f
    var life = 0f; var maxLife = 1f; var hue = 0.1f
    var alive = false
}

class Game(private val store: SettingsStore, private val host: GameHost) {

    companion object {
        // Aim space: the reticle lives in [-1,1]^2 of this angular screen space.
        const val AIM_STEP = 0.30f
        const val TANX = 0.72f            // tan(horizontal half-fov), matches renderer
        const val TANY = 0.55f
        // Trench geometry (world units; the ship can use most of it).
        const val TR_X = 3.1f
        const val TR_Y = 2.5f
        const val TRENCH_HALF_W = 4.6f
        const val TRENCH_FLOOR = -3.4f
        const val TRENCH_TOP = 3.4f
        const val MAX_FIGHTERS = 24
        const val MAX_BOLTS = 48
        const val MAX_TOWERS = 16
        const val MAX_BARRIERS = 10
        const val MAX_PARTICLES = 420
    }

    var state = GameState.TITLE; private set
    var time = 0f; private set
    var stateT = 0f; private set
    var wave = 1; private set
    var score = 0; private set
    var shields = 6; private set
    var hiScore = 0; private set

    // Aim reticle (eases toward the swiped target).
    var rx = 0f; private set
    var ry = 0f; private set
    private var rtx = 0f
    private var rty = 0f

    // Camera feel.
    var shake = 0f; private set
    var hitFlash = 0f; private set        // red shield-hit wash
    var whiteFlash = 0f; private set      // the port strike

    val fighters = Array(MAX_FIGHTERS) { Fighter() }
    val bolts = Array(MAX_BOLTS) { Bolt() }
    val towers = Array(MAX_TOWERS) { Tower() }
    val barriers = Array(MAX_BARRIERS) { Barrier() }
    val particles = Array(MAX_PARTICLES) { Particle() }

    // Streaming starfield — xyz triplets, recycled, shared by every act.
    val stars = FloatArray(160 * 3)

    var kills = 0; private set
    var killQuota = 8; private set
    var surfaceT = 0f; private set
    var rangeM = 0f; private set          // trench range to the port, "metres"
    var worldSpeed = 90f; private set
    var portZ = -280f; private set
    var targetingOff = false; private set
    var torpedoT = -1f; private set       // <0 idle; 0..1 in flight
    var briefTitle = ""; private set
    var briefSub = ""; private set

    private val rng = Random(System.nanoTime())
    private var fireCd = 0f
    private var gunSide = false
    var beamT = 1f; private set           // recent cannon shot anim (0=just fired)
    var beamX = 0f; private set
    var beamY = 0f; private set
    var beamRight = false; private set
    private var spawnCd = 0f
    private var barrierCd = 0f
    private var lastAlmost = false
    private var portRuns = 0
    private var saidFighters = false

    fun boot() {
        hiScore = store.highScore
        for (i in 0 until stars.size / 3) respawnStar(i, true)
        state = GameState.TITLE
    }

    // ------------------------------------------------------------- input

    /** dir: 0 up, 1 down, 2 left, 3 right — one reticle step per swipe. */
    fun aim(dir: Int) {
        if (state == GameState.TITLE || state == GameState.GAMEOVER) return
        when (dir) {
            0 -> rty += AIM_STEP
            1 -> rty -= AIM_STEP
            2 -> rtx -= AIM_STEP
            3 -> rtx += AIM_STEP
        }
        rtx = rtx.coerceIn(-1f, 1f)
        rty = rty.coerceIn(-1f, 1f)
        host.sfx(Sfx.TURN, 1.4f, 0.35f)
    }

    fun tap() {
        when (state) {
            GameState.TITLE -> startGame()
            GameState.GAMEOVER -> if (stateT > 1.2f) { state = GameState.TITLE; stateT = 0f }
            GameState.PORT -> tryTorpedo()
            else -> {}
        }
    }

    // ------------------------------------------------------------ flow

    private fun startGame() {
        score = 0; shields = 6; wave = 1; portRuns = 0
        store.games = store.games + 1
        host.sfx(Sfx.START)
        beginBriefing()
    }

    private fun beginBriefing() {
        clearField()
        state = GameState.BRIEFING; stateT = 0f
        rtx = 0f; rty = 0f
        briefTitle = "WAVE $wave"
        briefSub = "THE ASSAULT BEGINS"
        if (wave == 1) host.say("mentor_brief") else host.say("pilot_launch")
    }

    private fun beginFighters() {
        state = GameState.FIGHTERS; stateT = 0f
        kills = 0
        killQuota = (6 + 2 * (wave - 1)).coerceAtMost(16)
        spawnCd = 0.4f
        saidFighters = false
        host.stopRumble()
    }

    private fun beginSurface() {
        clearField()
        state = GameState.SURFACE; stateT = 0f
        surfaceT = 24f + 2f * wave.coerceAtMost(5)
        briefTitle = "THE SURFACE"
        briefSub = "CANNON TOWERS AHEAD"
        host.sfx(Sfx.WARP)
        host.say("pilot_surface")
        host.startRumble()
    }

    private fun beginTrench(rerun: Boolean = false) {
        clearField()
        state = GameState.TRENCH; stateT = 0f
        rangeM = if (rerun) 6000f else (26000f - 2000f * (wave - 1)).coerceAtLeast(14000f)
        worldSpeed = 88f + 7f * wave.coerceAtMost(8)
        barrierCd = 1.6f
        briefTitle = "THE TRENCH"
        briefSub = "THREAD THE BARRIERS"
        if (!rerun) { host.sfx(Sfx.WARP); host.say("pilot_trench") }
        host.startRumble()
        lastAlmost = false
    }

    private fun beginPort() {
        state = GameState.PORT; stateT = 0f
        portZ = -300f
        targetingOff = false
        torpedoT = -1f
        host.say("mentor_letgo", urgent = true)
    }

    private fun beginVictory() {
        clearField()
        state = GameState.VICTORY; stateT = 0f
        whiteFlash = 1f
        score += 25000
        shields = (shields + 2).coerceAtMost(8)
        host.stopRumble()
        host.sfx(Sfx.EXPL_L, 0.6f, 1f)
        host.say("pilot_victory", urgent = true)
        // The exterior fireball: a full sphere of debris off the station.
        var made = 0
        for (p in particles) {
            if (p.alive) continue
            val th = rng.nextFloat() * 6.2832f
            val ph = rng.nextFloat() * 3.1416f
            val sp = 6f + rng.nextFloat() * 26f
            p.alive = true
            p.x = 0f; p.y = 0f; p.z = -120f
            p.vx = sp * sin(ph) * cos(th)
            p.vy = sp * cos(ph)
            p.vz = sp * sin(ph) * sin(th) * 0.6f
            p.maxLife = 2.2f + rng.nextFloat() * 2.6f
            p.life = p.maxLife
            p.hue = 0.02f + rng.nextFloat() * 0.14f
            if (++made >= 300) break
        }
    }

    private fun gameOver() {
        state = GameState.GAMEOVER; stateT = 0f
        host.stopRumble()
        host.sfx(Sfx.SHIP_DIE)
        host.say("mentor_fall")
        hiScore = maxOf(hiScore, score)
        store.highScore = score
        store.bestWave = wave
    }

    private fun clearField() {
        for (f in fighters) f.alive = false
        for (b in bolts) b.alive = false
        for (t in towers) t.alive = false
        for (b in barriers) b.alive = false
    }

    // ------------------------------------------------------------ update

    fun update(dt: Float) {
        time += dt
        stateT += dt
        shake = (shake - dt * 2.4f).coerceAtLeast(0f)
        hitFlash = (hitFlash - dt * 1.8f).coerceAtLeast(0f)
        whiteFlash = (whiteFlash - dt * 1.1f).coerceAtLeast(0f)
        beamT = (beamT + dt * 9f).coerceAtMost(1f)

        // Reticle glide.
        val k = 1f - exp(-11f * dt)
        rx += (rtx - rx) * k
        ry += (rty - ry) * k

        updateParticles(dt)

        when (state) {
            GameState.TITLE -> updateStars(dt, 26f)
            GameState.BRIEFING -> {
                updateStars(dt, 60f)
                if (stateT > 3.2f) beginFighters()
            }
            GameState.FIGHTERS -> updateFighters(dt)
            GameState.SURFACE -> updateSurface(dt)
            GameState.TRENCH -> updateTrench(dt)
            GameState.PORT -> updatePort(dt)
            GameState.VICTORY -> if (stateT > 6f) { wave++; beginBriefing() }
            GameState.GAMEOVER -> updateStars(dt, 8f)
        }
    }

    // ---------------------------------------------------------- starfield

    private fun respawnStar(i: Int, anywhere: Boolean) {
        stars[i * 3] = (rng.nextFloat() * 2f - 1f) * 90f
        stars[i * 3 + 1] = (rng.nextFloat() * 2f - 1f) * 60f
        stars[i * 3 + 2] = if (anywhere) -rng.nextFloat() * 300f else -300f
    }

    private fun updateStars(dt: Float, speed: Float) {
        for (i in 0 until stars.size / 3) {
            stars[i * 3 + 2] += speed * dt
            if (stars[i * 3 + 2] > -1f) respawnStar(i, false)
        }
    }

    // ---------------------------------------------------------- fighters

    private fun updateFighters(dt: Float) {
        updateStars(dt, 40f)
        autoFire(dt)
        if (!saidFighters && stateT > 1f) { saidFighters = true; host.say("pilot_fighters") }

        // Keep a small pack in the fight until the quota is met.
        spawnCd -= dt
        var aliveCount = 0
        for (f in fighters) if (f.alive) aliveCount++
        val want = (2 + wave / 2).coerceAtMost(5)
        if (spawnCd <= 0f && aliveCount < want && kills + aliveCount < killQuota) {
            spawnFighter()
            spawnCd = 0.7f + rng.nextFloat() * 0.9f
        }

        for (f in fighters) {
            if (!f.alive) continue
            f.t += dt
            if (!f.leaving) {
                if (f.z < f.holdZ) f.z += (46f + 6f * wave) * dt
                if (f.t > 7f) f.leaving = true
            } else {
                f.z -= 70f * dt
                if (f.z < -290f) { f.alive = false; continue }
            }
            f.x = f.baseX + f.ampX * sin(f.w1 * f.t + f.ph)
            f.y = f.baseY + f.ampY * cos(f.w2 * f.t)
            f.fireCd -= dt
            if (f.fireCd <= 0f && !f.leaving && f.z > -160f) {
                f.fireCd = (2.6f - 0.15f * wave).coerceAtLeast(1.2f) + rng.nextFloat()
                fireBoltFrom(f.x, f.y, f.z, 26f + 2.5f * wave)
                host.sfx(Sfx.SAUCER_FIRE, 1.2f, 0.5f)
            }
        }
        updateBolts(dt)
        if (kills >= killQuota) {
            var quiet = true
            for (f in fighters) if (f.alive) { quiet = false; break }
            if (quiet) for (b in bolts) if (b.alive) { quiet = false; break }
            if (quiet) beginSurface()
        }
    }

    private fun spawnFighter() {
        var f: Fighter? = null
        for (c in fighters) if (!c.alive) { f = c; break }
        val n = f ?: return
        n.alive = true; n.leaving = false; n.t = 0f
        n.baseX = (rng.nextFloat() * 2f - 1f) * 14f
        n.baseY = (rng.nextFloat() * 2f - 1f) * 8f
        n.ampX = 4f + rng.nextFloat() * 8f
        n.ampY = 2f + rng.nextFloat() * 5f
        n.w1 = 0.8f + rng.nextFloat() * 1.2f
        n.w2 = 0.6f + rng.nextFloat() * 1.4f
        n.ph = rng.nextFloat() * 6.28f
        n.z = -260f - rng.nextFloat() * 40f
        n.holdZ = -34f - rng.nextFloat() * 36f
        n.fireCd = 1.2f + rng.nextFloat() * 1.6f
        host.sfx(Sfx.SPAWN, 1.3f, 0.5f)
    }

    private fun fireBoltFrom(x: Float, y: Float, z: Float, speed: Float) {
        var b: Bolt? = null
        for (c in bolts) if (!c.alive) { b = c; break }
        val n = b ?: return
        n.alive = true
        n.x = x; n.y = y; n.z = z
        // Aimed at the ship (the camera) with a little scatter.
        val jx = (rng.nextFloat() * 2f - 1f) * 1.6f
        val jy = (rng.nextFloat() * 2f - 1f) * 1.2f
        val d = -z
        n.vx = (jx - x) / d * speed
        n.vy = (jy - y) / d * speed
        n.vz = speed
    }

    private fun updateBolts(dt: Float) {
        for (b in bolts) {
            if (!b.alive) continue
            b.x += b.vx * dt; b.y += b.vy * dt; b.z += b.vz * dt
            if (b.z > -2f) {
                b.alive = false
                shieldHit()
            }
        }
    }

    // ----------------------------------------------------------- surface

    private fun updateSurface(dt: Float) {
        autoFire(dt)
        surfaceT -= dt
        spawnCd -= dt
        if (spawnCd <= 0f && surfaceT > 4f) {
            var t: Tower? = null
            for (c in towers) if (!c.alive) { t = c; break }
            t?.let {
                it.alive = true
                it.x = (rng.nextFloat() * 2f - 1f) * 15f
                it.z = -330f
                it.h = 4.5f + rng.nextFloat() * 4f
                it.fireCd = 1f + rng.nextFloat()
            }
            spawnCd = (1.7f - 0.1f * wave).coerceAtLeast(0.8f)
        }
        for (t in towers) {
            if (!t.alive) continue
            t.z += (worldSpeed + 20f) * dt
            if (t.z > -4f) { t.alive = false; continue }
            t.fireCd -= dt
            if (t.fireCd <= 0f && t.z > -180f && t.z < -30f) {
                t.fireCd = (2.4f - 0.12f * wave).coerceAtLeast(1.1f)
                fireBoltFrom(t.x, t.h - 6f, t.z, 30f + 2.5f * wave)
                host.sfx(Sfx.SAUCER_FIRE, 0.9f, 0.5f)
            }
        }
        updateBolts(dt)
        if (surfaceT <= 0f) beginTrench()
    }

    // ------------------------------------------------------------ trench

    /** Ship position inside the trench (the reticle IS the flight stick here). */
    fun shipX() = rx * TR_X
    fun shipY() = ry * TR_Y

    private fun updateTrench(dt: Float) {
        autoFire(dt)
        rangeM -= worldSpeed * dt * 9f
        barrierCd -= dt
        if (barrierCd <= 0f && rangeM > 2500f) {
            var b: Barrier? = null
            for (c in barriers) if (!c.alive) { b = c; break }
            b?.let {
                it.alive = true; it.scored = false
                it.z = -330f
                it.gapX = (rng.nextFloat() * 2f - 1f) * (TR_X * 0.72f)
                it.gapY = (rng.nextFloat() * 2f - 1f) * (TR_Y * 0.6f)
                it.gapW = (2.7f - 0.12f * wave).coerceAtLeast(1.8f)
                it.gapH = (2.5f - 0.10f * wave).coerceAtLeast(1.7f)
            }
            barrierCd = (2.6f - 0.12f * wave).coerceAtLeast(1.5f) + rng.nextFloat() * 0.8f
        }
        for (b in barriers) {
            if (!b.alive) continue
            b.z += worldSpeed * dt
            if (b.z > -1.2f && !b.scored) {
                b.scored = true
                val inGapX = abs(shipX() - b.gapX) < b.gapW * 0.5f
                val inGapY = abs(shipY() - b.gapY) < b.gapH * 0.5f
                if (inGapX && inGapY) score += 150 else shieldHit()
            }
            if (b.z > 3f) b.alive = false
        }
        // Rim turret fire.
        spawnCd -= dt
        if (spawnCd <= 0f) {
            fireBoltFrom(if (rng.nextBoolean()) -TRENCH_HALF_W else TRENCH_HALF_W,
                TRENCH_TOP - 0.5f, -240f, 34f + 3f * wave)
            host.sfx(Sfx.SAUCER_FIRE, 1.05f, 0.4f)
            spawnCd = (2.0f - 0.1f * wave).coerceAtLeast(1.0f)
        }
        updateBolts(dt)
        if (rangeM < 5200f && !lastAlmost) { lastAlmost = true; host.say("pilot_almost") }
        if (rangeM <= 0f) beginPort()
    }

    // -------------------------------------------------------------- port

    private fun updatePort(dt: Float) {
        portZ += worldSpeed * 0.9f * dt
        if (!targetingOff && stateT > 2.6f) {
            targetingOff = true
            host.sfx(Sfx.PWR_END, 0.7f, 0.9f)
        }
        if (torpedoT >= 0f) {
            torpedoT += dt / 1.5f
            if (torpedoT >= 1f) beginVictory()
        } else if (portZ > -16f) {
            // Overflew it. Come around for another pass.
            portRuns++
            host.say("pilot_missed", urgent = true)
            host.say("mentor_again")
            beginTrench(rerun = true)
        }
    }

    /** In the window (port close but not passed), TAP looses the torpedoes. */
    private fun tryTorpedo() {
        if (torpedoT >= 0f) return
        if (portZ > -95f && portZ < -18f) {
            torpedoT = 0f
            host.sfx(Sfx.DROP, 1.1f, 1f)
            host.say("pilot_away", urgent = true)
            score += 200 * (portRuns + 1)
        } else {
            host.sfx(Sfx.PWR_END, 1.4f, 0.5f)
        }
    }

    // ----------------------------------------------------------- shooting

    private fun autoFire(dt: Float) {
        fireCd -= dt
        if (fireCd > 0f) return
        fireCd = 0.15f
        gunSide = !gunSide
        beamT = 0f
        beamX = rx; beamY = ry
        beamRight = gunSide
        host.sfx(Sfx.FIRE, if (gunSide) 1.06f else 0.97f, 0.45f)
        // Hit test in angular screen space against the smoothed reticle.
        for (f in fighters) {
            if (!f.alive || f.leaving) continue
            if (screenHit(f.x, f.y, f.z, 0.16f)) {
                f.alive = false
                kills++; score += 200
                explode(f.x, f.y, f.z, 22, 0.55f)
                host.sfx(Sfx.EXPL_M, 1.2f, 0.8f)
                return
            }
        }
        for (b in bolts) {
            if (!b.alive) continue
            if (screenHit(b.x, b.y, b.z, 0.12f)) {
                b.alive = false
                score += 25
                explode(b.x, b.y, b.z, 8, 0.04f)
                host.sfx(Sfx.EXPL_S, 1.5f, 0.6f)
                return
            }
        }
        for (t in towers) {
            if (!t.alive) continue
            if (screenHit(t.x, t.h - 6f, t.z, 0.17f)) {
                t.alive = false
                score += 250
                explode(t.x, t.h - 6f, t.z, 18, 0.08f)
                host.sfx(Sfx.EXPL_M, 0.9f, 0.8f)
                return
            }
        }
    }

    /**
     * True if the entity projects within r (angular units) of the aim point.
     * In open space the reticle is the aim; in the trench the camera rides the
     * ship and the guns bore-sight dead ahead (screen centre, ship-relative).
     */
    private fun screenHit(x: Float, y: Float, z: Float, r: Float): Boolean {
        if (z > -3f || z < -290f) return false
        val trench = state == GameState.TRENCH || state == GameState.PORT
        val d = -z
        val ox = if (trench) shipX() else 0f
        val oy = if (trench) shipY() else 0f
        val ax = if (trench) 0f else rx
        val ay = if (trench) 0f else ry
        val dx = ((x - ox) / d) / TANX - ax
        val dy = ((y - oy) / d) / TANY - ay
        // Generous with depth: near things are easier to hit.
        val rr = r * (1f + 26f / d)
        return dx * dx + dy * dy < rr * rr
    }

    private fun shieldHit() {
        shields--
        shake = 1f; hitFlash = 1f
        host.sfx(Sfx.EXPL_L, 1.3f, 0.9f)
        if (shields < 0) gameOver() else if (shields <= 2) host.say("pilot_hit")
    }

    // ---------------------------------------------------------- particles

    private fun explode(x: Float, y: Float, z: Float, n: Int, hue: Float) {
        var made = 0
        for (p in particles) {
            if (p.alive) continue
            p.alive = true
            p.x = x; p.y = y; p.z = z
            val th = rng.nextFloat() * 6.2832f
            val sp = 3f + rng.nextFloat() * 12f
            p.vx = cos(th) * sp
            p.vy = sin(th) * sp
            p.vz = (rng.nextFloat() * 2f - 1f) * 8f
            p.maxLife = 0.5f + rng.nextFloat() * 0.7f
            p.life = p.maxLife
            p.hue = hue + rng.nextFloat() * 0.08f
            if (++made >= n) break
        }
    }

    private fun updateParticles(dt: Float) {
        for (p in particles) {
            if (!p.alive) continue
            p.life -= dt
            if (p.life <= 0f) { p.alive = false; continue }
            p.x += p.vx * dt; p.y += p.vy * dt; p.z += p.vz * dt
        }
    }
}
