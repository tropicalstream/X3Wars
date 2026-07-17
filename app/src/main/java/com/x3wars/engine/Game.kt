package com.x3wars.engine

import com.x3wars.SettingsStore
import com.x3wars.audio.Sfx
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * X3Wars — a first-person vector campaign in three battles, cycling forever
 * with a rising PART number and difficulty:
 *
 *  YAVIN   interceptors in space → the station surface → the trench →
 *          the exhaust port and the great wireframe fireball.
 *  HOTH    hunt probe droids over the snow → walker assault → fight through
 *          a mixed imperial fleet toward a dagger destroyer → strafe its
 *          deck radars and turrets until it goes down.
 *  ENDOR   a forest speeder run threading trunks and walker legs → torpedo
 *          the shield generator → the fleet above → a tunnel run through
 *          the unfinished station's guts to its core.
 *
 * Controls: swipes steer the aim reticle (in trench/forest/core it flies the
 * ship); cannons auto-fire; TAP looses the torpedoes at each level's heart.
 * Scene music streams from user-supplied MP3 folders (see Music.kt).
 * Everything runs on the GL thread; hot loops allocate nothing.
 */
enum class GameState {
    TITLE, BRIEFING,
    FIGHTERS, SURFACE, TRENCH,          // yavin (FIGHTERS doubles for endor space via FLEET)
    DROIDS, WALKERS, FLEET, DECK,       // hoth
    BIKES, CORE,                        // endor runs
    PORT, MINIWIN, VICTORY, GAMEOVER
}

enum class Level { YAVIN, HOTH, ENDOR }

interface GameHost {
    fun sfx(id: Int, pitch: Float = 1f, vol: Float = 1f)
    fun startRumble(rate: Float = 1f)
    fun stopRumble()
    fun say(id: String, urgent: Boolean = false)
    /** Start looping user MP3s for a scene folder; null stops the music. */
    fun music(scene: String?)
}

/** Shared swooping-enemy pool. kind: 0 interceptor, 1 probe droid, 2 gunship, 3 hunter. */
class Fighter {
    var kind = 0
    var x = 0f; var y = 0f; var z = -260f
    var baseX = 0f; var baseY = 0f
    var ampX = 0f; var ampY = 0f; var w1 = 0f; var w2 = 0f; var ph = 0f
    var holdZ = -40f
    var t = 0f
    var fireCd = 2f
    var hp = 1
    var alive = false
    var leaving = false
}

class Bolt {
    var x = 0f; var y = 0f; var z = 0f
    var vx = 0f; var vy = 0f; var vz = 0f
    var alive = false
}

/** Ground-standing pool. kind: 0 cannon tower, 1 walker, 2 radar dish, 3 deck turret, 4 forest strider. */
class Tower {
    var kind = 0
    var x = 0f; var z = -320f
    var h = 6f          // height; for wall turrets: mount y-position
    var hp = 1
    var phase = 0f      // walker stride
    var fireCd = 1.5f
    var hitCd = 0f      // brief invulnerability between counted hits
    var alive = false
}

