package com.contisupply.fallout.nuke;

import com.contisupply.fallout.FalloutConfig;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.particle.TintedParticleEffect;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

/**
 * The mushroom cloud - a scripted, purely server-side particle show.
 *
 * ============================== HOW THE CLOUD IS BUILT ==============================
 *
 * The cloud is drawn fresh every tick from four layered elements, all positioned by
 * simple parametric math around the detonation point:
 *
 *  1. FLASH (t = 0..8):        vanilla FLASH particles + a radial END_ROD spray. One
 *                              blinding white frame for the camera.
 *  2. FIREBALL (t = 0..rise):  a ball of FLAME/LAVA particles that inflates while its
 *                              center eases upward from the ground to cloudHeight.
 *                              This ball *becomes* the head of the mushroom.
 *  3. STEM (t = 6..end):       campfire signal smoke (the longest-lived, most opaque
 *                              vanilla particle - ideal at filming distance) spawned in
 *                              a gently wobbling column between the ground and the cap.
 *                              A ground "base surge" skirt pushes smoke outward at the
 *                              foot of the column, like the real thing.
 *  4. CAP (t > rise):          a torus that expands from 30% to 100% of cloudRadius:
 *                              - an even RIM ring with outward-curling velocity
 *                                (count=0 particle packets encode a velocity vector),
 *                              - a randomized DOME fill above the rim (denser + higher
 *                                towards the middle, giving the classic silhouette),
 *                              - an UNDERSIDE skirt that curls back in below the rim.
 *
 * Every particle is sent with force=true so the cloud renders at full filming distance
 * (default packets are dropped past ~32 blocks, which would gut the money shot).
 * Total emission is a few hundred particles per tick and scales linearly with
 * cloudParticleDensity - spectacular on camera, trivial for the renderer.
 * =====================================================================================
 */
final class MushroomCloud {
	/** FLASH is a tintable particle on 1.21.11 (ARGB, alpha ignored); we want pure white. */
	private static final ParticleEffect WHITE_FLASH =
			TintedParticleEffect.create(ParticleTypes.FLASH, 0xFFFFFFFF);

