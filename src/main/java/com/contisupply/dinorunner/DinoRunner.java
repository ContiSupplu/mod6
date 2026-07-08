package com.contisupply.dinorunner;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dino Runner - the Chrome offline T-Rex game as a playable arcade cabinet.
 *
 * Structure:
 *  - {@link ModContent}: registers the Arcade Machine block + item (common, runs everywhere).
 *  - {@link com.contisupply.dinorunner.client.DinoRunnerClient}: client entrypoint that wires
 *    right-click -> open the game screen.
 *  - {@link com.contisupply.dinorunner.client.game.DinoGame}: the whole simulation, pure Java.
 *  - {@link com.contisupply.dinorunner.client.screen.DinoGameScreen}: renders it and reads keys.
 *
 * The server never loads any client class: the block only exposes a {@code Runnable} hook that
 * stays null on dedicated servers, so the mod is safe to install on both sides.
 */
public class DinoRunner implements ModInitializer {
	public static final String MOD_ID = "dinorunner";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ModContent.register();
		LOGGER.info("[Dino Runner] Arcade Machine registered. Insert coin.");
	}
}
