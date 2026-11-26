package dev.panelume.reader;
import android.content.Context;
import java.io.*;
import java.time.LocalDate;
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
public final class OfflineEvictionTest {
    private static final byte[] COVER={5,6};
    private static final LocalDate DAY=LocalDate.of(2026,11,26);
    private static final CatalogSource.Title TITLE=new CatalogSource.Title("xkcd","fixture","Fixture","https://xkcd.com/","","");
    private static final CatalogSource.Episode EPISODE=new CatalogSource.Episode("one","1","One","https://xkcd.com/1/","",false);

    private static long save(ShelfDb shelf,CatalogQueue queue,int pages,byte[] content) throws Exception {
        File temporary=File.createTempFile("chapter",".cbz",shelf.root.getParentFile());
        try {
            try (FileOutputStream out=new FileOutputStream(temporary)) { out.write(content); }
            ImportFiles.Staged staged=new ImportFiles.Staged(temporary,"fixture.cbz","cbz",LibraryBackup.hash(content),pages,
                "Fixture","1","",COVER);
            queue.enqueue(TITLE,Collections.singletonList(EPISODE),false);
            CatalogQueue.Job job=queue.next(true);
            assertNotNull(job);
            return ImportFiles.store(shelf,staged,directory->queue.save(job,staged,directory));
        } finally { temporary.delete(); }
    }

    private static long withProgress(ShelfDb shelf,CatalogQueue queue) throws Exception {
        long id=save(shelf,queue,10,new byte[]{1,2,3});
        ShelfDb.Chapter chapter=shelf.chapter(id);
        Bookmark b=new Bookmark(chapter.bookmark(shelf.root));
        b.save(3,.25,32,3.25); b.flush();
        shelf.opened(id); shelf.finish(id);
        shelf.addTime(id,Collections.singletonMap(DAY,1200L));
        return id;
    }

