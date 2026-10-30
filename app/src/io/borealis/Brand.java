package io.borealis;

/**
 * The brand colors in one place (White #FFFFFF, Night #171519, Lily #B79CFE, Peach #E99794, Melon #FE9565).
 * Everything that draws with them reads from here, so a future theme (another accent color) changes only this file.
 */
final class Brand {
    private Brand() {}

    static final int LILY = 0xFFB79CFE, PEACH = 0xFFE99794, MELON = 0xFFFE9565, INK = 0xFF171519, WHITE = 0xFFFFFFFF;

    /** The three accents in order, for the gradients (progress bars, buttons). */
    static int[] accents() { return new int[] {LILY, PEACH, MELON}; }

    /** Night with the given opacity (0–255): veils, bars and pills over pictures. */
    static int ink(int alpha) { return (alpha << 24) | (INK & 0x00FFFFFF); }
}
