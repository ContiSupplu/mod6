package com.contisupply.fallout.nuke;

import com.contisupply.fallout.FalloutConfig;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

/**
 * The lingering fallout: for a configurable time after the blast, anything alive near
 * the crater withers (radiation) and players get a queasy nausea wobble. Ash keeps
 * drifting down over the zone the whole time.
 *
 * Effects re-apply once a second, so leaving the zone lets the dose wear off - it's a
 * hazard area, not a death sentence. Creative and spectator players are exempt.
 */
public class RadiationZone {
	private final Vec3d center;
	private final double radius;
	private final int durationTicks;
	private final int strength;
	private final Random random = Random.create();

	private int age = 0;

	RadiationZone(BlockPos center, FalloutConfig cfg) {
		this.center = Vec3d.ofCenter(center);
		this.radius = cfg.blastRadius * cfg.radiationRadiusMultiplier;
		this.durationTicks = cfg.radiationDurationSeconds * 20;
		this.strength = cfg.radiationStrength;
	}

	/** @return true when the zone has decayed and should be removed. */
	boolean tick(ServerWorld world) {
		age++;

		if (age % 20 == 0) {
			irradiate(world);
		}
		ambientParticles(world);

		return age > durationTicks;
	}

	private void irradiate(ServerWorld world) {
		Box box = Box.of(center, radius * 2, radius * 2, radius * 2);
		for (LivingEntity entity : world.getEntitiesByClass(LivingEntity.class, box, e -> true)) {
			if (entity.getPos().squaredDistanceTo(center) > radius * radius) {
				continue;
			}
			if (entity instanceof PlayerEntity player && (player.isCreative() || player.isSpectator())) {
				continue;
			}
			// 100-tick effect refreshed every 20 ticks = continuous while inside the zone.
			entity.addStatusEffect(new StatusEffectInstance(StatusEffects.WITHER, 100, strength));
			if (entity instanceof PlayerEntity) {
				entity.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 200, 0));
			}
		}
	}

	/** Slow ash-fall and the occasional eerie soul wisp rising from the crater. */
	private void ambientParticles(ServerWorld world) {
		double angle = random.nextDouble() * MathHelper.TAU;
		double r = radius * Math.sqrt(random.nextDouble()) * 0.8;
		double x = center.x + Math.cos(angle) * r;
		double z = center.z + Math.sin(angle) * r;

		MushroomCloud.spawnForced(world, ParticleTypes.ASH,
				x, center.y + 6 + random.nextDouble() * 14, z, 5, 4.0, 5.0, 4.0, 0);

		if (age % 15 == 0) {
			MushroomCloud.spawnForced(world, ParticleTypes.SOUL,
					x, center.y + 1, z, 0, 0, 1, 0, 0.03);
		}
	}
}
