# Dino Runner 🦖

The Chrome offline T-Rex game, recreated as a playable arcade cabinet inside Minecraft.

Craft an **Arcade Machine**, place it, right-click it — and you're playing the dino game:
auto-running pixel T-Rex, cacti to jump, pterodactyls to duck under, a score counter that
ticks up, speed that keeps climbing, the day/night flip at 700 points, milestone beeps,
a persistent high score, and a GAME OVER card with a restart button. All rendered as crisp
monochrome pixel art on a glowing arcade screen with a marquee and optional CRT scanlines.

- **Mod loader:** Fabric
- **Minecraft:** 1.21.11
- **Java (for building):** 21 or newer (Java 25 works — the toolchain is pinned for it)

---

## Building

```
git clone -b claude/dino-runner-minecraft-mod-rovaa8 https://github.com/ContiSupplu/mod6.git
cd mod6
gradlew build
```

The jar lands at `build\libs\dino-runner-1.0.0.jar` (ignore the `-sources` jar).

The toolchain in this repo (Gradle 9.2.1 wrapper, Loom 1.14.10, Loader 0.18.4,
Fabric API 0.141.2+1.21.11, yarn 1.21.11+build.4) is the combination already proven to
build on a Java 25 machine — don't bump one piece without the others. If a version ever
stops resolving, current values live at <https://fabricmc.net/develop/>.

## Installing

1. Install the **Fabric loader profile for 1.21.11** from <https://fabricmc.net/use/installer/>.
2. Download **Fabric API 0.141.2+1.21.11** from <https://modrinth.com/mod/fabric-api/versions>.
3. Drop both jars into `%appdata%\.minecraft\mods`:
   - `dino-runner-1.0.0.jar`
   - `fabric-api-0.141.2+1.21.11.jar`
4. Launch the Fabric 1.21.11 profile.

## Getting the Arcade Machine

Shaped recipe (also in the Functional Blocks creative tab):

```
stone       stone   stone
stone   glass pane  stone        ->  Arcade Machine
stone    redstone   stone
```

It faces you when placed, glows softly, and drops itself when mined (pickaxe is fastest).

## Controls (while the game is open)

| Key | Action |
|---|---|
| **Space** / **Up** / **W** / **Left-click** | Jump (tap = short hop, hold = full jump, hold on landing = bounce again) |
| **Down** / **S** | Duck (on the ground) / fast-drop (in the air) |
| **Space** after game over | Restart |
| **Esc** | Leave the arcade |

Ptero lanes: low ones you jump, head-height ones you duck under, high ones you just run past.

## Tuning the difficulty

Everything is in `config/dinorunner.json` (created on first launch, rewritten with any
missing options every launch, values clamped to sane ranges):

| Key | Default | Meaning |
|---|---|---|
| `startSpeed` | 360 | Scroll speed at run start (game-px/s; the original ≈ 360) |
| `maxSpeed` | 750 | Speed cap |
| `acceleration` | 4.2 | Speed gained per second |
| `gravity` | 2250 | Downward pull (px/s²) |
| `jumpVelocity` | 650 | Jump launch speed |
| `jumpCutVelocity` | 280 | Rise cap when you release jump early (variable jump height) |
| `minJumpHeight` | 45 | Height every tap is guaranteed to reach |
| `fastDropMultiplier` | 2.6 | Gravity multiplier while holding duck mid-air |
| `minObstacleGap` | 250 | Minimum gap between obstacles (px) |
| `gapSpeedFactor` | 0.32 | Extra gap proportional to speed |
| `gapRandomFactor` | 0.42 | Random extra gap, as a fraction of speed |
| `pteroMinScore` | 400 | Score at which pterodactyls appear |
| `pteroChance` | 0.22 | Chance a spawn is a pterodactyl |
| `scoreRate` | 0.028 | Score per pixel travelled (~10/s at start) |
| `milestoneEvery` | 100 | Beep-beep + score flash interval |
| `nightEvery` | 700 | Day/night flip interval |
| `soundVolume` | 0.6 | Game beep volume, 0 mutes |
| `crtScanlines` | true | CRT scanline overlay on the arcade screen |

The high score lives in `config/dinorunner_highscore.txt` and survives restarts. It is
saved at the moment of death (and on Esc mid-run if you beat it), so a crash can't eat it.

## Art & textures

The **game itself uses no texture files** — the dino, cacti, pterodactyls, clouds, moon,
stars, score font and GAME OVER card are all drawn as batched rectangles from pixel-art
bitmaps in [`GameSprites.java`](src/main/java/com/contisupply/dinorunner/client/game/GameSprites.java)
and [`PixelFont.java`](src/main/java/com/contisupply/dinorunner/client/game/PixelFont.java).
Edit the `#`-grids there to reskin the game.

The block textures are generated placeholder pixel art, already included. Replace these
PNGs to reskin the cabinet:

| File | Face |
|---|---|
| `src/main/resources/assets/dinorunner/textures/block/arcade_machine_front.png` | Screen + controls (16x16) |
| `src/main/resources/assets/dinorunner/textures/block/arcade_machine_side.png` | Side art (16x16) |
| `src/main/resources/assets/dinorunner/textures/block/arcade_machine_top.png` | Top vents (16x16) |
| `src/main/resources/assets/dinorunner/textures/block/arcade_machine_bottom.png` | Bottom (16x16) |
| `src/main/resources/assets/dinorunner/icon.png` | Mod icon (128x128) |

## How it works (code tour)

- `client/game/DinoGame.java` — the whole simulation: jump physics, obstacle spawning
  with speed-scaled gaps, forgiving multi-box collision, score/speed scaling, day/night.
  Pure Java, no Minecraft imports, driven by real delta-time (fps-independent).
- `client/screen/DinoGameScreen.java` — draws everything with `DrawContext.fill` under a
  single scale matrix and polls GLFW directly for input, so it dodges both the 1.21.6 GUI
  overhaul and the 1.21.9 input rework.
- `block/ArcadeMachineBlock.java` — furnace-style facing block; right-click runs a
  `Runnable` injected by the client entrypoint, so dedicated servers never touch client code.

## Limitations & notes

- The game is client-side: each player at the same machine plays their own run, and the
  high score is per-computer (stored in the local config folder), not per-world.
- Opening the screen pauses a singleplayer world (like the pause menu) — beeps still play.
- Sounds reuse vanilla's 8-bit note-block "bit" instrument, so there are no custom audio
  files to install.
- For recording, GUI Scale 1 or 2 gives the sharpest integer-scaled pixels; on very small
  windows the game shrinks smoothly to fit instead of cropping.
- Scores display up to 99999, like the original's five digits.
