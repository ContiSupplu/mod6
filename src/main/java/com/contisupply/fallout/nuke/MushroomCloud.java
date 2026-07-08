package com.contisupply.fallout.nuke;

import com.contisupply.fallout.FalloutConfig;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.particle.TintedParticleEffect;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

/**
 * The mushroom cloud - a scripted, purely server-side particle show, styled as a DENSE
 * GOLDEN FIREBALL CLOUD: an opaque, glowing amber mass rather than wispy smoke.
 *
 * ============================== HOW THE CLOUD IS BUILT ==============================
 *
 * The trick to an opaque *colored* cloud in vanilla is dust particles: they accept any
 * RGB color and render up to 4x size, so repainting a shape with a few hundred fat dust
 * particles per tick reads as one solid, roiling volume on camera. Each element below is
 * re-emitted every tick from simple parametric math around the detonation point:
 *
 *  1. FLASH (t = 0..8):    vanilla FLASH (tinted pure white) + a radial END_ROD spray.
 *                          One blinding frame for the camera.
 *  2. HEAD (whole show):   a solid ball of dust, filled uniformly via cube-root radius
 *                          sampling and slightly squashed vertically. It IS the fireball:
 *                          it inflates while easing up from the ground to cloudHeight,
 *                          then keeps swelling to the full cap radius. Color is chosen by
 *                          relative depth - bright gold core, amber body, brown rim - so
 *                          the ball shades itself like the reference footage. FLAME and
 *                          LAVA sprinkles inside keep it glowing; a tinted gold FLASH
 *                          pulses deep inside every two seconds while the cloud is young.
 *  3. COLLAR (t > rise):   the wide ledge of dust ringing the base of the head - the
 *                          signature skirt under a nuke's cap.
 *  4. STEM (t > 4):        a thick SOLID column of dust (radius 22% of the cap) from the
 *                          crater to the head, wobbling slowly so it looks alive, with
 *                          fire inside the lower third while the blast is young.
 *  5. BASE SURGE:          a dust ring that crawls outward along the ground from the
 *                          stem's foot, like the dust wall in real test footage.
 *  6. SKY DEBRIS + ASH:    single dark smoke specks scattered across a huge dome (the
 *                          black flecks in the reference shot) and slow ash-fall for the
 *                          back half of the show.
 *
 * Every particle is sent with force=true so the cloud renders at full filming distance
 * (default packets are dropped past ~32 blocks, which would gut the money shot).
 *
 * Budget: roughly 300-400 particles/tick at density 1.0, i.e. ~8-10k alive at steady
 * state - comfortably inside Minecraft's 16,384-particle engine cap. Keep
 * cloudParticleDensity at or below ~1.5, or the engine starts silently dropping
 * particles (including the flames and ash).
 * =====================================================================================
 */
final class MushroomCloud {
	/** FLASH is a tintable particle on 1.21.11 (ARGB, alpha ignored); we want pure white. */
	private static final ParticleEffect WHITE_FLASH =
			TintedParticleEffect.create(ParticleTypes.FLASH, 0xFFFFFFFF);
	/** Warm gold flash pulsed inside the young cloud for the inner-glow look. */
	private static final ParticleEffect GOLD_FLASH =
			TintedParticleEffect.create(ParticleTypes.FLASH, 0xFFFFD780);

	// The golden palette (tweak these hex values to restyle the whole cloud).
	private static final float DUST_SCALE = 3.6f;
	private static final ParticleEffect[] CORE = dusts(DUST_SCALE, 0xFFE879, 0xFFDC55, 0xFFF0A0);
	private static final ParticleEffect[] BODY = dusts(DUST_SCALE, 0xF2B53C, 0xE8A72E, 0xD9932A);
	private static final ParticleEffect[] RIM = dusts(3.2f, 0xB97B22, 0x9C6420, 0x8A5A1C);

	private MushroomCloud() {
	}

	private static ParticleEffect[] dusts(float scale, int... colors) {
		ParticleEffect[] effects = new ParticleEffect[colors.length];
		for (int i = 0; i < colors.length; i++) {
			effects[i] = new DustParticleEffect(colors[i], scale);
		}
		return effects;
	}