    @Test public void evictAndRedownloadPreserveIdentityAndProgress() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        try (TestShelf fixture=new TestShelf(context)) {
            ShelfDb shelf=fixture.shelf;
            CatalogQueue queue=new CatalogQueue(shelf);
            long id=withProgress(shelf,queue);
            ShelfDb.Chapter before=shelf.chapter(id);
            queue.enqueue(TITLE,Collections.singletonList(EPISODE),false);
            assertTrue(queue.jobs(before.seriesId).isEmpty());
            shelf.evict(id);
            ShelfDb.Chapter evicted=shelf.chapter(id);
            assertFalse(evicted.available(shelf.root));
            assertTrue(shelf.cover(evicted.directory).isFile());
            assertEquals(3,Bookmark.read(evicted.bookmark(shelf.root)).page);
            assertEquals(1200,evicted.ms); assertTrue(evicted.done);
            assertEquals(1,OfflineStorage.scan(shelf).missing);
            assertEquals(id,save(shelf,queue,10,new byte[]{8,9,10}));
            ShelfDb.Chapter restored=shelf.chapter(id);
            assertTrue(restored.available(shelf.root));
            assertEquals(1,shelf.chapters(before.seriesId).size());
            assertEquals(before.opened,restored.opened);
            assertEquals(1200,restored.ms); assertTrue(restored.done);
            assertEquals(3,Bookmark.read(restored.bookmark(shelf.root)).page);
            assertEquals(.25,Bookmark.read(restored.bookmark(shelf.root)).fraction,.000001);
            assertTrue(queue.jobs(before.seriesId).isEmpty());
        }
    }

    @Test public void layoutChangeFailsWithoutLosingProgress() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        try (TestShelf fixture=new TestShelf(context)) {
            ShelfDb shelf=fixture.shelf; CatalogQueue queue=new CatalogQueue(shelf);
            long id=withProgress(shelf,queue);
            shelf.evict(id);
            try { save(shelf,queue,11,new byte[]{7,8}); fail("Layout change accepted"); }
            catch (Fault expected) { assertEquals(R.string.offline_chapter_changed,expected.message); }
            ShelfDb.Chapter old=shelf.chapter(id);
            assertFalse(old.available(shelf.root)); assertEquals(10,old.pages);
            assertTrue(old.done); assertEquals(1200,old.ms);
            assertEquals(3,Bookmark.read(old.bookmark(shelf.root)).page);
        }
    }

    @Test public void backupPreservesProgressOnlyChapter() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        try (TestShelf source=new TestShelf(context);TestShelf dest=new TestShelf(context)) {
            CatalogQueue queue=new CatalogQueue(source.shelf);
            long id=withProgress(source.shelf,queue);
            source.shelf.evict(id);
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            LibraryBackup.write(context,source.shelf,out);
            byte[] zip=out.toByteArray();
            LibraryBackup.Preview preview=LibraryBackup.preview(new ByteArrayInputStream(zip));
            assertEquals(1,preview.missing);
            LibraryBackup.restore(dest.shelf,new ByteArrayInputStream(zip),preview);
            ShelfDb.Chapter chapter=dest.shelf.chapter(id);
            assertNotNull(chapter); assertFalse(chapter.available(dest.shelf.root));
            assertTrue(chapter.done); assertEquals(1200,chapter.ms);
            assertEquals(3,Bookmark.read(chapter.bookmark(dest.shelf.root)).page);
            assertEquals(id,save(dest.shelf,new CatalogQueue(dest.shelf),10,new byte[]{9,8,7}));
            assertTrue(dest.shelf.chapter(id).available(dest.shelf.root));
        }
    }

    @Test public void bulkSelectionIsBoundToConfirmedArchivesAndProtectsReaders() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        try (TestShelf fixture=new TestShelf(context)) {
            ShelfDb shelf=fixture.shelf;
            CatalogQueue queue=new CatalogQueue(shelf);
            long id=withProgress(shelf,queue);
            ShelfDb.Chapter chapter=shelf.chapter(id);
            OfflineStorage.Report report=OfflineStorage.scan(shelf);
            assertEquals(3,report.reclaimable);
            OfflineStorage.Candidate selected=report.series.get(0).candidates.get(0);
            assertTrue(selected.done);
            assertNotNull(shelf.acquireReader(id));
            try {
                OfflineStorage.Result blocked=OfflineStorage.evict(shelf,Collections.singletonList(selected));
                assertEquals(1,blocked.failed);
                assertTrue(chapter.available(shelf.root));
            } finally { shelf.releaseReader(id); }
            OfflineStorage.Result result=OfflineStorage.evict(shelf,java.util.Arrays.asList(selected,selected));
            assertEquals(1,result.removed); assertEquals(3,result.bytes);
            assertEquals(0,result.failed);
            assertEquals(1200,shelf.chapter(id).ms);
            assertEquals(3,Bookmark.read(chapter.bookmark(shelf.root)).page);
            assertEquals(id,save(shelf,queue,10,new byte[]{9,8,7}));
            OfflineStorage.Result stale=OfflineStorage.evict(shelf,Collections.singletonList(selected));
            assertEquals(1,stale.skipped);
            assertTrue(shelf.chapter(id).available(shelf.root));
        }
    }

    @Test public void bulkPartialFailureDoesNotRemoveUnselectedOrLocalFiles() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        try (TestShelf fixture=new TestShelf(context)) {
            ShelfDb shelf=fixture.shelf;
            CatalogQueue queue=new CatalogQueue(shelf);
            long id=withProgress(shelf,queue);
            ShelfDb.Chapter original=shelf.chapter(id);
            CatalogSource.Episode other=new CatalogSource.Episode("two","2","Two","https://xkcd.com/2/","",false);
            CatalogSource.Episode third=new CatalogSource.Episode("three","3","Three","https://xkcd.com/3/","",false);
            queue.enqueue(TITLE,java.util.Arrays.asList(other,third),false);
            for (int i=0;i<2;i++) {
                CatalogQueue.Job job=queue.next(true);
                File temp=File.createTempFile("chapter",".cbz",shelf.root.getParentFile());
                byte[] content=new byte[]{4,5,6,7};
                try (FileOutputStream out=new FileOutputStream(temp)) { out.write(content); }
                ImportFiles.Staged staged=new ImportFiles.Staged(temp,"fixture.cbz","cbz",LibraryBackup.hash(content),10,"Fixture","","",COVER);
                ImportFiles.store(shelf,staged,directory->queue.save(job,staged,directory));
            }
            String localDir="11111111-1111-1111-1111-111111111111";
            long local=shelf.addChapter("Local","1","","local.cbz",localDir,"local-digest","cbz",1);
            ShelfDb.Chapter localChapter=shelf.chapters(local).get(0);
            assertTrue(new File(shelf.root,localDir).mkdirs());
            try (FileOutputStream out=new FileOutputStream(localChapter.file(shelf.root))) { out.write(new byte[50]); }
            OfflineStorage.Report report=OfflineStorage.scan(shelf);
            assertEquals(61,report.bytes); assertEquals(11,report.reclaimable);
            assertEquals("Local",report.series.get(0).title);
            assertTrue(report.series.get(0).candidates.isEmpty());
            java.util.List<OfflineStorage.Candidate> candidates=report.series.get(1).candidates;
            assertEquals(3,candidates.size());
            OfflineStorage.Candidate first=candidates.get(0), second=candidates.get(1), unselected=candidates.get(2);
            assertEquals(id,first.id);
            assertNotNull(shelf.acquireReader(first.id));
            try {
                OfflineStorage.Result result=OfflineStorage.evict(shelf,java.util.Arrays.asList(first,second));
                assertEquals(1,result.failed); assertEquals(1,result.removed); assertEquals(4,result.bytes);
            } finally { shelf.releaseReader(first.id); }
            assertTrue(original.available(shelf.root));
            assertFalse(shelf.chapter(second.id).available(shelf.root));
            assertTrue(shelf.chapter(unselected.id).available(shelf.root));
            assertTrue(localChapter.available(shelf.root));
            assertEquals(1200,shelf.chapter(id).ms);
        }
    }

    @Test public void localImportsCannotBeEvicted() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        try (TestShelf fixture=new TestShelf(context)) {
            ShelfDb shelf=fixture.shelf;
            String directory="11111111-1111-1111-1111-111111111111";
            long series=shelf.addChapter("Local","1","","local.cbz",directory,"digest","cbz",1);
            ShelfDb.Chapter chapter=shelf.chapters(series).get(0);
            assertTrue(new File(shelf.root,directory).mkdirs());
            try (FileOutputStream out=new FileOutputStream(chapter.file(shelf.root))) { out.write(1); }
            try { shelf.evict(chapter.id); fail("Local import evicted"); }
            catch (IOException expected) {}
            assertTrue(chapter.available(shelf.root));
        }
    }
}
