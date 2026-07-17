# X3Wars

A first-person **vector rail assault** for the RayNeo X3 Pro smart glasses,
in the spirit of the great 1983 color-vector arcade cabinets: glowing
wireframes on black (transparent on the waveguide), a young pilot, an old
mystic in your ear, and one impossible torpedo shot at the heart of a
moon-sized battle station.

## The run

Every wave is the full assault, in three acts:

1. **INTERCEPTORS** — open space. Twin-panel fighters swoop and hurl energy
   bolts. The bolts are the real threat — and they can be shot down.
2. **THE SURFACE** — down on the deck of the station, cannon towers rising
   and firing as the surface races past.
3. **THE TRENCH** — walls tight, catwalk barriers to thread, rim turrets
   firing, range counting down… and at zero, the **exhaust port**: the
   targeting computer converges, then switches itself off, the old mystic
   tells you to trust yourself, and one well-timed TAP sends both torpedoes
   home. The station's death is the best wireframe fireball we could build.

Miss the window and you loop around for another pass. Waves escalate:
more fighters, faster bolts, meaner barriers, a longer trench.

## Controls (two inputs, no settings)

| Input | Action |
|---|---|
| **Swipe** (4-way) | Steer the aim reticle — in the trench it flies the ship |
| **Tap** | Launch torpedoes at the port · start · retry |
| — | The cannons fire themselves |

Suite conventions: temple tap arrives as a KEY, the left pad is ignored,
horizontal swipe sign is inverted on this hardware (forward = right).

## The voices

Two speakers, pre-generated with fish.audio S1 (no synthesis at run time —
missing clips are baked once from device TTS on first boot):

- **The pilot** — `16b68ec193c24e61929c84c1306961a5`
- **The old mystic** — `77fec8dd00174ddcac427af0b1011709`

```bash
export FISH_API_KEY=...     # never stored anywhere
python3 tools/generate_tts.py
./gradlew assembleDebug
```

## Build & install

```bash
cd ~/Projects/X3Wars
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

JDK 17, AGP 8.7.3, Kotlin 2.0.21, compileSdk 35 / minSdk 29, zero
dependencies. GLES3, additive-blend line/point batches, stroke-font HUD;
side-by-side per-eye viewports auto-enable on RayNeo hardware (detected by
manufacturer identity, never `Build.MODEL`). All audio synthesized at first
launch or pre-generated — nothing network at run time, no per-frame
allocation on the render thread.
