package com.contisupply.dinorunner.client.game;

/**
 * Every difficulty/feel dial in one place. This object is serialized verbatim to
 * `config/dinorunner.json`, so players can retune the game without recompiling.
 *
 * Units: the game canvas is 600x150 "game pixels" (same as the original Chrome game).
 * Speeds are game-pixels per second, acceleration is game-pixels per second squared.
 * For reference, the original runs at ~6 px/frame at 60 fps = 360 px/s.
 */
public class GameTuning {

	// ---------------- Run speed ----------------
	/** Scroll speed at the start of a run. Original: ~360. */
	public double startSpeed = 360;
	/** Speed cap. Original: ~780. */
	public double maxSpeed = 750;
	/** How much speed is gained per second. Higher = ramps up harder. */
	public double acceleration = 4.2;

	// ---------------- Jump ----------------
	/** Downward pull, px/s^2. */
	public double gravity = 2250;
	/** Upward launch speed when jumping, px/s. */
	public double jumpVelocity = 650;
	/**
	 * Releasing jump early caps the remaining rise to this speed, giving short hops.
	 * Set to the same value as jumpVelocity to disable variable jump height.
	 */
	public double jumpCutVelocity = 280;
	/**
	 * Even the quickest tap always rises at least this many units before the early-release
	 * cut applies, so a tap-jump still clears a small cactus (the original's minJumpHeight).
	 */
	public double minJumpHeight = 45;
	/** Holding duck in mid-air multiplies gravity by this (the original's "speed drop"). */
	public double fastDropMultiplier = 2.6;

	// ---------------- Obstacles ----------------
	/** Minimum horizontal gap between obstacles, px. Lower = crueler. */
	public double minObstacleGap = 250;
	/** Extra gap proportional to current speed (gap grows as the game speeds up). */
	public double gapSpeedFactor = 0.32;
	/** Random extra gap, up to this fraction of current speed. */
	public double gapRandomFactor = 0.42;
	/** Pterodactyls only appear once the score passes this. Original: ~450. */
	public int pteroMinScore = 400;
	/** Chance (0-1) that a spawn is a pterodactyl once they're unlocked. */
	public double pteroChance = 0.22;

	// ---------------- Score ----------------
	/** Score gained per game-pixel of distance. 0.028 = ~10 points/s at start speed. */
	public double scoreRate = 0.028;
	/** Every N points: beep-beep and score flash, like the original. */
	public int milestoneEvery = 100;
	/** Day/night flips every N points (700, like the original). */
	public int nightEvery = 700;

	// ---------------- Presentation ----------------
	/** Master volume for the game's beeps, 0.0 - 1.0. 0 mutes. */
	public double soundVolume = 0.6;
	/** Subtle CRT scanline overlay on the arcade screen. Pure cosmetics. */
	public boolean crtScanlines = true;

	/** Keeps hand-edited configs inside sane bounds instead of crashing the game. */
	public void clamp() {
		startSpeed = clamp(startSpeed, 100, 2000);
		maxSpeed = clamp(maxSpeed, startSpeed, 3000);
		acceleration = clamp(acceleration, 0, 100);
		gravity = clamp(gravity, 300, 20000);
		jumpVelocity = clamp(jumpVelocity, 200, 3000);
		jumpCutVelocity = clamp(jumpCutVelocity, 50, jumpVelocity);
		minJumpHeight = clamp(minJumpHeight, 0, 120);
		fastDropMultiplier = clamp(fastDropMultiplier, 1, 10);
		minObstacleGap = clamp(minObstacleGap, 80, 2000);
		gapSpeedFactor = clamp(gapSpeedFactor, 0, 3);
		gapRandomFactor = clamp(gapRandomFactor, 0, 3);
		pteroMinScore = (int) clamp(pteroMinScore, 0, 1_000_000);
		pteroChance = clamp(pteroChance, 0, 1);
		scoreRate = clamp(scoreRate, 0.001, 1);
		milestoneEvery = (int) clamp(milestoneEvery, 10, 100_000);
		nightEvery = (int) clamp(nightEvery, 50, 1_000_000);
		soundVolume = clamp(soundVolume, 0, 1);
	}

	private static double clamp(double v, double min, double max) {
		return Math.max(min, Math.min(max, v));
	}
}
