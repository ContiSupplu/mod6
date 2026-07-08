package com.contisupply.dinorunner.client.screen;

import com.contisupply.dinorunner.client.DinoRunnerConfig;
import com.contisupply.dinorunner.client.HighScore;
import com.contisupply.dinorunner.client.game.DinoGame;
import com.contisupply.dinorunner.client.game.GameSprites;
import com.contisupply.dinorunner.client.game.GameTuning;
import com.contisupply.dinorunner.client.game.PixelFont;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The arcade cabinet screen: draws the whole game and feeds it input.
 *
 * Two deliberate design choices keep this class immune to the GUI/input API churn between
 * Minecraft versions:
 *
 *  1. INPUT IS POLLED, NOT EVENT-DRIVEN. 1.21.9 changed {@code keyPressed} to take a
 *     KeyInput object; instead of chasing that, we ask GLFW (the windowing library itself,
 *     whose API never moves) which keys are down each frame. Edge detection happens inside
 *     {@link DinoGame}. ESC still closes the screen through vanilla's own handling because
 *     we never override the key events.
 *
 *  2. EVERYTHING IS RECTANGLES. Sprites, text, ground, HUD - all drawn with
 *     {@code DrawContext.fill}, batched into row-runs. No textures, no font renderer.
 *     That's also what makes it look exactly like the original.
 *
 * Rendering happens in "canvas units" on the original's 600x150 canvas, mapped to the
 * screen with a single matrix transform (integer scale when it fits, shrink-to-fit
 * otherwise). The game simulation itself is advanced from render() with real delta-time,
 * so gameplay speed is independent of fps.
 */
public class DinoGameScreen extends Screen implements DinoGame.Events {

	// ---- palette (day -> night, blended smoothly) ----
	private static final int DAY_BG = 0xFFF7F7F7;
	private static final int NIGHT_BG = 0xFF16161C;
	private static final int DAY_FG = 0xFF535353;
	private static final int NIGHT_FG = 0xFFF0F0F0;
	// cabinet colours are fixed
	private static final int BACKDROP = 0xF20C0C10;
	private static final int CASING = 0xFF34343E;
	private static final int CASING_EDGE = 0xFF1D1D24;
	private static final int ACCENT = 0xFF2BC8C0;
	private static final int BEZEL = 0xFF0A0A0D;
	private static final int MARQUEE = 0xFFFFB000;
	private static final int HINT = 0xFF8E8E99;

	/** The 8-bit note block: the closest thing vanilla has to the original's square-wave beeps. */
	private static final SoundEvent BEEP = SoundEvent.of(Identifier.of("minecraft", "block.note_block.bit"));

	private final GameTuning tuning = DinoRunnerConfig.tuning();
	private final DinoGame game = new DinoGame(tuning, this);

	private long lastFrameNanos;
	/** Queued delayed beeps as {secondsLeft, pitch, volume} - used for double-beeps. */
	private final List<double[]> pendingBeeps = new ArrayList<>();
	private boolean newHighScore;

	public DinoGameScreen() {
		super(Text.literal("Dino Runner"));
	}

	// =====================================================================
	// Frame loop
	// =====================================================================

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		// --- real delta time, clamped so a lag spike can't teleport the dino into a cactus ---
		long now = System.nanoTime();
		double dt = lastFrameNanos == 0 ? 0 : (now - lastFrameNanos) / 1_000_000_000.0;
		lastFrameNanos = now;
		dt = Math.min(dt, 0.05);

		// --- poll input straight from GLFW (see class javadoc for why) ---
		long window = MinecraftClient.getInstance().getWindow().getHandle();
		boolean jumpHeld = keyDown(window, GLFW.GLFW_KEY_SPACE)
				|| keyDown(window, GLFW.GLFW_KEY_UP)
				|| keyDown(window, GLFW.GLFW_KEY_W)
				|| GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
		boolean duckHeld = keyDown(window, GLFW.GLFW_KEY_DOWN) || keyDown(window, GLFW.GLFW_KEY_S);