	private MushroomCloud() {
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

		// --- 1. The flash ------------------------------------------------
		if (cfg.flashEnabled && age <= 8) {
			spawnForced(world, WHITE_FLASH,
					center.x, center.y + 4, center.z, 3, 6.0, 4.0, 6.0, 0);
			spawnForced(world, ParticleTypes.END_ROD,
					center.x, center.y + 2, center.z, scaled(60, density), 1.5, 1.5, 1.5, 0.55);
		}

		// --- 2. The rising fireball --------------------------------------
		if (age <= riseTicks) {
			double rise = easeOut((double) age / riseTicks);
			double ballY = center.y + 4 + (height - 4) * rise;
			double ballR = 5 + (capRadius * 0.45 - 5) * rise;

			spawnForced(world, ParticleTypes.FLAME,
					center.x, ballY, center.z, scaled(28, density), ballR * 0.5, ballR * 0.4, ballR * 0.5, 0.02);
			spawnForced(world, ParticleTypes.LAVA,
					center.x, ballY, center.z, scaled(6, density), ballR * 0.35, ballR * 0.3, ballR * 0.35, 0);
			if (age % 3 == 0) {
				spawnForced(world, ParticleTypes.EXPLOSION_EMITTER,
						center.x + spread(random, ballR * 0.4), ballY + spread(random, ballR * 0.3),
						center.z + spread(random, ballR * 0.4), 1, 0, 0, 0, 0);
			}
			// Dark smoke shroud around the fire so the ball reads as a roiling mass.
			spawnForced(world, ParticleTypes.LARGE_SMOKE,
					center.x, ballY, center.z, scaled(16, density), ballR * 0.6, ballR * 0.45, ballR * 0.6, 0.01);
		}

		// --- 3. The stem + base surge ------------------------------------
		if (age > 6) {
			double rise = easeOut(Math.min(1.0, (double) age / riseTicks));
			double columnTop = 4 + (height - 4) * rise;
			double stemR = capRadius * 0.16;

			int columnPoints = scaled(5, density);
			for (int i = 0; i < columnPoints; i++) {
				double h = random.nextDouble() * columnTop;
				// Slow lateral wobble so the column looks alive, not extruded.
				double wobble = 1.0 + 0.3 * Math.sin(h * 0.12 + age * 0.03);
				double r = stemR * wobble * (0.4 + random.nextDouble() * 0.6);
				double angle = random.nextDouble() * MathHelper.TAU;
				spawnForced(world, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE,
						center.x + Math.cos(angle) * r, center.y + h, center.z + Math.sin(angle) * r,
						2, 1.0, 1.6, 1.0, 0.012);
			}

			// Fire glow inside the lower third of the stem while the blast is young.
			if (age < riseTicks + 40) {
				spawnForced(world, ParticleTypes.FLAME,
						center.x, center.y + random.nextDouble() * columnTop * 0.33, center.z,
						scaled(6, density), stemR * 0.5, 2.0, stemR * 0.5, 0.01);
			}

			// Base surge: smoke pushed outward along the ground at the stem's foot.
			if (age < duration * 3 / 4) {
				int surgePoints = scaled(4, density);
				for (int i = 0; i < surgePoints; i++) {
					double angle = random.nextDouble() * MathHelper.TAU;
					double r = stemR * (1.5 + random.nextDouble() * 2.0);
					double px = center.x + Math.cos(angle) * r;
					double pz = center.z + Math.sin(angle) * r;
					// count = 0 -> the "delta" is a velocity direction, scaled by speed.
					spawnForced(world, ParticleTypes.CLOUD,
							px, center.y + 1.0 + random.nextDouble() * 2.0, pz,
							0, Math.cos(angle), 0.02, Math.sin(angle), 0.12);
				}
			}
		}

		// --- 4. The cap ---------------------------------------------------
		if (age > riseTicks) {
			double growth = easeOut((double) (age - riseTicks) / Math.max(1, duration - riseTicks));
			double capR = capRadius * (0.3 + 0.7 * growth);
			double capThick = capRadius * 0.35;
			// The whole head breathes up and down a couple of blocks.
			double capY = center.y + height + 2.0 * Math.sin(age * 0.03);

			// RIM: an even ring with jitter, plus outward-curling velocity particles.
			int rimPoints = scaled(10, density);
			for (int i = 0; i < rimPoints; i++) {
				double angle = (MathHelper.TAU * i) / rimPoints + random.nextDouble() * 0.4;
				double r = capR * (0.92 + random.nextDouble() * 0.12);
				double px = center.x + Math.cos(angle) * r;
				double pz = center.z + Math.sin(angle) * r;
				double py = capY + spread(random, 2.5);
				spawnForced(world, ParticleTypes.CAMPFIRE_COSY_SMOKE, px, py, pz, 2, 1.8, 1.5, 1.8, 0.01);
				// The signature curl: drift out and slightly down off the rim's edge.
				spawnForced(world, ParticleTypes.CLOUD, px, py, pz,
						0, Math.cos(angle), -0.25, Math.sin(angle), 0.1);
			}

			// DOME: fill above the rim; taller towards the center -> mushroom silhouette.
			int domePoints = scaled(8, density);
			for (int i = 0; i < domePoints; i++) {
				double r = capR * Math.sqrt(random.nextDouble());
				double angle = random.nextDouble() * MathHelper.TAU;
				double bulge = (1.0 - (r / capR) * (r / capR)) * capThick;
				spawnForced(world, ParticleTypes.LARGE_SMOKE,
						center.x + Math.cos(angle) * r,
						capY + random.nextDouble() * (2.0 + bulge),
						center.z + Math.sin(angle) * r,
						2, 2.2, 1.8, 2.2, 0.004);
			}

			// UNDERSIDE: a thinner skirt hanging below the rim, curling back inward.
			int skirtPoints = scaled(4, density);
			for (int i = 0; i < skirtPoints; i++) {
				double angle = random.nextDouble() * MathHelper.TAU;
				double r = capR * (0.5 + random.nextDouble() * 0.4);
				spawnForced(world, ParticleTypes.CAMPFIRE_COSY_SMOKE,
						center.x + Math.cos(angle) * r,
						capY - 3.0 - random.nextDouble() * 3.0,
						center.z + Math.sin(angle) * r,
						1, 1.5, 1.0, 1.5, 0.008);
			}

			// Young cap still burns from the inside.
			if (age < riseTicks + 80) {
				spawnForced(world, ParticleTypes.FLAME,
						center.x, capY, center.z, scaled(8, density), capR * 0.3, 2.0, capR * 0.3, 0.02);
			}
		}

		// --- Ash rain over the whole area for the back half of the show ---
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