	private static ParticleEffect pick(Random random, ParticleEffect[] effects) {
		return effects[random.nextInt(effects.length)];
	}

	/** Sends a particle batch to every player, bypassing the 32-block server cull. */
	static void spawnForced(ServerWorld world, ParticleEffect effect,
			double x, double y, double z, int count,
			double dx, double dy, double dz, double speed) {
		for (ServerPlayerEntity player : world.getPlayers()) {
			world.spawnParticles(player, effect, true, true, x, y, z, count, dx, dy, dz, speed);
		}
	}

	static void tick(ServerWorld world, Vec3d center, int age, FalloutConfig cfg, Random random) {
		double density = cfg.cloudParticleDensity;
		int duration = cfg.cloudDurationTicks;
		double height = cfg.cloudHeight;
		double capRadius = cfg.cloudRadius;

		// How long the fireball takes to climb to cap height.
		int riseTicks = MathHelper.clamp(duration / 4, 40, 200);
		boolean young = age < riseTicks + 80;

		// --- 1. The flash ------------------------------------------------
		if (cfg.flashEnabled && age <= 8) {
			spawnForced(world, WHITE_FLASH,
					center.x, center.y + 4, center.z, 3, 6.0, 4.0, 6.0, 0);
			spawnForced(world, ParticleTypes.END_ROD,
					center.x, center.y + 2, center.z, scaled(60, density), 1.5, 1.5, 1.5, 0.55);
		}

		// --- 2. The head: one solid golden ball, rising then swelling ----
		double climb = easeOut(Math.min(1.0, (double) age / riseTicks));
		double headY = center.y + 4 + (height - 4) * climb;
		if (age > riseTicks) {
			headY += 2.0 * Math.sin(age * 0.03); // the mature head slowly breathes
		}
		double growth = age <= riseTicks
				? 0.35 * climb
				: 0.35 + 0.65 * easeOut((double) (age - riseTicks) / Math.max(1, duration - riseTicks));
		double headR = Math.max(4.0, capRadius * growth);

		int headBlobs = scaled(26, density);
		for (int i = 0; i < headBlobs; i++) {
			// Uniform point inside a ball (cube-root radius bias), squashed 15% vertically.
			double u = Math.cbrt(random.nextDouble());
			double theta = random.nextDouble() * MathHelper.TAU;
			double phi = Math.acos(2.0 * random.nextDouble() - 1.0);
			double rr = headR * u;
			double px = Math.sin(phi) * Math.cos(theta) * rr;
			double py = Math.cos(phi) * rr * 0.85;
			double pz = Math.sin(phi) * Math.sin(theta) * rr;

			// Shade by depth: bright core -> amber body -> brown rim. Young cloud burns brighter.
			ParticleEffect dust = u < (young ? 0.6 : 0.45) ? pick(random, CORE)
					: u > 0.82 ? pick(random, RIM)
					: pick(random, BODY);
			spawnForced(world, dust, center.x + px, headY + py, center.z + pz,
					4, 1.6, 1.4, 1.6, 0);
		}

		// Glow inside the head: flames always, lava + explosion bursts while climbing.
		spawnForced(world, ParticleTypes.FLAME,
				center.x, headY, center.z, scaled(8, density), headR * 0.45, headR * 0.35, headR * 0.45, 0.02);
		if (age <= riseTicks) {
			spawnForced(world, ParticleTypes.LAVA,
					center.x, headY, center.z, scaled(6, density), headR * 0.35, headR * 0.3, headR * 0.35, 0);
			if (age % 3 == 0) {
				spawnForced(world, ParticleTypes.EXPLOSION_EMITTER,
						center.x + spread(random, headR * 0.4), headY + spread(random, headR * 0.3),
						center.z + spread(random, headR * 0.4), 1, 0, 0, 0, 0);
			}
		}
		// The inner-glow pulse.
		if (age < duration / 2 && age % 40 == 20) {
			spawnForced(world, GOLD_FLASH, center.x, headY, center.z, 1, 0, 0, 0, 0);
		}

		// --- 3. The collar: the wide skirt ringing the head's base -------
		if (age > riseTicks) {
			int collarPoints = scaled(10, density);
			for (int i = 0; i < collarPoints; i++) {
				double angle = (MathHelper.TAU * i) / collarPoints + random.nextDouble() * 0.5;
				double cr = headR * (1.02 + random.nextDouble() * 0.3);
				spawnForced(world, random.nextBoolean() ? pick(random, RIM) : pick(random, BODY),
						center.x + Math.cos(angle) * cr,
						headY - headR * 0.25 + spread(random, 2.5),
						center.z + Math.sin(angle) * cr,
						3, 1.8, 1.2, 1.8, 0);
			}
		}

		// --- 4. The stem: a thick SOLID golden column ---------------------
		if (age > 4) {
			double stemR = capRadius * 0.22;
			double stemTop = Math.max(4.0, headY - center.y - headR * 0.5);

			int slices = scaled(12, density);
			for (int i = 0; i < slices; i++) {
				double h = random.nextDouble() * stemTop;
				// Slow lateral wobble so the column looks alive, not extruded.
				double wobble = 1.0 + 0.25 * Math.sin(h * 0.12 + age * 0.03);
				double rr = stemR * wobble * Math.sqrt(random.nextDouble());
				double angle = random.nextDouble() * MathHelper.TAU;
				ParticleEffect dust = rr > stemR * wobble * 0.7 ? pick(random, RIM)
						: young && rr < stemR * 0.35 ? pick(random, CORE)
						: pick(random, BODY);
				spawnForced(world, dust,
						center.x + Math.cos(angle) * rr, center.y + h, center.z + Math.sin(angle) * rr,
						4, 1.2, 1.6, 1.2, 0);
			}

			// Fire inside the lower third of the stem while the blast is young.
			if (young) {
				spawnForced(world, ParticleTypes.FLAME,
						center.x, center.y + random.nextDouble() * stemTop * 0.33, center.z,
						scaled(6, density), stemR * 0.5, 2.0, stemR * 0.5, 0.01);
			}

			// --- 5. Base surge: a dust wall crawling outward on the ground ---
			if (age < duration * 3 / 4) {
				double surgeR = Math.min(capRadius * 1.4, stemR * 2.0 + age * 0.12);
				int surgePoints = scaled(5, density);
				for (int i = 0; i < surgePoints; i++) {
					double angle = random.nextDouble() * MathHelper.TAU;
					spawnForced(world, pick(random, RIM),
							center.x + Math.cos(angle) * surgeR,
							center.y + 1.0 + random.nextDouble() * 2.5,
							center.z + Math.sin(angle) * surgeR,
							3, 1.6, 0.8, 1.6, 0);
				}
			}
		}

		// --- 6. Dark debris specks across the sky + ash-fall --------------
		if (age > 12) {
			int specks = scaled(5, density);
			for (int i = 0; i < specks; i++) {
				double angle = random.nextDouble() * MathHelper.TAU;
				double r = capRadius * (0.5 + random.nextDouble() * 2.3);
				spawnForced(world, ParticleTypes.LARGE_SMOKE,
						center.x + Math.cos(angle) * r,
						center.y + 8 + random.nextDouble() * height * 1.2,
						center.z + Math.sin(angle) * r,
						1, 0.3, 0.3, 0.3, 0.01);
			}
		}
		if (age > duration / 3) {
			spawnForced(world, ParticleTypes.ASH,
					center.x, center.y + height * 0.4, center.z,
					scaled(10, density), capRadius * 1.2, height * 0.35, capRadius * 1.2, 0);
			spawnForced(world, ParticleTypes.WHITE_ASH,
					center.x, center.y + height * 0.3, center.z,
					scaled(4, density), capRadius, height * 0.3, capRadius, 0);
		}
	}

	// ------------------------------------------------------------------

	private static int scaled(int base, double density) {
		return Math.max(1, (int) Math.round(base * density));
	}

	private static double spread(Random random, double magnitude) {
		return (random.nextDouble() * 2.0 - 1.0) * magnitude;
	}

	/** Cubic ease-out: fast start, gentle settle - reads as a real buoyant plume. */
	private static double easeOut(double t) {
		double inv = 1.0 - MathHelper.clamp(t, 0.0, 1.0);
		return 1.0 - inv * inv * inv;
	}
}