		// --- delayed beeps (milestone double-beep etc.) ---
		for (int i = pendingBeeps.size() - 1; i >= 0; i--) {
			double[] beep = pendingBeeps.get(i);
			beep[0] -= dt;
			if (beep[0] <= 0) {
				pendingBeeps.remove(i);
				beep((float) beep[1], (float) beep[2]);
			}
		}

		// --- advance the simulation ---
		game.update(dt, jumpHeld, duckHeld);

		// --- layout: integer scale when possible, shrink-to-fit on tiny GUI sizes ---
		float s = Math.min((width - 24) / (float) (DinoGame.CANVAS_W + 12),
				(height - 64) / (float) (DinoGame.CANVAS_H + 12));
		if (s >= 1) {
			s = Math.min(6, (float) Math.floor(s));
		}
		int canvasW = Math.round(DinoGame.CANVAS_W * s);
		int canvasH = Math.round(DinoGame.CANVAS_H * s);
		int ox = (width - canvasW) / 2;
		int oy = (height - canvasH) / 2 + Math.round(4 * s);

		// --- day/night palette blend ---
		double night = game.nightBlend();
		int bg = lerpColor(DAY_BG, NIGHT_BG, night);
		int fg = lerpColor(DAY_FG, NIGHT_FG, night);

		drawCabinet(context, ox, oy, canvasW, canvasH, s);

		// Everything inside the arcade screen is drawn in canvas units under one transform.
		Matrix3x2fStack matrices = context.getMatrices();
		matrices.pushMatrix();
		matrices.translate(ox, oy);
		matrices.scale(s, s);
		drawGameWorld(context, bg, fg, night);
		matrices.popMatrix();

