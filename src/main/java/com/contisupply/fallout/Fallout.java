package com.contisupply.fallout;

import com.contisupply.fallout.nuke.DetonationManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fallout - adds the Nuke.
 *
 * Everything interesting happens in three places:
 *  - {@link com.contisupply.fallout.block.NukeBlockEntity}: the armed countdown (beeps, flashing).
 *  - {@link com.contisupply.fallout.nuke.Detonation}: the tick-spread shockwave that carves the
 *    crater WITHOUT freezing the server (see the class javadoc for how).
 *  - {@link com.contisupply.fallout.nuke.MushroomCloud}: the particle script for the money shot.
 */
public class Fallout implements ModInitializer {
	public static final String MOD_ID = "fallout";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		FalloutConfig.load();
		ModContent.register();

		// All heavy lifting (shockwave, cloud, radiation) is driven from the end of each
		// world tick so we fully control how much work happens per tick.
		ServerTickEvents.END_WORLD_TICK.register(DetonationManager::tick);

		// Drop any in-flight detonation state when the server shuts down.
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> DetonationManager.clear());

		LOGGER.info("[Fallout] Nuke registered. Handle with care.");
	}
}
