package com.contisupply.dinorunner.client.game;

import java.util.HashMap;
import java.util.Map;

/**
 * A tiny 5x7 pixel font (A-Z, 0-9, and a little punctuation), rendered the same way as
 * every other sprite in the game: as merged rectangles. This is what gives the score
 * counter and "GAME OVER" text the authentic chunky look of the original - and it means
 * the screen never touches Minecraft's text renderer, which has changed shape across
 * versions far more often than "draw a rectangle" has.
 *
 * Unknown characters render as blanks. Letters are upper-cased automatically.
 */
public final class PixelFont {

	/** Glyph width in cells. */
	public static final int GLYPH_W = 5;
	/** Glyph height in cells. */
	public static final int GLYPH_H = 7;
	/** Horizontal advance in cells (glyph + 1 cell of spacing). */
	public static final int ADVANCE = GLYPH_W + 1;

	private static final Map<Character, boolean[][]> GLYPHS = new HashMap<>();

	private PixelFont() {
	}

	private static void def(char c, String... rows) {
		if (rows.length != GLYPH_H) {
			throw new IllegalArgumentException("Glyph '" + c + "' has " + rows.length + " rows");
		}
		GLYPHS.put(c, GameSprites.parse(rows));
	}

	static {
		def('A', ".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#");
		def('B', "####.", "#...#", "#...#", "####.", "#...#", "#...#", "####.");
		def('C', ".###.", "#...#", "#....", "#....", "#....", "#...#", ".###.");
		def('D', "####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####.");
		def('E', "#####", "#....", "#....", "####.", "#....", "#....", "#####");
		def('F', "#####", "#....", "#....", "####.", "#....", "#....", "#....");
		def('G', ".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".###.");
		def('H', "#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#");
		def('I', "#####", "..#..", "..#..", "..#..", "..#..", "..#..", "#####");
		def('J', "..###", "...#.", "...#.", "...#.", "...#.", "#..#.", ".##..");
		def('K', "#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#");
		def('L', "#....", "#....", "#....", "#....", "#....", "#....", "#####");
		def('M', "#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#");
		def('N', "#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#", "#...#");
		def('O', ".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###.");
		def('P', "####.", "#...#", "#...#", "####.", "#....", "#....", "#....");
		def('Q', ".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#");
		def('R', "####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#");
		def('S', ".####", "#....", "#....", ".###.", "....#", "....#", "####.");
		def('T', "#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#..");
		def('U', "#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###.");
		def('V', "#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#..");
		def('W', "#...#", "#...#", "#...#", "#.#.#", "#.#.#", "##.##", "#...#");
		def('X', "#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#");
		def('Y', "#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#..");
		def('Z', "#####", "....#", "...#.", "..#..", ".#...", "#....", "#####");

		def('0', ".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###.");
		def('1', "..#..", ".##..", "..#..", "..#..", "..#..", "..#..", "#####");
		def('2', ".###.", "#...#", "....#", "..##.", ".#...", "#....", "#####");
		def('3', ".###.", "#...#", "....#", "..##.", "....#", "#...#", ".###.");
		def('4', "...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#.");
		def('5', "#####", "#....", "####.", "....#", "....#", "#...#", ".###.");
		def('6', ".###.", "#....", "#....", "####.", "#...#", "#...#", ".###.");
		def('7', "#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#...");
		def('8', ".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###.");
		def('9', ".###.", "#...#", "#...#", ".####", "....#", "....#", ".###.");

		def(':', ".....", "..#..", ".....", ".....", "..#..", ".....", ".....");
		def('.', ".....", ".....", ".....", ".....", ".....", ".....", "..#..");
		def('!', "..#..", "..#..", "..#..", "..#..", "..#..", ".....", "..#..");
		def('-', ".....", ".....", ".....", "#####", ".....", ".....", ".....");
		def('>', "#....", ".#...", "..#..", "...#.", "..#..", ".#...", "#....");
		def('+', ".....", "..#..", "..#..", "#####", "..#..", "..#..", ".....");
	}

	/** The bitmap for a character, or null for space/unknown. */
	public static boolean[][] glyph(char c) {
		return GLYPHS.get(Character.toUpperCase(c));
	}

	/** Width of a rendered string in cells (before scaling). */
	public static int widthCells(String text) {
		return text.isEmpty() ? 0 : text.length() * ADVANCE - 1;
	}
}