/** Crossing planes. kind: 0 trench lattice, 1 forest trunks, 2 core pipes. */
class Barrier {
    var kind = 0
    var z = -320f
    var gapX = 0f; var gapY = 0f
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
        const val AIM_STEP = 0.30f
        const val TANX = 0.72f
        const val TANY = 0.55f
        const val TR_X = 3.1f
        const val TR_Y = 2.5f
        const val TRENCH_HALF_W = 4.6f
        const val TRENCH_FLOOR = -3.4f
        const val TRENCH_TOP = 3.4f
        // The core tunnel is tighter and fully enclosed.
        const val CORE_HALF_W = 3.6f
        const val CORE_FLOOR = -2.7f
        const val CORE_TOP = 2.7f
        const val MAX_FIGHTERS = 24
        const val MAX_BOLTS = 48
        const val MAX_TOWERS = 16
        const val MAX_BARRIERS = 10
        const val MAX_PARTICLES = 420
        // Port kinds.
        const val PORT_EXHAUST = 0
        const val PORT_GENERATOR = 1
        const val PORT_CORE = 2
    }

    var state = GameState.TITLE; private set
    var level = Level.YAVIN; private set
    var part = 1; private set             // campaign cycle: PART 2 YAVIN, PART 3…
    var sceneIdx = 0; private set
    var time = 0f; private set
    var stateT = 0f; private set
    var score = 0; private set
    var shields = 6; private set
    var hiScore = 0; private set

    var rx = 0f; private set
    var ry = 0f; private set
    private var rtx = 0f
    private var rty = 0f

    var shake = 0f; private set
    var hitFlash = 0f; private set
    var whiteFlash = 0f; private set

    val fighters = Array(MAX_FIGHTERS) { Fighter() }
    val bolts = Array(MAX_BOLTS) { Bolt() }
    val towers = Array(MAX_TOWERS) { Tower() }
    val barriers = Array(MAX_BARRIERS) { Barrier() }
    val particles = Array(MAX_PARTICLES) { Particle() }
    val stars = FloatArray(160 * 3)
    var snowMode = false; private set

    var kills = 0; private set
    var killQuota = 8; private set
    var surfaceT = 0f; private set
    var rangeM = 0f; private set
    var worldSpeed = 90f; private set
    var portKind = PORT_EXHAUST; private set
    var portZ = -280f; private set
    var targetingOff = false; private set
    var torpedoT = -1f; private set
    var fleetMega = false; private set    // dagger destroyer looming in FLEET
    private var saidLordFleet = false
    var victoryKind = 0; private set      // 0 station, 1 destroyer, 2 station mk2
    var briefTitle = ""; private set
    var briefSub = ""; private set

    private val rng = Random(System.nanoTime())
    private var fireCd = 0f
    private var gunSide = false
    var beamT = 1f; private set
    var beamX = 0f; private set
    var beamY = 0f; private set
    var beamRight = false; private set
    private var spawnCd = 0f
    private var barrierCd = 0f
    private var lastAlmost = false
    private var portRuns = 0
    private var saidScene = false
    private var mazeStep = 0
    private var mazeSide = 1f

    /** Difficulty scalar: 0 on the first campaign, +1 each full cycle. */
    private val d get() = (part - 1).toFloat()

    fun boot() {
        hiScore = store.highScore
        for (i in 0 until stars.size / 3) respawnStar(i, true)
        state = GameState.TITLE
        host.music("title")
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
            GameState.GAMEOVER -> if (stateT > 1.2f) {
                state = GameState.TITLE; stateT = 0f
                host.music("title")
            }
            GameState.PORT -> tryTorpedo()
            GameState.FIGHTERS, GameState.DROIDS, GameState.FLEET,
            GameState.SURFACE, GameState.WALKERS, GameState.DECK,
            GameState.TRENCH, GameState.BIKES, GameState.CORE -> fireShot()
            else -> {}
        }
    }

    // ------------------------------------------------------------ flow

    private fun startGame() {
        score = 0; shields = 6; part = 1; portRuns = 0
        level = Level.YAVIN; sceneIdx = 0
        store.games = store.games + 1
        host.sfx(Sfx.START)
        beginBriefing()
    }

    private fun levelName() = when (level) {
        Level.YAVIN -> "YAVIN"
        Level.HOTH -> "HOTH"
        Level.ENDOR -> "ENDOR"
    }

    private fun beginBriefing() {
        clearField()
        state = GameState.BRIEFING; stateT = 0f
        rtx = 0f; rty = 0f
        snowMode = level == Level.HOTH
        briefTitle = if (part == 1) levelName() else "PART $part  " + levelName()
        briefSub = when (level) {
            Level.YAVIN -> "THE ASSAULT BEGINS"
            Level.HOTH -> "THE COLD OFFENSIVE"
            Level.ENDOR -> "THE FOREST MOON"
        }
        when (level) {
            Level.YAVIN -> if (part == 1) host.say("mentor_brief") else host.say("pilot_launch")
            Level.HOTH -> host.say("lord_hoth")
            Level.ENDOR -> host.say("sage_endor")
        }
    }

    /** Scene tables per level; BRIEFING and VICTORY bracket them. */
    private fun beginScene() {
        saidScene = false
        when (level) {
            Level.YAVIN -> when (sceneIdx) {
                0 -> beginFighters()
                1 -> beginSurface()
                else -> beginTrench()
            }
            Level.HOTH -> when (sceneIdx) {
                0 -> beginDroids()
                1 -> beginWalkers()
                2 -> beginFleet(mega = true)
                else -> beginDeck()
            }
            Level.ENDOR -> when (sceneIdx) {
                0 -> beginBikes()
                1 -> beginFleet(mega = false)
                else -> beginCore()
            }
        }
    }

    private fun nextScene() {
        sceneIdx++
        val last = when (level) {
            Level.YAVIN -> 3   // fighters, surface, trench(+port)
            Level.HOTH -> 4    // droids, walkers, fleet, deck
            Level.ENDOR -> 3   // bikes(+generator), fleet, core(+port)
        }
        if (sceneIdx >= last) {
            // Level complete is handled by VICTORY; shouldn't reach here.
            sceneIdx = last - 1
        }
        beginScene()
    }

    private fun advanceLevel() {
        sceneIdx = 0
        when (level) {
            Level.YAVIN -> level = Level.HOTH
            Level.HOTH -> level = Level.ENDOR
            Level.ENDOR -> { level = Level.YAVIN; part++ }
        }
        beginBriefing()
    }

    // ---- scene begins ----

    private fun beginFighters() {
        clearField()
        state = GameState.FIGHTERS; stateT = 0f
        kills = 0
        killQuota = (6 + 2 * d).toInt().coerceAtMost(16)
        spawnCd = 0.4f
        host.stopRumble()
        host.music("yavin_space")
    }

    private fun beginSurface() {
        clearField()
        state = GameState.SURFACE; stateT = 0f
        surfaceT = 24f + 2f * d.coerceAtMost(5f)
        worldSpeed = 90f + 6f * d
        briefTitle = "THE SURFACE"
        host.sfx(Sfx.WARP)
        host.say("pilot_surface")
        host.startRumble()
        host.music("yavin_surface")
    }

    private fun beginTrench(rerun: Boolean = false) {
        clearField()
        state = GameState.TRENCH; stateT = 0f
        rangeM = if (rerun) 6000f else (24000f + 2000f * d).coerceAtMost(34000f)
        // Slow enough to read the barrier gaps and steer through them.
        worldSpeed = 66f + 4f * d
        barrierCd = 1.8f
        spawnCd = 0.4f
        if (!rerun) { host.sfx(Sfx.WARP); host.say("pilot_trench") }
        host.startRumble()
        host.music("yavin_trench")
        lastAlmost = false
    }

    private fun beginDroids() {
        clearField()
        state = GameState.DROIDS; stateT = 0f
        kills = 0
        killQuota = (6 + 2 * d).toInt().coerceAtMost(14)
        spawnCd = 0.5f
        worldSpeed = 70f
        host.startRumble(0.8f)
        host.music("hoth_droids")
    }

    private fun beginWalkers() {
        clearField()
        state = GameState.WALKERS; stateT = 0f
        kills = 0
        killQuota = (3 + d).toInt().coerceAtMost(7)
        spawnCd = 1f
        worldSpeed = 40f
        host.sfx(Sfx.WARP)
        host.say("lord_walkers")
        host.startRumble(0.7f)
        host.music("hoth_walkers")
    }

    private fun beginFleet(mega: Boolean) {
        clearField()
        state = GameState.FLEET; stateT = 0f
        fleetMega = mega
        saidLordFleet = false
        kills = 0
        killQuota = (8 + 2 * d).toInt().coerceAtMost(18)
        spawnCd = 0.4f
        host.stopRumble()
        host.sfx(Sfx.WARP)
        host.say(if (mega) "pilot_fleet" else "pilot_space2")
        host.music(if (mega) "hoth_fleet" else "endor_space")
    }

    private fun beginDeck() {
        clearField()
        state = GameState.DECK; stateT = 0f
        kills = 0
        killQuota = (8 + 2 * d).toInt().coerceAtMost(16)
        spawnCd = 0.6f
        worldSpeed = 84f + 5f * d
        host.sfx(Sfx.WARP)
        host.say("pilot_deck")
        host.startRumble()
        host.music("hoth_deck")
    }

    private fun beginBikes() {
        clearField()
        state = GameState.BIKES; stateT = 0f
        rangeM = (20000f + 2000f * d).coerceAtMost(30000f)
        worldSpeed = 96f + 6f * d      // fastest run — but generous gaps
        barrierCd = 1.6f
        spawnCd = 2f
        host.say("pilot_bikes")
        host.startRumble(1.3f)
        host.music("endor_forest")
        lastAlmost = false
    }

    private fun beginCore() {
        clearField()
        state = GameState.CORE; stateT = 0f
        rangeM = (16000f + 2000f * d).coerceAtMost(26000f)
        worldSpeed = 60f + 4f * d      // tightest walls, gentlest speed
        barrierCd = 1.6f
        mazeStep = 0; mazeSide = 1f
        host.sfx(Sfx.ALARM, 1f, 0.7f)
        host.say(if (rng.nextBoolean()) "sage_core" else "lord_core")
        host.startRumble(0.9f)
        host.music("endor_core")
        lastAlmost = false
    }

    private fun beginPort(kind: Int) {
        state = GameState.PORT; stateT = 0f
        portKind = kind
        portZ = -300f
        targetingOff = false
        torpedoT = -1f
        host.say(if (kind == PORT_CORE) "sage_letgo" else "mentor_letgo", urgent = true)
    }

    private fun beginMiniwin() {
        clearField()
        state = GameState.MINIWIN; stateT = 0f
        whiteFlash = 1f
        score += 10000
        host.sfx(Sfx.EXPL_L, 0.7f, 1f)
        host.say("pilot_shield", urgent = true)
        sphereBurst(0f, -1f, -90f, 160, 22f)
    }

    private fun beginVictory() {
        clearField()
        victoryKind = when (level) {
            Level.YAVIN -> 0
            Level.HOTH -> 1
            Level.ENDOR -> 2
        }
        state = GameState.VICTORY; stateT = 0f
        whiteFlash = 1f
        score += 25000
        shields = (shields + 2).coerceAtMost(8)
        host.stopRumble()
        host.sfx(Sfx.EXPL_L, 0.6f, 1f)
        host.say("pilot_victory", urgent = true)
        if (victoryKind == 2) host.say("sage_victory")
        host.music("victory")
        sphereBurst(0f, 2f, -120f, 300, 26f)
    }

    private fun sphereBurst(cx: Float, cy: Float, cz: Float, n: Int, maxSp: Float) {
        var made = 0
        for (p in particles) {
            if (p.alive) continue
            val th = rng.nextFloat() * 6.2832f
            val ph = rng.nextFloat() * 3.1416f
            val sp = 6f + rng.nextFloat() * maxSp
            p.alive = true
            p.x = cx; p.y = cy; p.z = cz
            p.vx = sp * sin(ph) * cos(th)
            p.vy = sp * cos(ph)
            p.vz = sp * sin(ph) * sin(th) * 0.6f
            p.maxLife = 2.2f + rng.nextFloat() * 2.6f
            p.life = p.maxLife
            p.hue = 0.02f + rng.nextFloat() * 0.14f
            if (++made >= n) break
        }
    }

    private fun gameOver() {
        state = GameState.GAMEOVER; stateT = 0f
        host.stopRumble()
        host.music(null)
        host.sfx(Sfx.SHIP_DIE)
        host.say(when (rng.nextInt(3)) {
            0 -> "lord_fall"
            1 -> "sage_fall"
            else -> "mentor_fall"
        })
        hiScore = maxOf(hiScore, score)
        store.highScore = score
        store.bestWave = part
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

        fireCd = (fireCd - dt).coerceAtLeast(0f)
        val k = 1f - exp(-11f * dt)
        rx += (rtx - rx) * k
        ry += (rty - ry) * k

        updateParticles(dt)

        when (state) {
            GameState.TITLE -> updateStars(dt, 26f)
            GameState.BRIEFING -> {
                updateStars(dt, 60f)
                if (stateT > 3.2f) beginScene()
            }
            GameState.FIGHTERS -> updateSwoopers(dt, starsSpeed = 40f) {
                if (!saidScene && stateT > 1f) { saidScene = true; host.say("pilot_fighters") }
                spawnSwooper(0)
            }
            GameState.DROIDS -> updateSwoopers(dt, starsSpeed = 10f) {
                if (!saidScene && stateT > 1f) { saidScene = true; host.say("pilot_droids") }
                spawnSwooper(1)
            }
            GameState.FLEET -> {
                if (fleetMega && !saidLordFleet && kills * 2 >= killQuota) {
                    saidLordFleet = true
                    host.say("lord_fleet")
                }
                updateSwoopers(dt, starsSpeed = 34f) {
                    spawnSwooper(if (rng.nextFloat() < 0.34f) 2 else if (rng.nextBoolean()) 3 else 0)
                }
            }
            GameState.SURFACE -> updateSurface(dt)
            GameState.WALKERS -> updateWalkers(dt)
            GameState.DECK -> updateDeck(dt)
            GameState.TRENCH -> updateRun(dt, barrierKind = 0) { beginPort(PORT_EXHAUST) }
            GameState.BIKES -> updateBikes(dt)
            GameState.CORE -> updateRun(dt, barrierKind = 2) { beginPort(PORT_CORE) }
            GameState.PORT -> updatePort(dt)
            GameState.MINIWIN -> if (stateT > 3.2f) nextScene()
            GameState.VICTORY -> if (stateT > 6f) advanceLevel()
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
            if (snowMode) {
                stars[i * 3 + 1] -= 4.5f * dt          // snowfall
                if (stars[i * 3 + 1] < -60f) stars[i * 3 + 1] = 60f
            }
            if (stars[i * 3 + 2] > -1f) respawnStar(i, false)
        }
    }

    // --------------------------------------------- swooping enemies (shared)

    private inline fun updateSwoopers(dt: Float, starsSpeed: Float, spawner: () -> Unit) {
        updateStars(dt, starsSpeed)
        spawnCd -= dt
        var aliveCount = 0
        for (f in fighters) if (f.alive) aliveCount++
        val want = (2 + (d / 2).toInt() + if (state == GameState.FLEET) 1 else 0).coerceAtMost(6)
        if (spawnCd <= 0f && aliveCount < want && kills + aliveCount < killQuota) {
            spawner()
            spawnCd = 0.7f + rng.nextFloat() * 0.9f
        }
        for (f in fighters) {
            if (!f.alive) continue
            f.t += dt
            val approach = when (f.kind) {
                1 -> 18f            // probe droids drift in slowly
                2 -> 26f            // gunships lumber
                3 -> 78f + 6f * d   // hunters streak
                else -> 46f + 6f * d
            }
            if (!f.leaving) {
                if (f.z < f.holdZ) f.z += approach * dt
                if (f.t > (if (f.kind == 1) 10f else 7f)) f.leaving = true
            } else {
                f.z -= 70f * dt
                if (f.z < -290f) { f.alive = false; continue }
            }
            f.x = f.baseX + f.ampX * sin(f.w1 * f.t + f.ph)
            f.y = f.baseY + f.ampY * cos(f.w2 * f.t)
            f.fireCd -= dt
            if (f.fireCd <= 0f && !f.leaving && f.z > -160f) {
                f.fireCd = (2.6f - 0.25f * d).coerceAtLeast(1.1f) + rng.nextFloat()
                if (f.kind == 1) host.sfx(Sfx.DROID, 1f, 0.6f)
                fireBoltFrom(f.x, f.y, f.z, 24f + 3f * d + if (f.kind == 3) 10f else 0f)
                host.sfx(Sfx.SAUCER_FIRE, if (f.kind == 2) 0.8f else 1.2f, 0.5f)
            }
        }
        updateBolts(dt)
        if (kills >= killQuota) {
            var quiet = true
            for (f in fighters) if (f.alive) { quiet = false; break }
            if (quiet) for (b in bolts) if (b.alive) { quiet = false; break }
            if (quiet) sceneCleared()
        }
    }

    private fun sceneCleared() {
        when (state) {
            GameState.FIGHTERS -> { briefTitle = "THE SURFACE"; nextScene() }
            GameState.DROIDS -> { host.say("pilot_walkers"); nextScene() }
            GameState.FLEET -> nextScene()
            GameState.WALKERS -> nextScene()
            GameState.DECK -> beginVictory()
            else -> nextScene()
        }
    }

    private fun spawnSwooper(kind: Int) {
        var f: Fighter? = null
        for (c in fighters) if (!c.alive) { f = c; break }
        val n = f ?: return
        n.kind = kind
        n.alive = true; n.leaving = false; n.t = 0f
        // Spawn OFF-centre: pick a quadrant and keep the weave inside it, so
        // nothing materializes under a parked reticle.
        val sx = if (rng.nextBoolean()) 1f else -1f
        val sy = if (rng.nextBoolean()) 1f else -1f
        n.baseX = sx * (7f + rng.nextFloat() * 9f)
        n.baseY = sy * (4f + rng.nextFloat() * 6f)
        n.ampX = (if (kind == 1) 1.5f else 2.5f) + rng.nextFloat() * (if (kind == 3) 6f else 4f)
        n.ampY = 1.5f + rng.nextFloat() * 3f
        n.w1 = (if (kind == 2) 0.4f else 0.8f) + rng.nextFloat() * 1.2f
        n.w2 = 0.6f + rng.nextFloat() * 1.4f
        n.ph = rng.nextFloat() * 6.28f
        n.z = -260f - rng.nextFloat() * 40f
        n.holdZ = (if (kind == 2) -60f else -34f) - rng.nextFloat() * 36f
        n.fireCd = 1.2f + rng.nextFloat() * 1.6f
        n.hp = when (kind) { 2 -> 3; else -> 1 }
        host.sfx(if (kind == 1) Sfx.DROID else Sfx.SPAWN, 1.3f, 0.5f)
    }

    private fun fireBoltFrom(x: Float, y: Float, z: Float, speed: Float) {
        var b: Bolt? = null
        for (c in bolts) if (!c.alive) { b = c; break }
        val n = b ?: return
        n.alive = true
        n.x = x; n.y = y; n.z = z
        // Aimed at the ship — which in a run scene is off-centre.
        val run = isRunScene()
        val tx = (if (run) shipX() else 0f) + (rng.nextFloat() * 2f - 1f) * 1.6f
        val ty = (if (run) shipY() else 0f) + (rng.nextFloat() * 2f - 1f) * 1.2f
        val dd = -z
        n.vx = (tx - x) / dd * speed
        n.vy = (ty - y) / dd * speed
        n.vz = speed
    }

    private fun isRunScene() =
        state == GameState.TRENCH || state == GameState.PORT ||
            state == GameState.BIKES || state == GameState.CORE

    private fun updateBolts(dt: Float) {
        for (b in bolts) {
            if (!b.alive) continue
            b.x += b.vx * dt; b.y += b.vy * dt; b.z += b.vz * dt
            if (b.z > -2f) {
                b.alive = false
                // In a run scene the bolt must actually arrive near the ship.
                if (!isRunScene() ||
                    (abs(b.x - shipX()) < 1.6f && abs(b.y - shipY()) < 1.4f)
                ) shieldHit()
            }
        }
    }

    // -------------------------------------------- ground scenes (surface/deck)

    private fun updateSurface(dt: Float) {
        surfaceT -= dt
        spawnCd -= dt
        if (spawnCd <= 0f && surfaceT > 4f) {
            spawnTower(0)
            spawnCd = (1.7f - 0.15f * d).coerceAtLeast(0.8f)
        }
        stepTowers(dt, worldSpeed + 20f)
        updateBolts(dt)
        if (surfaceT <= 0f) nextScene()
    }

    private fun updateDeck(dt: Float) {
        spawnCd -= dt
        if (spawnCd <= 0f && kills < killQuota) {
            spawnTower(if (rng.nextBoolean()) 2 else 3)
            spawnCd = (1.6f - 0.12f * d).coerceAtLeast(0.8f)
        }
        stepTowers(dt, worldSpeed + 16f)
        updateBolts(dt)
        if (!saidScene && stateT > 16f) { saidScene = true; host.say("pilot_deck_close") }
        if (kills >= killQuota) beginVictory()
    }

    private fun updateWalkers(dt: Float) {
        updateStars(dt, 4f)
        spawnCd -= dt
        var aliveW = 0
        for (t in towers) if (t.alive) aliveW++
        if (spawnCd <= 0f && aliveW < 3 && kills + aliveW < killQuota) {
            spawnTower(1)
            spawnCd = 2.2f + rng.nextFloat()
        }
        for (t in towers) {
            if (!t.alive || t.kind != 1) continue
            t.hitCd = (t.hitCd - dt).coerceAtLeast(0f)
            t.phase += dt * 1.6f
            t.z += (10f + 2f * d) * dt          // walkers advance slowly, relentlessly
            if ((t.phase % 3.1416f) < 0.05f) host.sfx(Sfx.STOMP, 0.9f + rng.nextFloat() * 0.2f, 0.6f)
            if (t.z > -18f) {                    // reached the line: it costs you
                t.alive = false
                shieldHit()
                continue
            }
            t.fireCd -= dt
            if (t.fireCd <= 0f && t.z > -200f) {
                t.fireCd = (2.8f - 0.2f * d).coerceAtLeast(1.4f)
                fireBoltFrom(t.x, t.h - 4.4f, t.z, 26f + 3f * d)
                host.sfx(Sfx.SAUCER_FIRE, 0.7f, 0.6f)
            }
        }
        updateBolts(dt)
        if (kills >= killQuota) {
            var quiet = true
            for (t in towers) if (t.alive) { quiet = false; break }
            if (quiet) { host.say("pilot_fleet_next"); nextScene() }
        }
    }

    private fun spawnTower(kind: Int) {
        var t: Tower? = null
        for (c in towers) if (!c.alive) { t = c; break }
        val n = t ?: return
        n.kind = kind
        n.alive = true
        n.x = (rng.nextFloat() * 2f - 1f) * 15f
        n.z = -330f
        n.phase = rng.nextFloat() * 6.28f
        n.fireCd = 1f + rng.nextFloat()
        when (kind) {
            1 -> { n.h = 8.5f; n.hp = 5; n.z = -240f - rng.nextFloat() * 60f }
            2 -> { n.h = 5f; n.hp = 1 }      // radar dish
            3 -> { n.h = 3.6f; n.hp = 2 }    // deck turret
            4 -> { n.h = 3f; n.hp = 3 }      // forest strider
            else -> { n.h = 4.5f + rng.nextFloat() * 4f; n.hp = 1 }
        }
    }

    private fun stepTowers(dt: Float, speed: Float) {
        for (t in towers) {
            if (!t.alive || t.kind == 1) continue
            t.z += speed * dt
            if (t.z > -4f) { t.alive = false; continue }
            t.fireCd -= dt
            val canFire = t.kind != 2      // radar dishes don't shoot
            if (canFire && t.fireCd <= 0f && t.z > -180f && t.z < -30f) {
                t.fireCd = (2.4f - 0.2f * d).coerceAtLeast(1.0f)
                fireBoltFrom(t.x, t.h - 6f, t.z, 30f + 3f * d)
                host.sfx(Sfx.SAUCER_FIRE, 0.9f, 0.5f)
            }
        }
    }

    // ------------------------------------------------- run scenes (rail duty)

    fun shipX() = rx * TR_X
    fun shipY() = ry * TR_Y

    private inline fun updateRun(dt: Float, barrierKind: Int, onArrive: () -> Unit) {
        rangeM -= worldSpeed * dt * 9f
        barrierCd -= dt
        if (barrierCd <= 0f && rangeM > 2500f) {
            spawnBarrier(barrierKind)
            barrierCd = (2.8f - 0.15f * d).coerceAtLeast(1.7f) + rng.nextFloat() * 0.8f
        }
        stepBarriers(dt)
        spawnCd -= dt
        if (barrierKind == 0) {
            // Trench: manned turret emplacements on the walls — visible,
            // firing, and shootable by flying the boresight onto them.
            if (spawnCd <= 0f && rangeM > 3000f) {
                spawnWallTurret()
                spawnCd = (2.2f - 0.15f * d).coerceAtLeast(1.3f) + rng.nextFloat() * 0.6f
            }
            for (t in towers) {
                if (!t.alive || t.kind != 5) continue
                t.z += worldSpeed * dt
                if (t.z > -3f) { t.alive = false; continue }
                t.fireCd -= dt
                if (t.fireCd <= 0f && t.z > -170f) {
                    t.fireCd = (2.4f - 0.15f * d).coerceAtLeast(1.2f)
                    fireBoltFrom(t.x * 0.94f, t.h, t.z, 30f + 3f * d)
                    host.sfx(Sfx.SAUCER_FIRE, 1.05f, 0.5f)
                }
            }
        } else {
            // Core duct: unseen defenses spit from the conduit mouths.
            if (spawnCd <= 0f) {
                fireBoltFrom(if (rng.nextBoolean()) -CORE_HALF_W else CORE_HALF_W,
                    CORE_TOP - 0.5f, -240f, 30f + 3f * d)
                host.sfx(Sfx.SAUCER_FIRE, 1.05f, 0.4f)
                spawnCd = (2.2f - 0.12f * d).coerceAtLeast(1.1f)
            }
        }
        updateBolts(dt)
        if (rangeM < 5200f && !lastAlmost) { lastAlmost = true; host.say("pilot_almost") }
        if (rangeM <= 0f) onArrive()
    }

    private fun spawnWallTurret() {
        var t: Tower? = null
        for (c in towers) if (!c.alive) { t = c; break }
        val n = t ?: return
        n.kind = 5
        n.alive = true
        n.x = if (rng.nextBoolean()) -TRENCH_HALF_W else TRENCH_HALF_W
        n.h = TRENCH_FLOOR + 1.2f + rng.nextFloat() * (TRENCH_TOP - TRENCH_FLOOR - 2.4f)
        n.z = -330f
        n.hp = 1
        n.fireCd = 0.8f + rng.nextFloat()
    }

    private fun updateBikes(dt: Float) {
        rangeM -= worldSpeed * dt * 9f
        barrierCd -= dt
        if (barrierCd <= 0f && rangeM > 2500f) {
            spawnBarrier(1)
            barrierCd = (2.4f - 0.12f * d).coerceAtLeast(1.5f) + rng.nextFloat() * 0.7f
        }
        stepBarriers(dt)
        // Forest striders lurching between the trees, shootable.
        spawnCd -= dt
        if (spawnCd <= 0f) {
            spawnTower(4)
            spawnCd = 3.4f - 0.2f * d + rng.nextFloat()
        }
        for (t in towers) {
            if (!t.alive || t.kind != 4) continue
            t.hitCd = (t.hitCd - dt).coerceAtLeast(0f)
            t.phase += dt * 2.4f
            t.z += worldSpeed * 0.85f * dt
            if (t.z > -4f) { t.alive = false; continue }
            t.fireCd -= dt
            if (t.fireCd <= 0f && t.z > -160f) {
                t.fireCd = (3f - 0.2f * d).coerceAtLeast(1.6f)
                fireBoltFrom(t.x, -0.6f, t.z, 30f + 3f * d)
                host.sfx(Sfx.SAUCER_FIRE, 1.3f, 0.45f)
            }
        }
        updateBolts(dt)
        if (rangeM < 5200f && !lastAlmost) { lastAlmost = true; host.say("pilot_generator") }
        if (rangeM <= 0f) beginPort(PORT_GENERATOR)
    }

    private fun spawnBarrier(kind: Int) {
        var b: Barrier? = null
        for (c in barriers) if (!c.alive) { b = c; break }
        val n = b ?: return
        n.alive = true; n.scored = false
        n.z = -330f
        if (kind == 2) {
            // The core is a MAZE of ducts: alternate forced S-turns (branch
            // walls open on one side), crawl/climb shelves, and pipe rings.
            when (mazeStep % 4) {
                0, 2 -> {              // branch wall: one whole side open
                    n.kind = 3
                    mazeSide = -mazeSide
                    n.gapX = mazeSide * TR_X * 0.55f
                    n.gapW = TR_X * 1.05f
                    n.gapY = 0f
                    n.gapH = 20f       // full height
                }
                1 -> {                 // shelf: go under or over
                    n.kind = 4
                    n.gapX = 0f
                    n.gapW = 20f       // full width
                    n.gapY = (if (rng.nextBoolean()) 1f else -1f) * TR_Y * 0.52f
                    n.gapH = (2.4f - 0.1f * d).coerceAtLeast(1.7f)
                }
                else -> {              // pipe ring: the tight window
                    n.kind = 2
                    n.gapX = (rng.nextFloat() * 2f - 1f) * (TR_X * 0.6f)
                    n.gapY = (rng.nextFloat() * 2f - 1f) * (TR_Y * 0.5f)
                    n.gapW = (2.6f - 0.12f * d).coerceAtLeast(1.8f)
                    n.gapH = (2.4f - 0.10f * d).coerceAtLeast(1.7f)
                }
            }
            mazeStep++
            return
        }
        n.kind = kind
        val gw = when (kind) {
            1 -> (3.4f - 0.15f * d).coerceAtLeast(2.4f)   // between trunks: generous
            else -> (2.8f - 0.12f * d).coerceAtLeast(1.9f)
        }
        val gh = when (kind) {
            1 -> 5f                                        // trunks: full height gap
            else -> (2.6f - 0.10f * d).coerceAtLeast(1.8f)
        }
        n.gapW = gw; n.gapH = gh
        n.gapX = (rng.nextFloat() * 2f - 1f) * (TR_X * 0.72f)
        n.gapY = if (kind == 1) 0f else (rng.nextFloat() * 2f - 1f) * (TR_Y * 0.6f)
    }

    private fun stepBarriers(dt: Float) {
        for (b in barriers) {
            if (!b.alive) continue
            b.z += worldSpeed * dt
            if (b.z > -1.2f && !b.scored) {
                b.scored = true
                val inGapX = abs(shipX() - b.gapX) < b.gapW * 0.5f
                val inGapY = abs(shipY() - b.gapY) < b.gapH * 0.5f
                if (inGapX && inGapY) {
                    score += 150
                    if (b.kind == 1) host.sfx(Sfx.WHOOSH, 1f + rng.nextFloat() * 0.2f, 0.7f)
                } else shieldHit()
            }
            if (b.z > 3f) b.alive = false
        }
    }

    // -------------------------------------------------------------- port

    private fun updatePort(dt: Float) {
        portZ += worldSpeed * 0.9f * dt
        if (!targetingOff && stateT > 2.6f) {
            targetingOff = true
            host.sfx(Sfx.PWR_END, 0.7f, 0.9f)
        }
        if (!targetingOff && stateT > 0.8f && (stateT * 3f).toInt() != ((stateT - dt) * 3f).toInt()) {
            host.sfx(Sfx.LOCK, 1f + (300f + portZ) / 300f, 0.4f)
        }
        if (torpedoT >= 0f) {
            torpedoT += dt / 1.5f
            if (torpedoT >= 1f) {
                if (portKind == PORT_GENERATOR) beginMiniwin() else beginVictory()
            }
        } else if (portZ > -16f) {
            portRuns++
            host.say("pilot_missed", urgent = true)
            host.say("mentor_again")
            when (portKind) {
                PORT_GENERATOR -> beginBikes()
                PORT_CORE -> beginCore()
                else -> beginTrench(rerun = true)
            }
            rangeM = 6000f
        }
    }

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

    /** One tap, one shot — the cannons answer to the pilot now. */
    private fun fireShot() {
        if (fireCd > 0f) return
        fireCd = 0.12f
        gunSide = !gunSide
        beamT = 0f
        beamX = rx; beamY = ry
        beamRight = gunSide
        host.sfx(Sfx.FIRE, if (gunSide) 1.06f else 0.97f, 0.45f)
        for (f in fighters) {
            if (!f.alive || f.leaving) continue
            val r = when (f.kind) { 1 -> 0.15f; 2 -> 0.20f; 3 -> 0.13f; else -> 0.16f }
            if (screenHit(f.x, f.y, f.z, r)) {
                if (--f.hp > 0) {
                    host.sfx(Sfx.EXPL_S, 0.8f, 0.5f)
                    explode(f.x, f.y, f.z, 5, 0.08f)
                    return
                }
                f.alive = false
                kills++; score += when (f.kind) { 1 -> 150; 2 -> 400; 3 -> 300; else -> 200 }
                explode(f.x, f.y, f.z, 22, if (f.kind == 1) 0.35f else 0.55f)
                host.sfx(if (f.kind == 1) Sfx.DROID_DIE else Sfx.EXPL_M, 1.1f, 0.8f)
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
            val ty = when (t.kind) {
                1 -> t.h - 4.4f          // walker head
                4 -> -0.6f               // strider cab
                5 -> t.h                 // wall turret mount height
                else -> t.h - 6f
            }
            val r = when (t.kind) { 1 -> 0.20f; 2 -> 0.18f; 5 -> 0.20f; else -> 0.16f }
            // Armored walkers and striders: the head OR the legs are the
            // weak zones — body shots don't count.
            val legHit = when (t.kind) {
                1 -> screenHit(t.x, -4.2f, t.z, 0.24f)
                4 -> screenHit(t.x, -2.6f, t.z, 0.20f)
                else -> false
            }
            if (screenHit(t.x, ty, t.z, r) || legHit) {
                if ((t.kind == 1 || t.kind == 4) && t.hitCd > 0f) return
                if (--t.hp > 0) {
                    if (t.kind == 1 || t.kind == 4) t.hitCd = 0.3f
                    host.sfx(Sfx.EXPL_S, 0.7f, 0.6f)
                    explode(t.x, ty, t.z, 6, 0.08f)
                    return
                }
                t.alive = false
                kills++
                score += when (t.kind) { 1 -> 600; 2 -> 300; 3 -> 250; 4 -> 350; 5 -> 200; else -> 250 }
                explode(t.x, ty, t.z, if (t.kind == 1) 34 else 18, 0.08f)
                host.sfx(if (t.kind == 2) Sfx.DISH else Sfx.EXPL_M, if (t.kind == 1) 0.7f else 0.9f, 0.9f)
                return
            }
        }
    }

    private fun screenHit(x: Float, y: Float, z: Float, r: Float): Boolean {
        // Engagement window: distant contacts all collapse into the vanishing
        // point on screen — letting them be hit there made the game play
        // itself. Nothing beyond -140 can be engaged.
        if (z > -3f || z < -140f) return false
        val run = isRunScene()
        val dd = -z
        val ox = if (run) shipX() else 0f
        val oy = if (run) shipY() else 0f
        val ax = if (run) 0f else rx
        val ay = if (run) 0f else ry
        val dx = ((x - ox) / dd) / TANX - ax
        val dy = ((y - oy) / dd) / TANY - ay
        val rr = r * (1f + 8f / dd)
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
