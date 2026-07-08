package com.contisupply.dinorunner.client;

import com.contisupply.dinorunner.DinoRunner;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The persistent high score: a single integer in `config/dinorunner_highscore.txt`.
 * Written the moment a run beats it (at death or when the screen closes), so a crash
 * can never eat a record.
 */
public final class HighScore {

	private static final String FILE_NAME = "dinorunner_highscore.txt";
	private static int best;

	private HighScore() {
	}

	public static int get() {
		return best;
	}

	/** Records the score if it's a new best. Returns true when it was. */
	public static boolean submit(int score) {
		if (score <= best) {
			return false;
		}
		best = score;
		try {
			Files.writeString(file(), Integer.toString(best));
		} catch (Exception e) {
			DinoRunner.LOGGER.error("[Dino Runner] Could not save high score", e);
		}
		return true;
	}

	public static void load() {
		try {
			Path path = file();
			if (Files.exists(path)) {
				best = Math.max(0, Integer.parseInt(Files.readString(path).trim()));
			}
		} catch (Exception e) {
			DinoRunner.LOGGER.error("[Dino Runner] Could not read high score - starting at 0", e);
			best = 0;
		}
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}
}
