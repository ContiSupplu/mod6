package com.contisupply.fallout.nuke;

import com.contisupply.fallout.FalloutConfig;
import com.contisupply.fallout.ModContent;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.Blocks;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

/**
 * One nuclear detonation, spread across many ticks.
 *
 * ============================ HOW THE GRADUAL DESTRUCTION WORKS ============================
 *
 * Destroying a radius-90 sphere naively means ~3 million setBlockState calls in one tick -
 * that hard-freezes the server. Instead the blast is modelled as an EXPANDING SPHERICAL
 * SHELL (a shockwave):
 *
 *  - Each tick the wavefront radius advances by (finalRadius / shockwaveDurationTicks).
 *  - Only the thin shell between the previous radius and the new radius is processed that
 *    tick, so blocks vanish in a visible outward ripple instead of all at once.
 *  - {@link ShellCursor} iterates exactly the blocks of the current shell. For every (x, y)
 *    column inside the sphere it solves the z-range that lies inside the shell analytically
 *    (two square roots), so we never brute-force scan the full cube.
 *  - A hard budget caps how many blocks may actually be CHANGED per tick
 *    ({@code maxBlocksPerTick}, shared across simultaneous detonations by the manager).
 *    If a shell is too rich - e.g. the wave is deep underground - the cursor simply
 *    pauses mid-shell and resumes where it left off next tick. The wave stalls for a
 *    moment; the server does not.
 *  - Block removal uses NOTIFY_LISTENERS | FORCE_STATE | SKIP_DROPS: clients get told,
 *    but we skip item drops and neighbour-update cascades, which are the two biggest
 *    hidden costs of mass block removal.
 *
 * The crater is shaped by a second, "normalized" distance check: below the detonation
 * point the y-offset is divided by {@code craterDepthScale}, squashing the destruction
 * sphere into a bowl. Blocks just OUTSIDE the bowl (within {@code scorchedShellThickness})
 * are converted to a charred palette instead of destroyed - that's the permanent, ugly,
 * irradiated look - with optional lingering fires.
 * ===========================================================================================
 */
public class Detonation {
	/** Charred blocks left on the crater surface. Weighted towards blackstone. */
	private static final BlockState[] SCORCH_PALETTE = {
			Blocks.BLACKSTONE.getDefaultState(),
			Blocks.BLACKSTONE.getDefaultState(),
			Blocks.COBBLED_DEEPSLATE.getDefaultState(),
			Blocks.BASALT.getDefaultState(),
			Blocks.COAL_BLOCK.getDefaultState(),
			Blocks.MAGMA_BLOCK.getDefaultState()
	};

	private static final int SET_BLOCK_FLAGS =
			Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS;

	private final ServerWorld world;
	private final BlockPos center;
	private final Vec3d centerVec;
	private final FalloutConfig cfg;
	private final Random random = Random.create();

	// Geometry, precomputed once.
	private final double radius;          // destruction radius
	private final double radiusSq;
	private final double outerLimit;      // destruction radius + scorched shell
	private final double outerLimitSq;
	private final double depthScale;      // crater depth as fraction of radius
	private final double waveSpeed;       // blocks the wavefront advances per tick
	private final int dyMin, dyMax;       // vertical offsets clamped to world height

	private int age = -1;                 // ticks since detonation started
	private double wavefront = 0;         // radius already claimed by the cursor
	private double lastEntityWave = 0;    // radius up to which entities were already hit
	private ShellCursor cursor;
	private boolean shockwaveDone = false;
	private int debrisSpawned = 0;

	// Chunk-loaded cache: shells sweep along z, so consecutive positions share a chunk.
	private int cachedChunkX = Integer.MIN_VALUE, cachedChunkZ = Integer.MIN_VALUE;
	private boolean cachedChunkLoaded = false;

	private final BlockPos.Mutable cursorPos = new BlockPos.Mutable();
	private final BlockPos.Mutable scratchPos = new BlockPos.Mutable();