		drawCanvasOverlays(context, ox, oy, canvasW, canvasH, s);
	}

	private static boolean keyDown(long window, int key) {
		return GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;
	}

	// =====================================================================
	// The game world (canvas-unit space, clipped to 600x150)
	// =====================================================================

	private void drawGameWorld(DrawContext ctx, int bg, int fg, double night) {
		// Desert sky/sand - one flat colour, exactly like the original.
		fillU(ctx, 0, 0, DinoGame.CANVAS_W, DinoGame.CANVAS_H, bg);

		double dist = game.distance();
		double time = game.time();

		// --- night sky: stars twinkle in, moon drifts slowly (parallax 5%) ---
		if (night > 0.02) {
			int starColor = withAlpha(0xFFF5F1CE, (int) (night * 230));
			for (int i = 0; i < 6; i++) {
				double sx = mod(i * 113 + 60 - dist * 0.08, DinoGame.CANVAS_W + 40) - 20;
				double sy = 10 + (i * 37) % 52;
				boolean twinkle = ((int) (time * 2) + i) % 3 == 0;
				drawSpriteU(ctx, twinkle ? GameSprites.STAR_B : GameSprites.STAR_A, sx, sy, starColor);
			}
			double moonX = mod(480 - dist * 0.05, DinoGame.CANVAS_W + 120) - 60;
			drawSpriteU(ctx, GameSprites.MOON, moonX, 14, withAlpha(0xFFF4F1DE, (int) (night * 255)));
		}

		// --- clouds: soft translucent blobs, slower than the ground (parallax) ---
		int cloudColor = withAlpha(fg, 70);
		for (DinoGame.Cloud cloud : game.clouds()) {
			drawSpriteU(ctx, GameSprites.CLOUD, cloud.x, cloud.y, cloudColor);
		}

		// --- ground: 2-unit line plus deterministic scrolling rubble ---
		fillU(ctx, 0, DinoGame.GROUND_LINE_Y, DinoGame.CANVAS_W, DinoGame.GROUND_LINE_Y + 2, fg);
		int firstSeg = (int) Math.floor(dist / 17.0);
		for (int k = firstSeg; k < firstSeg + 37; k++) {
			int h = hash(k);
			double x = k * 17 - dist;
			if ((h & 3) == 0) { // pebble below the line
				fillU(ctx, x, 137 + (h >> 4) % 7, x + 2 + ((h >> 2) & 1), 138 + (h >> 4) % 7, fg);
			}
			if ((h & 7) == 5) { // notch on the line itself
				fillU(ctx, x, DinoGame.GROUND_LINE_Y + 2, x + 4, DinoGame.GROUND_LINE_Y + 3, fg);
			}
		}

		// --- obstacles ---
		for (DinoGame.Obstacle o : game.obstacles()) {
			if (o.kind == DinoGame.KIND_PTERO) {
				drawSpriteU(ctx, o.flapFrame ? GameSprites.PTERO_A : GameSprites.PTERO_B, o.x, o.y, fg);
			} else {
				boolean[][] sprite = o.kind == DinoGame.KIND_CACTUS_SMALL
						? GameSprites.CACTUS_SMALL : GameSprites.CACTUS_LARGE;
				double step = GameSprites.unitsWide(sprite) - GameSprites.CELL;
				for (int i = 0; i < o.count; i++) {
					drawSpriteU(ctx, sprite, o.x + i * step, o.y, fg);
				}
			}
		}

		// --- the dino ---
		boolean[][] pose;
		double dinoTop;
		if (game.state() == DinoGame.State.DEAD) {
			pose = GameSprites.DINO_DEAD;
			dinoTop = game.feetY() - DinoGame.DINO_H;
		} else if (game.ducking()) {
			pose = game.runFrameFlip() ? GameSprites.DINO_DUCK_A : GameSprites.DINO_DUCK_B;
			dinoTop = DinoGame.BASELINE - DinoGame.DUCK_H;
		} else if (game.state() == DinoGame.State.READY) {
			pose = GameSprites.DINO_IDLE;
			dinoTop = game.feetY() - DinoGame.DINO_H;
		} else if (!game.grounded()) {
			pose = GameSprites.DINO_IDLE; // legs together mid-air, like the original
			dinoTop = game.feetY() - DinoGame.DINO_H;
		} else {
			pose = game.runFrameFlip() ? GameSprites.DINO_RUN_A : GameSprites.DINO_RUN_B;
			dinoTop = game.feetY() - DinoGame.DINO_H;
		}
		drawSpriteU(ctx, pose, DinoGame.DINO_X, dinoTop, fg);

		// --- HUD: HI 00420  00069, top right, original style ---
		int score = game.score();
		String scoreText = String.format("%05d", Math.min(score, 99999));
		double scoreX = DinoGame.CANVAS_W - 8 - PixelFont.widthCells(scoreText) * 2;
		// Milestone flash: the score blinks for a second.
		boolean hideScore = game.scoreFlashing() && ((int) (time * 8)) % 2 == 1;
		if (!hideScore) {
			drawTextU(ctx, scoreText, scoreX, 8, 2, fg);
		}
		int hi = Math.max(HighScore.get(), 0);
		if (hi > 0) {
			String hiText = "HI " + String.format("%05d", Math.min(hi, 99999));
			double hiX = scoreX - 14 - PixelFont.widthCells(hiText) * 2;
			drawTextU(ctx, hiText, hiX, 8, 2, withAlpha(fg, 140));
		}

		// --- state overlays ---
		if (game.state() == DinoGame.State.READY && ((int) (time * 1.6)) % 2 == 0) {
			centerTextU(ctx, "PRESS SPACE TO START", 56, 2, fg);
		}
		if (game.state() == DinoGame.State.DEAD) {
			centerTextU(ctx, "G A M E  O V E R", 40, 2, fg);
			double rw = GameSprites.unitsWide(GameSprites.RESTART);
			drawSpriteU(ctx, GameSprites.RESTART, (DinoGame.CANVAS_W - rw) / 2.0, 66, fg);
			if (newHighScore && ((int) (game.deathTimer() * 2.5)) % 2 == 0) {
				centerTextU(ctx, "NEW HIGH SCORE!", 104, 2, fg);
			}
		}
	}

	// =====================================================================
	// The cabinet around the screen (screen-pixel space)
	// =====================================================================

	private void drawCabinet(DrawContext ctx, int ox, int oy, int canvasW, int canvasH, float s) {
		// Dim the whole Minecraft world into an "arcade at night" backdrop.
		ctx.fill(0, 0, width, height, BACKDROP);

		int pad = Math.max(6, Math.round(5 * s)); // bezel gap between casing and canvas
		int x0 = ox - pad;
		int y0 = oy - pad;
		int x1 = ox + canvasW + pad;
		int y1 = oy + canvasH + pad;

		// casing -> dark edge -> cyan accent line -> black bezel -> canvas
		ctx.fill(x0 - 5, y0 - 5, x1 + 5, y1 + 5, CASING_EDGE);
		ctx.fill(x0 - 4, y0 - 4, x1 + 4, y1 + 4, CASING);
		ctx.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, ACCENT);
		ctx.fill(x0, y0, x1, y1, BEZEL);

		// Marquee above, hint line below - skipped if the window is too cramped.
		int marqueeCell = Math.max(2, Math.round(2 * s));
		int marqueeH = PixelFont.GLYPH_H * marqueeCell;
		int marqueeY = y0 - 10 - marqueeH;
		if (marqueeY > 4) {
			String title = "DINO RUNNER";
			int tw = PixelFont.widthCells(title) * marqueeCell;
			int tx = ox + (canvasW - tw) / 2;
			drawTextPx(ctx, title, tx + marqueeCell / 2 + 1, marqueeY + marqueeCell / 2 + 1,
					marqueeCell, 0x66000000); // soft drop shadow
			drawTextPx(ctx, title, tx, marqueeY, marqueeCell, MARQUEE);
		}

		int hintCell = Math.max(1, Math.round(s));
		int hintY = y1 + 8;
		if (hintY + PixelFont.GLYPH_H * hintCell < height - 2) {
			String hint = "SPACE: JUMP   DOWN: DUCK   ESC: QUIT";
			int hw = PixelFont.widthCells(hint) * hintCell;
			drawTextPx(ctx, hint, ox + (canvasW - hw) / 2, hintY, hintCell, HINT);
		}
	}

	/** CRT scanlines + vignette, drawn over the canvas in screen space. */
	private void drawCanvasOverlays(DrawContext ctx, int ox, int oy, int canvasW, int canvasH, float s) {
		if (tuning.crtScanlines && s >= 2) {
			int step = Math.max(2, Math.round(s));
			for (int y = oy + step - 1; y < oy + canvasH; y += step) {
				ctx.fill(ox, y, ox + canvasW, y + 1, 0x12000000);
			}
		}
		// Soft vignette: gradients top/bottom, thin strips on the sides.
		int edge = Math.max(4, canvasH / 10);
		ctx.fillGradient(ox, oy, ox + canvasW, oy + edge, 0x2E000000, 0x00000000);
		ctx.fillGradient(ox, oy + canvasH - edge, ox + canvasW, oy + canvasH, 0x00000000, 0x2E000000);
		int side = Math.max(2, canvasW / 60);
		ctx.fill(ox, oy, ox + side, oy + canvasH, 0x17000000);
		ctx.fill(ox + canvasW - side, oy, ox + canvasW, oy + canvasH, 0x17000000);
	}

	// =====================================================================
	// Canvas-unit drawing helpers (used inside the matrix transform)
	// =====================================================================

	/** Rect in canvas units, clipped to the 600x150 canvas so nothing bleeds onto the bezel. */
	private static void fillU(DrawContext ctx, double x0, double y0, double x1, double y1, int argb) {
		int ix0 = Math.max(0, (int) Math.round(x0));
		int iy0 = Math.max(0, (int) Math.round(y0));
		int ix1 = Math.min(DinoGame.CANVAS_W, (int) Math.round(x1));
		int iy1 = Math.min(DinoGame.CANVAS_H, (int) Math.round(y1));
		if (ix1 > ix0 && iy1 > iy0) {
			ctx.fill(ix0, iy0, ix1, iy1, argb);
		}
	}

	/**
	 * Blits a bitmap sprite at 2 units/cell, merging horizontal runs of pixels into single
	 * fill calls (a 22x20 dino is ~50 rects, not 250).
	 */
	private static void drawSpriteU(DrawContext ctx, boolean[][] sprite, double x, double y, int argb) {
		drawBitmapU(ctx, sprite, x, y, GameSprites.CELL, argb);
	}

	private static void drawBitmapU(DrawContext ctx, boolean[][] bitmap, double x, double y,
			double cell, int argb) {
		for (int row = 0; row < bitmap.length; row++) {
			boolean[] line = bitmap[row];
			int col = 0;
			while (col < line.length) {
				if (!line[col]) {
					col++;
					continue;
				}
				int runStart = col;
				while (col < line.length && line[col]) {
					col++;
				}
				fillU(ctx, x + runStart * cell, y + row * cell, x + col * cell, y + (row + 1) * cell, argb);
			}
		}
	}

	private static void drawTextU(DrawContext ctx, String text, double x, double y, double cell, int argb) {
		for (int i = 0; i < text.length(); i++) {
			boolean[][] glyph = PixelFont.glyph(text.charAt(i));
			if (glyph != null) {
				drawBitmapU(ctx, glyph, x + i * PixelFont.ADVANCE * cell, y, cell, argb);
			}
		}
	}

	private static void centerTextU(DrawContext ctx, String text, double y, double cell, int argb) {
		drawTextU(ctx, text, (DinoGame.CANVAS_W - PixelFont.widthCells(text) * cell) / 2, y, cell, argb);
	}

	/** Unclipped screen-pixel text for the marquee and hints (outside the canvas). */
	private static void drawTextPx(DrawContext ctx, String text, int x, int y, int cell, int argb) {
		for (int i = 0; i < text.length(); i++) {
			boolean[][] glyph = PixelFont.glyph(text.charAt(i));
			if (glyph == null) {
				continue;
			}
			int gx = x + i * PixelFont.ADVANCE * cell;
			for (int row = 0; row < glyph.length; row++) {
				int col = 0;
				while (col < glyph[row].length) {
					if (!glyph[row][col]) {
						col++;
						continue;
					}
					int runStart = col;
					while (col < glyph[row].length && glyph[row][col]) {
						col++;
					}
					ctx.fill(gx + runStart * cell, y + row * cell, gx + col * cell, y + (row + 1) * cell, argb);
				}
			}
		}
	}

	// =====================================================================
	// Colour + math helpers
	// =====================================================================

	private static int lerpColor(int a, int b, double t) {
		int aa = (a >>> 24) + (int) (((b >>> 24) - (a >>> 24)) * t);
		int ar = ((a >> 16) & 0xFF) + (int) ((((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
		int ag = ((a >> 8) & 0xFF) + (int) ((((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
		int ab = (a & 0xFF) + (int) (((b & 0xFF) - (a & 0xFF)) * t);
		return (aa << 24) | (ar << 16) | (ag << 8) | ab;
	}

	private static int withAlpha(int argb, int alpha) {
		return (Math.max(0, Math.min(255, alpha)) << 24) | (argb & 0xFFFFFF);
	}

	private static double mod(double v, double m) {
		double r = v % m;
		return r < 0 ? r + m : r;
	}

	/** Cheap integer hash for deterministic ground rubble. */
	private static int hash(int k) {
		int h = k * 0x9E3779B9;
		h ^= h >>> 16;
		return h & 0x7FFFFFFF;
	}

	// =====================================================================
	// Game events -> sound + high score
	// =====================================================================

	@Override
	public void onJump() {
		beep(1.6f, 0.5f);
	}

	@Override
	public void onMilestone(int score) {
		// The original's little "beep-beep" every 100 points.
		beep(2.0f, 0.45f);
		pendingBeeps.add(new double[] {0.13, 2.0f, 0.45f});
	}

	@Override
	public void onDeath(int score) {
		beep(0.5f, 0.8f);
		newHighScore = HighScore.submit(score);
	}

	private void beep(float pitch, float volume) {
		float v = (float) (volume * tuning.soundVolume);
		if (v <= 0.001f) {
			return;
		}
		MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.ui(BEEP, pitch, v));
	}

	// =====================================================================
	// Screen behaviour
	// =====================================================================

	/** Don't lose an in-progress record when the player quits with ESC mid-run. */
	@Override
	public void close() {
		HighScore.submit(game.score());
		super.close();
	}
}
