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
    @Test public void crossesShortPageAndNeverScansPastTwoCandidates() {
        int[] heights={700,90,1200};
        java.util.List<Long> visited=new java.util.ArrayList<>();
        long key=PrefetchTiles.next(0,690,3,320,p->heights[p],k->{visited.add(k); return false;});
        assertEquals(-1,key);
        assertEquals(java.util.Arrays.asList(1L<<32,2L<<32),visited);
        assertEquals(2L<<32,PrefetchTiles.next(0,690,3,320,p->heights[p],k->k==(2L<<32)));
        assertEquals(-1,PrefetchTiles.next(2,1200,3,320,p->heights[p],k->true));
    }
    @Test public void doesNotSelectPartiallyVisibleOrCachedTiles() {
        assertEquals(640,PrefetchTiles.next(0,1,1,320,p->2000,k->k!=320));
        assertEquals(-1,PrefetchTiles.next(0,1,1,320,p->2000,k->k==960));
        assertEquals(320,PrefetchTiles.next(0,320,1,320,p->2000,k->true));
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
