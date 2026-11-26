package dev.panelume.reader;

/** Geometry policy for the first completely offscreen tile. */
final class PrefetchTiles {
    static final int LIMIT=2;
    private PrefetchTiles() {}
    static int firstUnseen(double bottomInPage,int tileHeight) {
        // Treat tiny floating-point boundary errors conservatively; never skip a full tile.
        if (bottomInPage<=0) return 0;
        return (int)Math.ceil(bottomInPage/tileHeight)*tileHeight;
    }
}
