package com.contisupply.dinorunner.client;

import com.contisupply.dinorunner.block.ArcadeMachineBlock;
import com.contisupply.dinorunner.client.screen.DinoGameScreen;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;

/**
 * Client entrypoint: loads tuning + high score and hands the Arcade Machine block a way
 * to open the game screen without the common code ever touching client classes.
 */
public class DinoRunnerClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		DinoRunnerConfig.load();
		HighScore.load();

		ArcadeMachineBlock.setClientGameOpener(
				() -> MinecraftClient.getInstance().setScreen(new DinoGameScreen()));
	}
}
