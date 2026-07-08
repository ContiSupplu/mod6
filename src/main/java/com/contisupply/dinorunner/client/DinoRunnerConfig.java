package com.contisupply.dinorunner.client;

import com.contisupply.dinorunner.DinoRunner;
import com.contisupply.dinorunner.client.game.GameTuning;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads/saves the game's tuning values as pretty-printed JSON in `config/dinorunner.json`.
 * The file is rewritten on every launch so new options appear automatically and typos fall
 * back to defaults instead of crashing. All the actual values live in {@link GameTuning}.
 */
public final class DinoRunnerConfig {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "dinorunner.json";

	private static GameTuning tuning = new GameTuning();

	private DinoRunnerConfig() {
	}

	public static GameTuning tuning() {
		return tuning;
	}

	public static void load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		try {
			if (Files.exists(path)) {
				GameTuning loaded = GSON.fromJson(Files.readString(path), GameTuning.class);
				if (loaded != null) {
					tuning = loaded;
				}
			}
			tuning.clamp();
			Files.writeString(path, GSON.toJson(tuning));
		} catch (Exception e) {
			DinoRunner.LOGGER.error("[Dino Runner] Failed to read {} - using defaults", FILE_NAME, e);
			tuning = new GameTuning();
			tuning.clamp();
		}
	}
}