	Detonation(ServerWorld world, BlockPos center, FalloutConfig cfg) {
		this.world = world;
		this.center = center.toImmutable();
		this.centerVec = Vec3d.ofCenter(center);
		this.cfg = cfg;
		this.radius = cfg.blastRadius;
		this.radiusSq = radius * radius;
		this.outerLimit = radius + cfg.scorchedShellThickness;
		this.outerLimitSq = outerLimit * outerLimit;
		this.depthScale = cfg.craterDepthScale;
		this.waveSpeed = outerLimit / Math.max(10, cfg.shockwaveDurationTicks);
		this.dyMin = Math.max((int) -Math.ceil(outerLimit), world.getBottomY() - center.getY());
		this.dyMax = Math.min((int) Math.ceil(outerLimit), world.getTopYInclusive() - center.getY());
	}

	/**
	 * Advances the detonation by one tick.
	 *
	 * @param blockBudget max blocks this detonation may change this tick
	 * @return true when completely finished (wave done AND cloud finished emitting)
	 */
	boolean tick(int blockBudget) {
		age++;

		if (age == 0) {
			initialBlast();
		}

		if (!shockwaveDone) {
			advanceShockwave(blockBudget);
			damageEntitiesInWaveBand();
			waveAmbience();
		}

		if (cfg.cloudEnabled && age <= cfg.cloudDurationTicks) {
			MushroomCloud.tick(world, centerVec, age, cfg, random);
		}

		if (cfg.screenShake && age < 80 && (age & 1) == 0) {
			shakePlayers();
		}

		boolean cloudFinished = !cfg.cloudEnabled || age > cfg.cloudDurationTicks;
		return shockwaveDone && cloudFinished;
	}

	// ------------------------------------------------------------------
	// t = 0: the flash and the boom.
	// ------------------------------------------------------------------

