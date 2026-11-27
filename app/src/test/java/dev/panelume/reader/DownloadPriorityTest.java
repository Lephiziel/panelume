package dev.panelume.reader;
import android.content.Context;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class DownloadPriorityTest {
    private static final CatalogSource.Title TITLE=new CatalogSource.Title("xkcd","priority","Priority","https://xkcd.com/","","");
    private static CatalogSource.Episode episode(String id) {
        return new CatalogSource.Episode(id,id,"Chapter "+id,"https://xkcd.com/"+id+"/","",false);
    }

    @Test public void bulkYieldsAtBoundaryAndResumesAfterForeground() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        try (TestShelf fixture=new TestShelf(context)) {
            CatalogQueue q=new CatalogQueue(fixture.shelf);
            long series=q.enqueue(TITLE,Arrays.asList(episode("1"),episode("2")),true);
            CatalogQueue.Job first=q.next(true);
            assertNotNull(first); assertTrue(first.bulk);
            assertTrue(q.progress(first.id,2,10));
            assertFalse(q.yieldToInteractive(first.id));
            q.enqueue(TITLE,Collections.singletonList(episode("3")),false);
            assertTrue(q.yieldToInteractive(first.id));
            assertEquals(-1,q.runningMode(first.id));
            CatalogQueue.Job foreground=q.next(false);
            assertEquals("3",foreground.episode.id);
            assertFalse(foreground.bulk);
            assertFalse(q.yieldToInteractive(foreground.id));
            q.cancel(foreground.id);
            assertNull(q.next(false));
            CatalogQueue.Job resumed=q.next(true);
            assertEquals(first.id,resumed.id);
            assertEquals(0,resumed.current); assertEquals(0,resumed.total);
            assertEquals(2,q.jobs(series).size());
        }
    }

    @Test public void promotionDoesNotPreemptOrDuplicateCurrentDownload() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        try (TestShelf fixture=new TestShelf(context)) {
            CatalogQueue q=new CatalogQueue(fixture.shelf);
            long series=q.enqueue(TITLE,Collections.singletonList(episode("1")),true);
            CatalogQueue.Job current=q.next(true);
            q.progress(current.id,4,10);
            q.enqueue(TITLE,Collections.singletonList(episode("1")),false);
            q.enqueue(TITLE,Collections.singletonList(episode("2")),false);
            assertEquals(0,q.runningMode(current.id));
            assertFalse(q.yieldToInteractive(current.id));
            assertEquals(2,q.jobs(series).size());
            CatalogQueue.Job promoted=null;
            for (CatalogQueue.Job job:q.jobs(series)) if (job.id==current.id) promoted=job;
            assertNotNull(promoted); assertFalse(promoted.bulk);
            assertEquals(CatalogQueue.RUNNING,promoted.state);
            assertEquals(4,promoted.current);
            q.cancel(current.id);
            assertFalse(q.yieldToInteractive(current.id));
            assertEquals("2",q.next(false).episode.id);
        }
    }
}
