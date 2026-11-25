package dev.panelume.reader;

/** Map the same base-grid boundary to a higher-resolution image without gaps. */
final class ZoomTiles {
    private ZoomTiles() {}
    static int boundary(int baseRow, int baseHeight, int detailHeight) {
        return (int) Math.round((double) baseRow * detailHeight / baseHeight);
    }
}
