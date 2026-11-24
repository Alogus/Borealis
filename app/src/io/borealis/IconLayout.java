package io.borealis;

/** Geometry of the apps grid for a number of columns and a tile shape, in the 1920 x 1080 design space (as HomeView lays it out). */
final class IconLayout {
    final int cols, rows, shape;
    final float cellW, rowCell, pitch, tileW, tileH, tileR;

    private IconLayout(int cols, int shape) {
        this.cols = cols;
        this.shape = shape;
        rows = cols == 6 ? 4 : cols == 10 ? 6 : 5;
        cellW = (1728 - (cols - 1) * 24f) / cols;
        rowCell = (843 - (rows - 1) * 21f) / rows;
        pitch = rowCell + 21;
        float zone = rowCell - 39, tw, th, tr;
        if (shape == 0) {
            tw = cellW;
            th = cellW * 0.5625f;
            if (th > zone) {
                th = zone;
                tw = zone / 0.5625f;
            }
            tr = th * 0.2f;
        } else {
            tw = th = Math.min(Math.min(cellW * 0.5625f * 1.25f, cellW), Math.min(zone, cols == 6 ? 192f : 168f));
            tr = shape == 1 ? tw * 0.24f : tw / 2f;
        }
        tileW = tw;
        tileH = th;
        tileR = tr;
    }

    static IconLayout of(int cols, int shape) {
        return new IconLayout(cols, shape);
    }

    float tileX(int col) { return 96 + col * (cellW + 24) + (cellW - tileW) / 2f; }

    /** Top of a tile in the open grid. */
    float gridTileY(int row) { return 186 + (rowCell - 39 - tileH) / 2f + row * pitch; }

    /** Top of a tile on the shelf at rest (its center is at 873). */
    float homeTileY() { return 873 - tileH / 2f; }
}