	private void initialBlast() {
		// Layered boom: the sharp crack, a deep roar and rolling thunder.
		world.playSound(null, center, SoundEvents.ENTITY_GENERIC_EXPLODE.value(),
				SoundCategory.BLOCKS, 16.0f, 0.5f);
		world.playSound(null, center, SoundEvents.ENTITY_WITHER_SPAWN,
				SoundCategory.BLOCKS, 12.0f, 0.4f);
		world.playSound(null, center, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER,
				SoundCategory.BLOCKS, 16.0f, 0.5f);

		// Every player in the dimension hears at least a distant, muffled boom.
		for (ServerPlayerEntity player : world.getPlayers()) {
			if (player.squaredDistanceTo(centerVec) > 128 * 128) {
				world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_GENERIC_EXPLODE.value(),
						SoundCategory.BLOCKS, 4.0f, 0.45f);
			}
		}
	}

	// ------------------------------------------------------------------
	// The expanding shockwave (see class javadoc).
	// ------------------------------------------------------------------

	private void advanceShockwave(int blockBudget) {
		int changed = 0;
		// Even probing air costs a little, so cap total positions visited as well.
		int visited = 0;
		int visitCap = blockBudget * 8;

		while (changed < blockBudget && visited < visitCap) {
			if (cursor == null || cursor.exhausted) {
				if (wavefront >= outerLimit) {
					shockwaveDone = true;
					// The wave has fully played out; one last long rumble.
					world.playSound(null, center, SoundEvents.ENTITY_ENDER_DRAGON_GROWL,
							SoundCategory.BLOCKS, 10.0f, 0.4f);
					return;
				}
				// Claim the next shell [wavefront, wavefront + waveSpeed).
				double next = Math.min(wavefront + waveSpeed, outerLimit);
				cursor = new ShellCursor(wavefront, next, dyMin, dyMax);
				wavefront = next;
				continue;
			}

			if (!cursor.advance()) {
				continue; // cursor exhausted; loop re-checks and builds the next shell
			}

			visited++;
			if (processBlock(cursor.ox, cursor.oy, cursor.oz)) {
				changed++;
			}
		}
	}

	/**
	 * Handles a single block offset of the current shell.
	 *
	 * @return true if a block was actually changed (counts against the tick budget)
	 */
	private boolean processBlock(int dx, int dy, int dz) {
		cursorPos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);

		// Never touch (and never synchronously load!) unloaded chunks.
		int chunkX = cursorPos.getX() >> 4;
		int chunkZ = cursorPos.getZ() >> 4;
		if (chunkX != cachedChunkX || chunkZ != cachedChunkZ) {
			cachedChunkX = chunkX;
			cachedChunkZ = chunkZ;
			cachedChunkLoaded = world.getChunkManager().isChunkLoaded(chunkX, chunkZ);
		}
		if (!cachedChunkLoaded) {
			return false;
		}

		BlockState state = world.getBlockState(cursorPos);
		if (state.isAir()) {
			return false; // fast path: most of the shell above ground is sky
		}
		if (state.getHardness(world, cursorPos) < 0) {
			return false; // bedrock, end portals, ...
		}

		// Crater shaping: below the center, vertical distance is stretched so the
		// destruction sphere becomes a bowl of depth radius * craterDepthScale.
		double scaledDy = dy < 0 ? dy / depthScale : dy;
		double normDistSq = (double) dx * dx + (double) dz * dz + scaledDy * scaledDy;

		if (normDistSq <= radiusSq) {
			return destroyBlock(state, dx, dy, dz);
		} else if (normDistSq <= outerLimitSq) {
			return scorchBlock(state);
		}
		return false;
	}

	private boolean destroyBlock(BlockState state, int dx, int dy, int dz) {
		// Sympathetic detonation: nukes caught in the blast go off themselves.
		if (cfg.chainReaction && state.isOf(ModContent.NUKE)) {
			world.setBlockState(cursorPos, Blocks.AIR.getDefaultState(), SET_BLOCK_FLAGS);
			DetonationManager.start(world, cursorPos.toImmutable());
			return true;
		}

		// Debris: exposed blocks near the surface get launched as falling-block
		// entities for the "everything goes flying" look. Entities are expensive,
		// so this is chance-gated and hard-capped.
		if (debrisSpawned < cfg.debrisMax
				&& state.getFluidState().isEmpty()
				&& !(state.getBlock() instanceof BlockWithEntity)
				&& random.nextDouble() < cfg.debrisChance) {
			scratchPos.set(cursorPos).move(Direction.UP);
			if (world.isAir(scratchPos)) {
				launchDebris(state, dx, dy, dz);
				return true;
			}
		}

		world.setBlockState(cursorPos, Blocks.AIR.getDefaultState(), SET_BLOCK_FLAGS);
		return true;
	}

	private void launchDebris(BlockState state, int dx, int dy, int dz) {
		// spawnFromBlock removes the block itself and spawns the falling entity.
		FallingBlockEntity debris = FallingBlockEntity.spawnFromBlock(world, cursorPos, state);
		double dist = Math.max(1.0, Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz));
		double power = cfg.debrisLaunchPower * (0.6 + random.nextDouble() * 0.8);
		debris.setVelocity(
				dx / dist * power,
				0.5 + random.nextDouble() * 0.7 * cfg.debrisLaunchPower,
				dz / dist * power);
		debris.velocityDirty = true;
		debrisSpawned++;
	}

	private boolean scorchBlock(BlockState state) {
		if (!state.getFluidState().isEmpty() || random.nextDouble() >= cfg.scorchChance) {
			return false;
		}
		BlockState charred = SCORCH_PALETTE[random.nextInt(SCORCH_PALETTE.length)];
		world.setBlockState(cursorPos, charred, SET_BLOCK_FLAGS);

		// Lingering fires on the fresh char.
		if (random.nextDouble() < cfg.lingeringFireChance) {
			scratchPos.set(cursorPos).move(Direction.UP);
			if (world.isAir(scratchPos)) {
				world.setBlockState(scratchPos, Blocks.FIRE.getDefaultState(), SET_BLOCK_FLAGS);
			}
		}
		return true;
	}

	// ------------------------------------------------------------------
	// The wave hitting things: damage, knockback, dust and rumble.
	// ------------------------------------------------------------------

	/** Entities are hurt exactly once - the tick the wavefront band sweeps past them. */
	private void damageEntitiesInWaveBand() {
		if (!cfg.blastDamageEnabled || wavefront <= lastEntityWave) {
			return;
		}
		double bandMin = lastEntityWave;
		double bandMax = wavefront;
		lastEntityWave = wavefront;

		Box searchBox = Box.of(centerVec, bandMax * 2 + 4, bandMax * 2 + 4, bandMax * 2 + 4);
		DamageSource source = world.getDamageSources().explosion(null, null);

		for (LivingEntity entity : world.getEntitiesByClass(LivingEntity.class, searchBox, e -> true)) {
			double dist = Math.sqrt(entity.squaredDistanceTo(centerVec));
			if (dist < bandMin || dist >= bandMax) {
				continue;
			}
			double falloff = 1.0 - dist / outerLimit; // 1 at ground zero -> 0 at the edge
			float damage = (float) Math.max(4.0, cfg.blastDamageMax * falloff);
			entity.damage(world, source, damage);

			// Fling it away from ground zero.
			Vec3d away = new Vec3d(entity.getX() - centerVec.x, entity.getY() - centerVec.y,
					entity.getZ() - centerVec.z).normalize();
			double punch = 0.8 + 2.8 * falloff;
			entity.addVelocity(away.x * punch, 0.4 + punch * 0.35, away.z * punch);
			entity.velocityDirty = true;
		}
	}

	/** Dust ring + intermittent rumble that tracks the wavefront outward. */
	private void waveAmbience() {
		double ringRadius = wavefront * (radius / outerLimit);
		for (int i = 0; i < 10; i++) {
			double angle = random.nextDouble() * MathHelper.TAU;
			double x = centerVec.x + Math.cos(angle) * ringRadius;
			double z = centerVec.z + Math.sin(angle) * ringRadius;
			double y = centerVec.y + random.nextDouble() * 5.0 - 1.0;
			MushroomCloud.spawnForced(world, ParticleTypes.EXPLOSION, x, y, z, 1, 1.5, 1.5, 1.5, 0);
			MushroomCloud.spawnForced(world, ParticleTypes.LARGE_SMOKE, x, y, z, 4, 2.0, 2.5, 2.0, 0.02);
		}

		if (age % 8 == 0) {
			double angle = random.nextDouble() * MathHelper.TAU;
			scratchPos.set(
					center.getX() + (int) (Math.cos(angle) * ringRadius),
					center.getY(),
					center.getZ() + (int) (Math.sin(angle) * ringRadius));
			world.playSound(null, scratchPos, SoundEvents.ENTITY_GENERIC_EXPLODE.value(),
					SoundCategory.BLOCKS, 6.0f, 0.4f + random.nextFloat() * 0.3f);
		}
	}

	/** Tiny random velocity jolts = handheld-camera shake for nearby players. */
	private void shakePlayers() {
		double shakeRange = radius * 1.5;
		for (ServerPlayerEntity player : world.getPlayers()) {
			double dist = Math.sqrt(player.squaredDistanceTo(centerVec));
			if (dist > shakeRange || player.isSpectator()) {
				continue;
			}
			double strength = 0.06 * (1.0 - dist / shakeRange);
			player.addVelocity(
					(random.nextDouble() - 0.5) * strength,
					(random.nextDouble() - 0.5) * strength * 0.6,
					(random.nextDouble() - 0.5) * strength);
			player.velocityDirty = true;
		}
	}

	// ------------------------------------------------------------------
	// Shell iteration.
	// ------------------------------------------------------------------

	/**
	 * Iterates every integer offset whose distance from the center lies in [r0, r1),
	 * visiting each offset exactly once across the whole detonation (shells partition
	 * the sphere). Iteration order is column-major: for each (x, y) column the z-band
	 * inside the shell is solved directly:
	 *
	 *   h² = x² + y²                       (squared distance of the column axis)
	 *   band = { z : r0² <= h² + z² < r1² }
	 *        = ± [ sqrt(max(0, r0² - h²)) , sqrt(r1² - h²) )
	 *
	 * The cursor is a resumable state machine so the manager can stop it mid-shell
	 * when the tick budget runs out and continue seamlessly next tick.
	 */
	private static final class ShellCursor {
		private final double r0Sq, r1Sq;
		private final int rOuter;
		private final int dyMin, dyMax;

		// Current column and band position.
		private int dx, dy;
		private int z, zMax;
		private double hSq;
		private boolean columnReady = false;
		private boolean pendingMirror = false;
		boolean exhausted = false;

		// Output offset of the last successful advance().
		int ox, oy, oz;

		ShellCursor(double r0, double r1, int dyMin, int dyMax) {
			this.r0Sq = r0 * r0;
			this.r1Sq = r1 * r1;
			this.rOuter = (int) Math.ceil(r1);
			this.dyMin = dyMin;
			this.dyMax = dyMax;
			this.dx = -rOuter;
			this.dy = dyMin - 1; // nextColumn() pre-increments
		}

		/** Moves to the next offset in the shell. False once the shell is finished. */
		boolean advance() {
			while (!exhausted) {
				if (!columnReady) {
					nextColumn();
					continue;
				}

				// Second half of a symmetric pair: emit -z, then step forward.
				if (pendingMirror) {
					pendingMirror = false;
					oz = -z;
					stepZ();
					return true;
				}

				if (z > zMax) {
					columnReady = false;
					continue;
				}

				double dSq = hSq + (double) z * z;
				if (dSq >= r0Sq && dSq < r1Sq) {
					ox = dx;
					oy = dy;
					oz = z;
					if (z > 0) {
						pendingMirror = true; // -z comes next
					} else {
						stepZ();
					}
					return true;
				}
				stepZ();
			}
			return false;
		}

		private void stepZ() {
			z++;
			if (z > zMax) {
				columnReady = false;
			}
		}

		/** Finds the next (x, y) column that intersects the shell and sets up its z-band. */
		private void nextColumn() {
			while (true) {
				dy++;
				if (dy > dyMax) {
					dy = dyMin;
					dx++;
					if (dx > rOuter) {
						exhausted = true;
						return;
					}
				}

				hSq = (double) dx * dx + (double) dy * dy;
				if (hSq >= r1Sq) {
					continue; // column axis already outside the outer sphere
				}

				// Outer edge of the band (float-safe: nudge to the largest z with h²+z² < r1²).
				zMax = (int) Math.floor(Math.sqrt(r1Sq - hSq));
				while (hSq + (double) (zMax + 1) * (zMax + 1) < r1Sq) {
					zMax++;
				}
				while (zMax > 0 && hSq + (double) zMax * zMax >= r1Sq) {
					zMax--;
				}

				// Inner edge of the band (0 if the whole column is outside the inner sphere).
				if (hSq >= r0Sq) {
					z = 0;
				} else {
					z = (int) Math.floor(Math.sqrt(r0Sq - hSq));
					while (z > 0 && hSq + (double) (z - 1) * (z - 1) >= r0Sq) {
						z--; // nudge down while the previous z is still in-band
					}
				}

				columnReady = true;
				pendingMirror = false;
				return;
			}
		}
	}
}
