package com.contisupply.dinorunner.client.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The complete T-Rex runner simulation - deliberately free of any Minecraft imports so it
 * can be unit-tested headlessly. {@link com.contisupply.dinorunner.client.screen.DinoGameScreen}
 * owns one of these, feeds it real time + key state every frame, and draws whatever it says.
 *
 * Coordinate system: a 600x150 unit canvas, x growing right, y growing DOWN (same as GUI
 * pixels). The dino stands near the left edge; obstacles stream in from the right at the
 * current scroll speed. All motion is integrated with real delta-time, so the game feels
 * identical at 30 or 240 fps.
 *
 * The frame loop, in order (see {@link #update}):
 *   1. input        - edge-detect jump, apply duck, handle READY start / DEAD restart
 *   2. dino physics - gravity, jump arc, fast-drop, landing
 *   3. world scroll - speed ramps up over time, obstacles + clouds move left
 *   4. spawning     - distance-based gaps that scale with speed (harder as you go faster)
 *   5. collision    - forgiving multi-box AABB test, like the original
 *   6. score        - distance * rate; milestone beeps; day/night flip
 */
public final class DinoGame {

	/** Canvas size in game units - same aspect as the original Chrome canvas. */
	public static final int CANVAS_W = 600;
	public static final int CANVAS_H = 150;
	/** Top of the 2px ground line. */
	public static final int GROUND_LINE_Y = 130;
	/** Where feet/cactus-bottoms rest (slightly below the line so things look planted). */
	public static final int BASELINE = 133;
	/** Left edge of the dino sprite. */
	public static final int DINO_X = 24;

	/** Standing sprite footprint in units. */
	public static final int DINO_W = GameSprites.unitsWide(GameSprites.DINO_IDLE);
	public static final int DINO_H = GameSprites.unitsTall(GameSprites.DINO_IDLE);
	/** Ducking sprite footprint in units. */
	public static final int DUCK_W = GameSprites.unitsWide(GameSprites.DINO_DUCK_A);
	public static final int DUCK_H = GameSprites.unitsTall(GameSprites.DINO_DUCK_A);

	public enum State { READY, RUNNING, DEAD }

	/** Obstacle kinds. */
	public static final int KIND_CACTUS_SMALL = 0;
	public static final int KIND_CACTUS_LARGE = 1;
	public static final int KIND_PTERO = 2;

	/** Callbacks for the audible moments; the screen turns these into note-block beeps. */
	public interface Events {
		void onJump();

		void onMilestone(int score);

		void onDeath(int score);
	}

	public static final class Obstacle {
		public int kind;
		/** For cacti: how many are planted side by side (1-3). */
		public int count;
		/** Left edge / top edge on the canvas, in units. */
		public double x;
		public double y;
		public double w;
		public double h;
		/** Pterodactyls fly a little slower/faster than the ground scroll. */
		public double speedOffset;
		/** Wing-flap animation clock (pterodactyls only). */
		public double flapTimer;
		public boolean flapFrame;
	}

	public static final class Cloud {
		public double x;
		public double y;
	}

	private final GameTuning t;
	private final Events events;
	private final Random random;

	private State state = State.READY;
	/** Wall-clock seconds since the screen opened; drives UI blinking. */
	private double time;
	/** Current scroll speed, units/s. */
	private double speed;
	/** Total world distance scrolled this run, units. */
	private double distance;

	// --- dino ---
	/** Y of the dino's feet; BASELINE when grounded, smaller mid-jump. */
	private double feetY = BASELINE;
	/** Vertical velocity, +down. */
	private double velocityY;
	private boolean ducking;
	private boolean prevJumpHeld;
	/** Seconds left in which a mid-air jump press still triggers on landing. */
	private double jumpBuffer;
	private double runAnimTimer;
	private boolean runFrameFlip;

	// --- world ---
	private final List<Obstacle> obstacles = new ArrayList<>();
	private final List<Cloud> clouds = new ArrayList<>();
	/** Units of scroll remaining until the next obstacle spawns. */
	private double distToNextSpawn;
	private double cloudTimer;

	// --- score / presentation ---
	private int lastMilestone;
	/** Counts down while the score is flashing after a milestone. */
	private double scoreFlashTimer;
	/** 0 = day, 1 = night; eased toward the target each frame. */
	private double nightBlend;
	/** Seconds since death; also the restart lockout. */
	private double deathTimer;

	public DinoGame(GameTuning tuning, Events events) {
		this(tuning, events, new Random());
	}

	/** Seedable constructor for deterministic tests. */
	public DinoGame(GameTuning tuning, Events events, Random random) {
		this.t = tuning;
		this.events = events;
		this.random = random;
		this.speed = t.startSpeed;
		this.distToNextSpawn = firstGap();
		// A couple of clouds so the READY screen isn't an empty void.
		for (int i = 0; i < 2; i++) {
			Cloud c = new Cloud();
			c.x = 120 + i * 260 + random.nextDouble() * 60;
			c.y = 24 + random.nextDouble() * 55;
			clouds.add(c);
		}
	}

	// =====================================================================
	// Frame update
	// =====================================================================

	/**
	 * Advances the simulation. Call once per rendered frame.
	 *
	 * @param dt       real seconds since last frame (the screen clamps runaway values)
	 * @param jumpHeld space / up / W / left-click held this frame
	 * @param duckHeld down / S held this frame
	 */
	public void update(double dt, boolean jumpHeld, boolean duckHeld) {
		time += dt;
		boolean jumpPressed = jumpHeld && !prevJumpHeld;
		prevJumpHeld = jumpHeld;

		switch (state) {
			case READY -> {
				// The original starts on the first jump: press -> instantly running AND airborne.
				if (jumpPressed) {
					state = State.RUNNING;
					launch();
				}
			}
			case RUNNING -> tickRunning(dt, jumpPressed, jumpHeld, duckHeld);
			case DEAD -> {
				deathTimer += dt;
				// Short lockout so mashing at the moment of death doesn't instantly restart.
				if (jumpPressed && deathTimer > 0.45) {
					restart();
				}
			}
		}
	}

	private void tickRunning(double dt, boolean jumpPressed, boolean jumpHeld, boolean duckHeld) {
		boolean grounded = feetY >= BASELINE;

		// ---- 1. input ----
		if (jumpPressed) {
			if (grounded) {
				launch();
			} else {
				jumpBuffer = 0.09; // pressed a hair early: honour it on landing
			}
		}
		// Variable jump height: while rising with the button released, cap the rise - but
		// only once past the guaranteed minimum, so a quick tap still clears a small cactus.
		if (!jumpHeld && velocityY < -t.jumpCutVelocity && BASELINE - feetY >= t.minJumpHeight) {
			velocityY = -t.jumpCutVelocity;
		}
		ducking = duckHeld && grounded;

		// ---- 2. dino physics ----
		if (!grounded || velocityY < 0) {
			double g = t.gravity * (duckHeld ? t.fastDropMultiplier : 1); // duck in air = slam down
			velocityY += g * dt;
			feetY += velocityY * dt;
			if (feetY >= BASELINE) { // touched down
				feetY = BASELINE;
				velocityY = 0;
				// Relaunch if a press was buffered just before landing, or if jump is still
				// held - the original re-jumps on held keys too (via OS key repeat), and it's
				// what makes dense high-speed sections survivable.
				if (jumpBuffer > 0 || jumpHeld) {
					launch();
				}
			}
		}
		jumpBuffer = Math.max(0, jumpBuffer - dt);

		// ---- 3. world scroll ----
		speed = Math.min(t.maxSpeed, speed + t.acceleration * dt);
		double step = speed * dt;
		distance += step;

		// Leg animation swaps faster as the world speeds up.
		double legPeriod = Math.max(0.05, Math.min(0.14, 34 / speed));
		runAnimTimer += dt;
		if (runAnimTimer >= legPeriod) {
			runAnimTimer = 0;
			runFrameFlip = !runFrameFlip;
		}

		for (int i = obstacles.size() - 1; i >= 0; i--) {
			Obstacle o = obstacles.get(i);
			o.x -= (speed + o.speedOffset) * dt;
			if (o.kind == KIND_PTERO) {
				o.flapTimer += dt;
				if (o.flapTimer >= 0.24) {
					o.flapTimer = 0;
					o.flapFrame = !o.flapFrame;
				}
			}
			if (o.x + o.w < -12) {
				obstacles.remove(i);
			}
		}

		// Clouds drift at a fraction of world speed for cheap parallax.
		for (int i = clouds.size() - 1; i >= 0; i--) {
			Cloud c = clouds.get(i);
			c.x -= (speed * 0.3 + 8) * dt;
			if (c.x < -60) {
				clouds.remove(i);
			}
		}
		cloudTimer -= dt;
		if (cloudTimer <= 0 && clouds.size() < 4) {
			Cloud c = new Cloud();
			c.x = CANVAS_W + 30;
			c.y = 18 + random.nextDouble() * 62;
			clouds.add(c);
			cloudTimer = 2.5 + random.nextDouble() * 3.5;
		}

		// ---- 4. spawning ----
		distToNextSpawn -= step;
		if (distToNextSpawn <= 0) {
			spawnObstacle();
			// The gap to the NEXT obstacle scales with speed plus a random slice, so the
			// game gets denser as it gets faster but always stays clearable.
			distToNextSpawn = t.minObstacleGap
					+ speed * t.gapSpeedFactor
					+ random.nextDouble() * speed * t.gapRandomFactor;
		}

		// ---- 5. collision ----
		if (hitSomething()) {
			state = State.DEAD;
			deathTimer = 0;
			ducking = false; // death pose is always the standing sprite, like the original
			events.onDeath(score());
			return;
		}

		// ---- 6. score / milestones / night ----
		int milestone = score() / t.milestoneEvery;
		if (milestone > lastMilestone) {
			lastMilestone = milestone;
			scoreFlashTimer = 1.0;
			events.onMilestone(score());
		}
		scoreFlashTimer = Math.max(0, scoreFlashTimer - dt);

		// Day for the first `nightEvery` points, then night, then day again, forever.
		boolean nightTarget = (score() / t.nightEvery) % 2 == 1;
		double blendStep = 1.6 * dt;
		nightBlend = nightTarget ? Math.min(1, nightBlend + blendStep) : Math.max(0, nightBlend - blendStep);
	}

	private void launch() {
		velocityY = -t.jumpVelocity;
		feetY = BASELINE - 0.01; // leave the ground this frame
		jumpBuffer = 0;
		ducking = false;
		events.onJump();
	}

	/** Fresh run: everything resets except the ambient clouds. */
	private void restart() {
		state = State.RUNNING;
		speed = t.startSpeed;
		distance = 0;
		feetY = BASELINE;
		velocityY = 0;
		ducking = false;
		jumpBuffer = 0;
		obstacles.clear();
		distToNextSpawn = firstGap();
		lastMilestone = 0;
		scoreFlashTimer = 0;
		nightBlend = 0;
		deathTimer = 0;
	}

	/** Breathing room before the first obstacle of a run. */
	private double firstGap() {
		return 480 + random.nextDouble() * 200;
	}

	// =====================================================================
	// Spawning
	// =====================================================================

	private void spawnObstacle() {
		Obstacle o = new Obstacle();
		boolean pteroUnlocked = score() >= t.pteroMinScore;

		if (pteroUnlocked && random.nextDouble() < t.pteroChance) {
			o.kind = KIND_PTERO;
			o.count = 1;
			o.w = GameSprites.unitsWide(GameSprites.PTERO_A);
			o.h = GameSprites.unitsTall(GameSprites.PTERO_A);
			// Three flight lanes: 104 = ankle height (jump it, ducking dies),
			// 82 = head height (duck under it), 62 = high (just keep running).
			double roll = random.nextDouble();
			o.y = roll < 0.30 ? 104 : roll < 0.75 ? 82 : 62;
			// Slight speed variance so pteros don't feel glued to the ground scroll.
			o.speedOffset = switch (random.nextInt(3)) {
				case 0 -> -30;
				case 1 -> 0;
				default -> 36;
			};
		} else if (random.nextDouble() < 0.55) {
			o.kind = KIND_CACTUS_SMALL;
			// 1-3 small cacti in a row: 50% single, 30% double, 20% triple.
			double roll = random.nextDouble();
			o.count = roll < 0.5 ? 1 : roll < 0.8 ? 2 : 3;
			int spriteW = GameSprites.unitsWide(GameSprites.CACTUS_SMALL);
			o.w = o.count * spriteW - (o.count - 1) * GameSprites.CELL; // overlap 1 cell
			o.h = GameSprites.unitsTall(GameSprites.CACTUS_SMALL);
			o.y = BASELINE + 2 - o.h;
		} else {
			o.kind = KIND_CACTUS_LARGE;
			o.count = random.nextDouble() < 0.65 ? 1 : 2;
			int spriteW = GameSprites.unitsWide(GameSprites.CACTUS_LARGE);
			o.w = o.count * spriteW - (o.count - 1) * GameSprites.CELL;
			o.h = GameSprites.unitsTall(GameSprites.CACTUS_LARGE);
			o.y = BASELINE + 2 - o.h;
		}

		o.x = CANVAS_W + 6;
		obstacles.add(o);
	}

	// =====================================================================
	// Collision - forgiving boxes, tuned like the original so grazes don't kill
	// =====================================================================

	private boolean hitSomething() {
		// Dino hurtboxes for the current pose, as {x, y, w, h}.
		double[][] dino;
		if (ducking) {
			dino = new double[][] {
					{DINO_X + 4, BASELINE - DUCK_H + 4, DUCK_W - 10, DUCK_H - 8}
			};
		} else {
			double top = feetY - DINO_H;
			dino = new double[][] {
					{DINO_X + 20, top + 2, 20, 8},   // head
					{DINO_X + 4, top + 16, 22, 12},  // torso
					{DINO_X + 10, top + 30, 10, 9},  // legs
			};
		}

		for (Obstacle o : obstacles) {
			double[] box;
			if (o.kind == KIND_PTERO) {
				box = new double[] {o.x + 8, o.y + 12, o.w - 14, 8}; // body only, wings are safe
			} else {
				box = new double[] {o.x + 3, o.y + 2, o.w - 6, o.h - 2};
			}
			for (double[] d : dino) {
				if (d[0] < box[0] + box[2] && d[0] + d[2] > box[0]
						&& d[1] < box[1] + box[3] && d[1] + d[3] > box[1]) {
					return true;
				}
			}
		}
		return false;
	}

	// =====================================================================
	// Read-only view for the renderer
	// =====================================================================

	public State state() {
		return state;
	}

	public int score() {
		return (int) (distance * t.scoreRate);
	}

	/** Wall-clock seconds since the screen opened (for blinking UI). */
	public double time() {
		return time;
	}

	/** Y of the dino's feet in canvas units. */
	public double feetY() {
		return feetY;
	}

	public boolean ducking() {
		return ducking;
	}

	public boolean grounded() {
		return feetY >= BASELINE;
	}

	/** Which run frame the legs are on. */
	public boolean runFrameFlip() {
		return runFrameFlip;
	}

	public List<Obstacle> obstacles() {
		return obstacles;
	}

	public List<Cloud> clouds() {
		return clouds;
	}

	public double distance() {
		return distance;
	}

	public double speed() {
		return speed;
	}

	/** 0 = full day, 1 = full night. */
	public double nightBlend() {
		return nightBlend;
	}

	/** True while the score should blink from a milestone. */
	public boolean scoreFlashing() {
		return scoreFlashTimer > 0;
	}

	/** Seconds since death; the restart hint fades in after the lockout. */
	public double deathTimer() {
		return deathTimer;
	}
}
