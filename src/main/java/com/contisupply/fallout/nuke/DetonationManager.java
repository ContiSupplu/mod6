package com.contisupply.fallout.nuke;

import com.contisupply.fallout.FalloutConfig;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns every live {@link Detonation} and {@link RadiationZone}, and drives them from the
 * END_WORLD_TICK event.
 *
 * Two invariants keep the server alive no matter what the player does:
 *
 *  1. The per-tick block budget ({@code maxBlocksPerTick}) is SHARED across all live
 *     detonations in a world. Set off five nukes at once and each gets a fifth of the
 *     budget - the total work per tick never grows.
 *
 *  2. New detonations (including chain reactions triggered from inside a running
 *     detonation's tick) go into a pending list first and only join the active list at
 *     the start of the next tick, so we never mutate a list we are iterating.
 */
public final class DetonationManager {
	private static final Map<RegistryKey<World>, List<Detonation>> PENDING = new HashMap<>();
	private static final Map<RegistryKey<World>, List<Detonation>> ACTIVE = new HashMap<>();
	private static final Map<RegistryKey<World>, List<RadiationZone>> RADIATION = new HashMap<>();

	private DetonationManager() {
	}

	/** Queue a nuke going off at {@code center}. Safe to call from anywhere on the server thread. */
	public static void start(ServerWorld world, BlockPos center) {
		FalloutConfig cfg = FalloutConfig.get();
		PENDING.computeIfAbsent(world.getRegistryKey(), k -> new ArrayList<>())
				.add(new Detonation(world, center, cfg));
		if (cfg.radiationEnabled) {
			RADIATION.computeIfAbsent(world.getRegistryKey(), k -> new ArrayList<>())
					.add(new RadiationZone(center, cfg));
		}
	}

	/** Called at the end of every world tick. */
	public static void tick(ServerWorld world) {
		RegistryKey<World> key = world.getRegistryKey();

		// Promote pending detonations queued last tick (or by chain reactions this tick).
		List<Detonation> pending = PENDING.remove(key);
		if (pending != null) {
			ACTIVE.computeIfAbsent(key, k -> new ArrayList<>()).addAll(pending);
		}

		List<Detonation> active = ACTIVE.get(key);
		if (active != null && !active.isEmpty()) {
			// Split the global destruction budget evenly between live detonations.
			int share = Math.max(200, FalloutConfig.get().maxBlocksPerTick / active.size());
			active.removeIf(detonation -> detonation.tick(share));
		}

		List<RadiationZone> zones = RADIATION.get(key);
		if (zones != null && !zones.isEmpty()) {
			zones.removeIf(zone -> zone.tick(world));
		}
	}

	/** Forget everything (server shutdown). In-flight explosions simply stop. */
	public static void clear() {
		PENDING.clear();
		ACTIVE.clear();
		RADIATION.clear();
	}
}
