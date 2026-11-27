package dev.panelume.reader;
import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
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
public final class NextChapterTest {
    private static final CatalogSource.Title TITLE=new CatalogSource.Title("xkcd","prepare-fixture","Fixture","https://xkcd.com/","","");
    private static CatalogSource.Episode ep(String id,boolean external) {
        return new CatalogSource.Episode(id,id,"Chapter "+id,"https://xkcd.com/"+id+"/","",external);
    }
    @Test public void exactFeedOrderExternalAndAmbiguousEntries() {
        CatalogSource.Episode a=ep("1",false), b=ep("2",true), c=ep("3",false);
        assertSame(b,NextChapter.after(Arrays.asList(a,b,c),"1").episode);
        assertNull(NextChapter.after(Arrays.asList(a,b,c),"3").episode);
        assertNull(NextChapter.after(Arrays.asList(a,b,c),"4"));
        assertNull(NextChapter.after(Arrays.asList(a,b,a),"1"));
        assertNull(NextChapter.after(Arrays.asList(a,b,b),"1"));
    }
    @Test public void preparationRequiresEveryGate() {
        assertTrue(NextChapter.canPrepare(75,true,true,true));
        assertFalse(NextChapter.canPrepare(74,true,true,true));
        assertFalse(NextChapter.canPrepare(75,false,true,true));
        assertFalse(NextChapter.canPrepare(75,true,false,true));
        assertFalse(NextChapter.canPrepare(75,true,true,false));
    }

    @Test public void thresholdNeedsRenderedContiguousProgress() {
        assertFalse(NextChapter.readyToPrepare(74,9,10,true));
        assertFalse(NextChapter.readyToPrepare(90,2,10,true));
        assertFalse(NextChapter.readyToPrepare(90,9,10,false));
        assertTrue(NextChapter.readyToPrepare(75,7.5,10,true));
    }
    @Test public void optInDefaultsOff() {
        Context c=RuntimeEnvironment.getApplication();
        c.getSharedPreferences("panelume_preferences",Context.MODE_PRIVATE).edit().remove("prepare_next").commit();
        assertFalse(Prefs.prepareNext(c));
        Prefs.prepareNext(c,true);
        assertTrue(Prefs.prepareNext(c));
        Prefs.prepareNext(c,false);
        assertFalse(Prefs.prepareNext(c));
    }
    private static long save(ShelfDb shelf,CatalogQueue queue,CatalogSource.Episode episode) throws Exception {
        File temp=File.createTempFile("prepare",".cbz",shelf.root.getParentFile());
        byte[] content={1,2,3};
        try {
            try(FileOutputStream out=new FileOutputStream(temp)){out.write(content);}
            queue.enqueue(TITLE,Collections.singletonList(episode),false);
            CatalogQueue.Job job=queue.next(true);
            assertNotNull(job);
            ImportFiles.Staged staged=new ImportFiles.Staged(temp,"fixture.cbz","cbz",LibraryBackup.hash(content),10,"Fixture","1","",new byte[]{4});
            return ImportFiles.store(shelf,staged,dir->queue.save(job,staged,dir));
        } finally {temp.delete();}
    }
    @Test public void oneBulkPreparationDoesNotDuplicateOrRetry() throws Exception {
        Context c=RuntimeEnvironment.getApplication();
        try(TestShelf fixture=new TestShelf(c)) {
            CatalogQueue q=new CatalogQueue(fixture.shelf);
            long id=save(fixture.shelf,q,ep("1",false));
            assertEquals(CatalogQueue.PREPARATION_EXISTS,q.enqueuePreparation(id,TITLE,ep("1",false)));
            assertEquals(CatalogQueue.PREPARATION_INVALID,q.enqueuePreparation(id,TITLE,ep("2",true)));
            q.enqueue(TITLE,Collections.singletonList(ep("9",false)),false);
            CatalogQueue.Job foreground=q.next(true);
            assertNotNull(foreground);
            assertEquals(CatalogQueue.PREPARATION_BUSY,q.enqueuePreparation(id,TITLE,ep("2",false)));
            q.cancel(foreground.id);
            assertEquals(CatalogQueue.PREPARATION_QUEUED,q.enqueuePreparation(id,TITLE,ep("2",false)));
            assertEquals(CatalogQueue.PREPARATION_EXISTS,q.enqueuePreparation(id,TITLE,ep("2",false)));
            assertEquals(CatalogQueue.PREPARATION_BUSY,q.enqueuePreparation(id,TITLE,ep("3",false)));
            assertNull(q.next(false));
            CatalogQueue.Job job=q.next(true);
            assertTrue(job.bulk);
            assertEquals("2",job.episode.id);
            q.fail(job.id,CatalogQueue.NETWORK);
            assertEquals(CatalogQueue.PREPARATION_EXISTS,q.enqueuePreparation(id,TITLE,ep("2",false)));
            assertEquals(CatalogQueue.FAILED,q.jobs(job.seriesId).get(0).state);
            q.cancel(job.id);
            long saved=save(fixture.shelf,q,ep("2",false));
            assertFalse(q.enqueuePreparation(id,TITLE,ep("2",false)));
            fixture.shelf.evict(saved);
            assertFalse(q.enqueuePreparation(id,TITLE,ep("2",false)));
            assertTrue(q.jobs(job.seriesId).isEmpty());
        }
    }
}
