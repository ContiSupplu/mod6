package com.contisupply.fallout;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Config for the Nuke, stored as pretty-printed JSON in `config/fallout.json`.
 *
 * The file is (re)written on every game launch, so options added in newer versions of the
 * mod appear automatically and hand-typos fall back to defaults instead of crashing.
 * Every field is documented in the README; the short version is inline below.
 */
public class FalloutConfig {

	// ---------------- Trigger ----------------
	/** Arm the nuke when it receives redstone power. */
	public boolean triggerByRedstone = true;
	/** Arm the nuke when a player punches it (left click). */
	public boolean triggerByPunch = true;
	/** Countdown length after arming, in seconds. Beeping speeds up as it runs out. */
	public int countdownSeconds = 10;

	// ---------------- Blast ----------------
	/** Radius of the spherical blast, in blocks. THE dial for spectacle vs. performance. */
	public int blastRadius = 90;
	/** How many ticks the expanding shockwave takes to reach full radius (20 ticks = 1s). */
	public int shockwaveDurationTicks = 160;
	/**
	 * Hard cap on blocks destroyed per tick, shared across ALL live detonations.
	 * This is the anti-freeze valve: if a shell of the sphere holds more blocks than this,
	 * the wave simply pauses and resumes next tick.
	 */
	public int maxBlocksPerTick = 24000;
	/** Crater depth as a fraction of the blast radius (1.0 = full hemisphere). */
	public double craterDepthScale = 0.55;
	/** If another Nuke block is caught in the blast, it detonates too. */
	public boolean chainReaction = true;

	// ---------------- Scorched earth ----------------
	/** Thickness (blocks) of the charred shell left around the crater. */
	public double scorchedShellThickness = 2.5;
	/** Chance for each surviving surface block in that shell to be charred. */
	public double scorchChance = 0.85;
	/** Chance to leave burning fire on top of a freshly charred block. */
	public double lingeringFireChance = 0.08;

	// ---------------- Debris ----------------
	/** Chance that a destroyed surface block launches as flying debris instead of vanishing. */
	public double debrisChance = 0.035;
	/** Hard cap on debris entities per detonation (entities are far pricier than blocks). */
	public int debrisMax = 500;
	/** How hard debris is thrown outward/upward. */
	public double debrisLaunchPower = 1.6;

	// ---------------- Mushroom cloud ----------------
	public boolean cloudEnabled = true;
	/** Height of the cloud's cap above the detonation point, in blocks. */
	public int cloudHeight = 80;
	/** Final radius of the cap, in blocks. */
	public int cloudRadius = 34;
	/** How long the cloud keeps emitting particles (20 ticks = 1s). */
	public int cloudDurationTicks = 600;
	/** Multiplies every particle count. 1.0 = default, 2.0 = double density. */
	public double cloudParticleDensity = 1.0;

	// ---------------- Feel ----------------
	/** Blinding white flash at t=0. */
	public boolean flashEnabled = true;
	/** Physically rattles nearby players' cameras with tiny velocity jolts. */
	public boolean screenShake = true;
	/** Whether the passing shockwave damages and launches entities. */
	public boolean blastDamageEnabled = true;
	/** Damage at ground zero; falls off linearly to the blast edge. */
	public double blastDamageMax = 150.0;

	// ---------------- Radiation ----------------
	public boolean radiationEnabled = true;
	/** Radiation zone radius = blastRadius * this multiplier. */
	public double radiationRadiusMultiplier = 1.25;
	/** How long the crater stays irradiated, in seconds. */
	public int radiationDurationSeconds = 90;
	/** Wither amplifier applied inside the zone (0 = Wither I, 1 = Wither II, ...). */
	public int radiationStrength = 1;

	// ---------------------------------------------------------------

	private static FalloutConfig INSTANCE = new FalloutConfig();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "fallout.json";

	public static FalloutConfig get() {
		return INSTANCE;
	}

	public static void load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		try {
			if (Files.exists(path)) {
				FalloutConfig loaded = GSON.fromJson(Files.readString(path), FalloutConfig.class);
				if (loaded != null) {
					INSTANCE = loaded;
				}
			}
			INSTANCE.clamp();
			// Write back so newly added options (and corrected values) show up in the file.
			Files.writeString(path, GSON.toJson(INSTANCE));
		} catch (Exception e) {
			Fallout.LOGGER.error("[Fallout] Failed to read {} - using defaults", FILE_NAME, e);
			INSTANCE = new FalloutConfig();
			INSTANCE.clamp();
		}
	}

	/** Keep values inside sane bounds so a config typo can't hard-lock a server. */
	private void clamp() {
		countdownSeconds = clampInt(countdownSeconds, 1, 600);
		blastRadius = clampInt(blastRadius, 8, 256);
		shockwaveDurationTicks = clampInt(shockwaveDurationTicks, 10, 6000);
		maxBlocksPerTick = clampInt(maxBlocksPerTick, 500, 200000);
		craterDepthScale = clampDouble(craterDepthScale, 0.1, 1.0);
		scorchedShellThickness = clampDouble(scorchedShellThickness, 0.0, 8.0);
		scorchChance = clampDouble(scorchChance, 0.0, 1.0);
		lingeringFireChance = clampDouble(lingeringFireChance, 0.0, 1.0);
		debrisChance = clampDouble(debrisChance, 0.0, 1.0);
		debrisMax = clampInt(debrisMax, 0, 5000);
		debrisLaunchPower = clampDouble(debrisLaunchPower, 0.0, 10.0);
		cloudHeight = clampInt(cloudHeight, 10, 300);
		cloudRadius = clampInt(cloudRadius, 4, 200);
		cloudDurationTicks = clampInt(cloudDurationTicks, 20, 20000);
		cloudParticleDensity = clampDouble(cloudParticleDensity, 0.05, 8.0);
		blastDamageMax = clampDouble(blastDamageMax, 0.0, 10000.0);
		radiationRadiusMultiplier = clampDouble(radiationRadiusMultiplier, 0.1, 8.0);
		radiationDurationSeconds = clampInt(radiationDurationSeconds, 0, 36000);
		radiationStrength = clampInt(radiationStrength, 0, 9);
	}

	private static int clampInt(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	private static double clampDouble(double v, double min, double max) {
		return Math.max(min, Math.min(max, v));
	}
}
