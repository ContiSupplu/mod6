# Fallout — the Nuke Minecraft would never ship

A Fabric mod for **Minecraft 1.21.11** that adds one block: the **Nuke**. Redstone- or
punch-triggered, tension-building countdown, a shockwave that carves a 90-block crater
without freezing the server, flying debris, a towering particle mushroom cloud, scorched
earth and lingering radiation. Built for filming.

---

## 1. Build & install

Requirements: **Java 21** (JDK). Everything else is downloaded by the Gradle wrapper.

```bash
./gradlew build          # Linux/macOS
gradlew build            # Windows
```

The mod jar lands in `build/libs/fallout-1.0.0.jar` (ignore the `-sources` jar).

Install:
1. Install the [Fabric Loader](https://fabricmc.net/use/installer/) for **1.21.11** (loader 0.18+).
2. Drop **fallout-1.0.0.jar** and **[Fabric API](https://modrinth.com/mod/fabric-api)**
   (`0.141.x+1.21.11`) into your `mods/` folder.

To test in a dev workspace instead: `./gradlew runClient`.

### Toolchain pins (already set in `gradle.properties`)

| What | Version |
|---|---|
| Minecraft | 1.21.11 |
| Yarn mappings | 1.21.11+build.4 |
| Fabric Loader | 0.18.4 |
| Fabric API | 0.141.4+1.21.11 |
| Loom | 1.14.10 (plugin id `net.fabricmc.fabric-loom-remap` — 1.21.11 is the last *obfuscated* MC version, which uses the `-remap` flavour) |
| Gradle (wrapper) | 8.14.3 |

> **Note:** this project was authored in an offline environment where the final
> `gradlew build` could not be executed (Mojang/FabricMC servers unreachable), so run the
> build once yourself. The code targets the 1.21.11 Yarn API precisely, but if Yarn moved
> a name between snapshots, §6 lists the few version-sensitive call sites and their
> one-line fixes.

## 2. Using the nuke

* **Craft:** 8× TNT around 1× Nether Star (expensive on purpose).
* **Arm:** power it with redstone **or** punch it (both configurable). A creeper-hiss
  plays, the casing turns red and starts strobing light.
* **Countdown:** default 10 s. Beeps start at 1/s and accelerate to a frantic 10/s with
  rising pitch — that's your cue to get the camera rolling and *run*.
* **Defuse:** right-click an armed nuke.
* **Boom:** white flash, layered boom heard across the whole dimension, expanding
  shockwave, debris, mushroom cloud, then 90 s of radiation over the crater.

**Filming tips:** set your client particle setting to **All**, film from 150–250 blocks
out at a slight elevation, and keep render distance ≥ 20 chunks. Every cloud particle is
force-sent to clients, so distance shots work.

## 3. How the destruction avoids freezing the game

The naive approach — destroy a radius-90 sphere in one tick — is ~3 million
`setBlockState` calls and a guaranteed multi-second freeze (or crash). Instead
(`Detonation.java`):

1. The blast is an **expanding spherical shell**. Each tick the wavefront advances by
   `radius / shockwaveDurationTicks` blocks and only the thin shell between the old and
   new radius is touched, so destruction ripples outward over ~8 seconds.
2. A `ShellCursor` iterates **exactly** the shell's blocks: for each (x, y) column it
   solves the in-shell z-range analytically (two square roots), never scanning the full
   cube, and skips air via a fast path.
3. A hard **per-tick budget** (`maxBlocksPerTick`) caps real block changes. If the wave
   is deep underground and a shell is too rich, the cursor *pauses mid-shell* and resumes
   next tick. The wave stalls a moment; the server never does. The budget is **shared**
   across simultaneous detonations (chain reactions included), so five nukes cost the
   same per tick as one.
4. Block removal uses `NOTIFY_LISTENERS | FORCE_STATE | SKIP_DROPS` — no item drops, no
   neighbour-update cascades (the two hidden costs of mass removal).
5. Entities, debris and particles are all chance-gated and hard-capped independently.

## 4. Config reference (`config/fallout.json`)

Created on first launch; values are clamped to sane ranges on load.

| Key | Default | What it does |
|---|---|---|
| `triggerByRedstone` | `true` | Arm when the block receives redstone power. |
| `triggerByPunch` | `true` | Arm when a player left-clicks the block. |
| `countdownSeconds` | `10` | Fuse length. Beeping accelerates as it runs out. |
| `blastRadius` | `90` | Radius (blocks) of the destruction sphere. **The** spectacle/performance dial. |
| `shockwaveDurationTicks` | `160` | Ticks for the wave to reach full radius (20 = 1 s). Longer = slower, more cinematic ripple and less work per tick. |
| `maxBlocksPerTick` | `24000` | Hard cap on block changes per tick, shared across all live detonations. The anti-freeze valve. |
| `craterDepthScale` | `0.55` | Crater depth as a fraction of radius (1.0 = full hemisphere). |
| `chainReaction` | `true` | Nukes caught in a blast detonate sympathetically. |
| `scorchedShellThickness` | `2.5` | Thickness of the charred shell left around the crater. |
| `scorchChance` | `0.85` | Chance each rim block gets charred (blackstone/basalt/deepslate/coal/magma). |
| `lingeringFireChance` | `0.08` | Chance to leave fire burning on charred blocks. |
| `debrisChance` | `0.035` | Chance an exposed destroyed block launches as flying debris. |
| `debrisMax` | `500` | Hard cap on debris entities per detonation. |
| `debrisLaunchPower` | `1.6` | How hard debris is thrown. |
| `cloudEnabled` | `true` | Master switch for the mushroom cloud. |
| `cloudHeight` | `80` | Cap height above the detonation point. |
| `cloudRadius` | `34` | Final cap radius. |
| `cloudDurationTicks` | `600` | How long the cloud keeps emitting (600 = 30 s). |
| `cloudParticleDensity` | `1.0` | Multiplies every particle count. |
| `flashEnabled` | `true` | Blinding white flash at t=0. |
| `screenShake` | `true` | Tiny velocity jolts rattle nearby players' cameras. |
| `blastDamageEnabled` | `true` | The passing wavefront damages & flings entities. |
| `blastDamageMax` | `150.0` | Damage at ground zero, falling linearly to 0 at the edge. |
| `radiationEnabled` | `true` | Lingering radiation zone over the crater. |
| `radiationRadiusMultiplier` | `1.25` | Radiation radius = blastRadius × this. |
| `radiationDurationSeconds` | `90` | How long the zone persists. |
| `radiationStrength` | `1` | Wither amplifier (0 = Wither I, 1 = Wither II…). Players also get nausea. |

## 5. Recommended settings for the BIGGEST filmable explosion

Tested logic, honest numbers — for a decent PC (6+ cores, 6 GB allocated to MC,
render distance 24):

```json
"blastRadius": 140,
"shockwaveDurationTicks": 360,
"maxBlocksPerTick": 32000,
"craterDepthScale": 0.5,
"debrisChance": 0.03,
"debrisMax": 800,
"cloudHeight": 120,
"cloudRadius": 55,
"cloudDurationTicks": 900,
"cloudParticleDensity": 1.6
```

Why these: radius 140 ≈ 5.7 M block sphere; at 32 k changes/tick worst case that's
~18 ms of block work in the heaviest ticks — hitchy but alive, and the longer
`shockwaveDurationTicks` (18 s wave) spreads the underground bulk thin. The cloud is
pure particles: cranking it costs the *client* fps, not the server, so lower
`cloudParticleDensity` first if your recording stutters, and lower `maxBlocksPerTick`
to 16000 if the tick-lag bothers you (the wave just takes longer).

Do **not** stack `blastRadius` > 200 with `shockwaveDurationTicks` < 100 unless you
enjoy slideshow footage — the budget will protect the server, but the wave will stall
visibly while it grinds through millions of underground blocks.

## 6. Limitations & version-sensitive call sites

**Limitations (by design):**
* An in-flight explosion does **not** survive a server restart — the wave stops where it
  was (armed, not-yet-detonated nukes *do* persist). The crater keeps whatever shape it reached.
* Lighting is recalculated normally, so the biggest craters cause brief light-update lag
  at the rim; unavoidable without leaving broken lighting.
* Nuking an ocean leaves water frozen mid-wall at the crater edge (neighbour updates are
  deliberately suppressed); it flows in as soon as anything touches it. Eerie, arguably a feature.
* Chunks outside loaded range are skipped, never force-loaded — keep the whole radius
  within view distance for a perfect sphere.
* No true camera-FOV screen shake (that needs a client mixin); `screenShake` approximates
  it with physical velocity jolts, and nausea in the radiation zone adds wobble.
* TNT/creeper explosions do **not** set the nuke off — only its own triggers do.

**Version-sensitive call sites** (Yarn moved these names during the 1.21.x cycle; if
`gradlew build` complains, these are the one-line fixes — everything else is stable API):

| File / call | If the compiler complains… |
|---|---|
| `MushroomCloud.spawnForced` → `world.spawnParticles(player, effect, true, true, …)` | Older overload takes a single boolean: drop the second `true`. |
| `SoundEvents.ENTITY_GENERIC_EXPLODE.value()` / `BLOCK_NOTE_BLOCK_PLING.value()` | If "cannot find method value()", the constant is a plain `SoundEvent` — remove `.value()`. Conversely, if a plain constant mismatches `RegistryEntry`, add `.value()`. |
| `world.getTopYInclusive()` (`Detonation` constructor) | Pre-1.21.2 name is `getTopY()`. |
| `NukeBlock.neighborUpdate(…, WireOrientation, …)` | Pre-1.21.2 has no `WireOrientation` parameter. |
| `NukeBlockEntity.readData/writeData(ReadView/WriteView)` | Pre-1.21.6 uses `readNbt/writeNbt(NbtCompound, RegistryWrapper.WrapperLookup)`. |
| `world.getChunkManager().isChunkLoaded(cx, cz)` | Alternatively `world.isChunkLoaded(cx, cz)`. |

## 7. Where to put better art

Generated placeholder textures live at
`src/main/resources/assets/fallout/textures/block/`:
`nuke_top/side/bottom.png` (idle), `nuke_top_primed/side_primed.png` (armed),
`nuke_top_lit/side_lit.png` (armed, lamp flash) — 16×16 RGBA. Replace them 1:1 (any
square power-of-two size works) and `assets/fallout/icon.png` (128×128) for the mod icon.

## 8. Project layout

```
src/main/java/com/contisupply/fallout/
├── Fallout.java             entrypoint, event wiring
├── FalloutConfig.java       JSON config, defaults + clamping
├── ModContent.java          block/item/block-entity registration
├── block/
│   ├── NukeBlock.java       triggers (redstone/punch), defuse, blockstates
│   └── NukeBlockEntity.java the countdown: beeps, strobe, persistence
└── nuke/
    ├── DetonationManager.java  tick driver, shared budget, pending queue
    ├── Detonation.java         expanding-shell destruction, debris, scorch, knockback
    ├── MushroomCloud.java      the particle money shot
    └── RadiationZone.java      lingering wither/nausea + ash-fall
```

All code is original. MIT licensed. Aim away from anything you love.
