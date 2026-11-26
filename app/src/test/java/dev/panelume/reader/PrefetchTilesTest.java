package dev.panelume.reader;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PrefetchTilesTest {
    @Test public void exactBoundaryAndPartialTile() {
        assertEquals(0,PrefetchTiles.firstUnseen(0,320));
        assertEquals(320,PrefetchTiles.firstUnseen(1,320));
        assertEquals(320,PrefetchTiles.firstUnseen(320,320));
        assertEquals(640,PrefetchTiles.firstUnseen(320.1,320));
        assertEquals(960,PrefetchTiles.firstUnseen(959.9,320));
    }
    @Test public void boundedAndAlignedForLongPanels() {
        assertEquals(2,PrefetchTiles.LIMIT);
        for(int i=0;i<10000;i++){
            double bottom=i*307.37;
            int top=PrefetchTiles.firstUnseen(bottom,320);
            assertEquals(0,top%320);
            assertTrue(top>=bottom);
            assertTrue(top-bottom<320);
        }
    }
}
