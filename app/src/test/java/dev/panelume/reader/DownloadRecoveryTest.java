package dev.panelume.reader;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
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
public final class DownloadRecoveryTest {
    private static final CatalogSource.Title TITLE = new CatalogSource.Title(
        "xkcd", "recovery-fixture", "Recovery fixture", "https://xkcd.com/", "", "");
    private static CatalogSource.Episode episode(String id) {
        return new CatalogSource.Episode(id,id,"Chapter "+id,"https://xkcd.com/"+id+"/","",false);
    }

    private static ImportFiles.Staged staged(File root) throws Exception {
        File file = File.createTempFile("staged-", ".cbz", root.getParentFile());
        byte[] bytes = {1,2,3,4};
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); }
        return new ImportFiles.Staged(file,"fixture.cbz","cbz",LibraryBackup.hash(bytes),
            3,"Fixture","1","",new byte[]{5,6});
    }

    @Test public void reopeningDatabaseResetsOnlyInterruptedJobsAndKeepsSavedProgress() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        String name = "recovery-" + UUID.randomUUID() + ".db";
        File parent = Files.createTempDirectory(context.getCacheDir().toPath(),"recovery-").toFile();
        File root = new File(parent,"chapters");
        long series, saved, failed, interrupted;
        try {
            try (ShelfDb shelf = new ShelfDb(context,name,root)) {
                CatalogQueue q = new CatalogQueue(shelf);
                series = q.enqueue(TITLE,Collections.singletonList(episode("1")),false);
                CatalogQueue.Job first = q.next(false);
                assertNotNull(first);
                ImportFiles.Staged archive = staged(root);
                saved = ImportFiles.store(shelf,archive,dir -> q.save(first,archive,dir));
                ShelfDb.Chapter chapter = shelf.chapter(saved);
                Bookmark bookmark = new Bookmark(chapter.bookmark(root));
                bookmark.save(1,.25,42,1.25);
                bookmark.flush();
                shelf.finish(saved);
                shelf.addTime(saved,Collections.singletonMap(LocalDate.of(2026,11,28),1700L));
                q.enqueue(TITLE,Collections.singletonList(episode("2")),false);
                CatalogQueue.Job failure = q.next(false);
                assertNotNull(failure);
                failed = failure.id;
                q.fail(failed,CatalogQueue.ACCESS);
                q.enqueue(TITLE,Arrays.asList(episode("3"),episode("4")),true);
                CatalogQueue.Job running = q.next(true);
                assertNotNull(running);
                interrupted = running.id;
                assertTrue(q.progress(interrupted,2,3));
            }
            try (ShelfDb shelf = new ShelfDb(context,name,root)) {
                CatalogQueue q = new CatalogQueue(shelf);
                assertEquals(1,q.runningMode(interrupted));
                q.resetRunning();
                q.resetRunning(); // recovery must be idempotent
                assertNull(q.next(false)); // bulk remains blocked; failed is not retried
                assertEquals(3,q.jobs(series).size());
                for (CatalogQueue.Job job:q.jobs(series)) {
                    if (job.id == failed) {
                        assertEquals(CatalogQueue.FAILED,job.state);
                        assertEquals(CatalogQueue.ACCESS,job.error);
                    } else {
                        assertEquals(CatalogQueue.QUEUED,job.state);
                        assertEquals(0,job.current);
                        assertEquals(0,job.total);
                        assertTrue(job.bulk);
                    }
                }
                q.enqueue(TITLE,Collections.singletonList(episode("3")),false);
                assertEquals(3,q.jobs(series).size());
                CatalogQueue.Job resumed = q.next(false);
                assertNotNull(resumed);
                assertEquals(interrupted,resumed.id);
                assertFalse(resumed.bulk);
                assertEquals(0,resumed.current);
                ShelfDb.Chapter chapter = shelf.chapter(saved);
                assertNotNull(chapter);
                assertTrue(chapter.available(root));
                assertTrue(chapter.done);
                assertEquals(1700L,chapter.ms);
                Bookmark.Position position = Bookmark.read(chapter.bookmark(root));
                assertEquals(1,position.page);
                assertEquals(.25,position.fraction,.00001);
                assertEquals(1.25,position.frontier,.00001);
                assertEquals(1,shelf.chapters(series).size());
            }
        } finally {
            context.deleteDatabase(name);
            try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(parent.toPath())) {
                for (java.nio.file.Path path:paths.sorted(java.util.Comparator.reverseOrder()).toArray(java.nio.file.Path[]::new))
                    Files.deleteIfExists(path);
            }
        }
    }

    @Test public void cancellationAtRegistrationBoundaryLeavesNoPhantomChapter() throws Exception {
        try (TestShelf fixture = new TestShelf(RuntimeEnvironment.getApplication())) {
            ShelfDb shelf = fixture.shelf;
            CatalogQueue q = new CatalogQueue(shelf);
            long series = q.enqueue(TITLE,Collections.singletonList(episode("1")),false);
            CatalogQueue.Job job = q.next(false);
            assertNotNull(job);
            ImportFiles.Staged archive = staged(shelf.root);
            try {
                ImportFiles.store(shelf,archive,dir -> {
                    q.cancel(job.id);
                    return q.save(job,archive,dir);
                });
                fail("Cancelled download registered");
            } catch (Fault expected) {
                assertEquals(R.string.operation_cancelled,expected.message);
            }
            assertTrue(q.jobs(series).isEmpty());
            assertTrue(shelf.chapters(series).isEmpty());
            assertTrue(archive.file.isFile());
            assertArrayEquals(new byte[]{1,2,3,4},Files.readAllBytes(archive.file.toPath()));
            assertNotNull(shelf.root.list());
            assertEquals(0,shelf.root.list().length);
            q.enqueue(TITLE,Collections.singletonList(episode("1")),false);
            CatalogQueue.Job retry = q.next(false);
            assertNotNull(retry);
            assertNotEquals(job.id,retry.id);
            long saved = ImportFiles.store(shelf,archive,dir -> q.save(retry,archive,dir));
            assertTrue(shelf.chapter(saved).available(shelf.root));
            assertEquals(1,shelf.chapters(series).size());
            assertTrue(q.jobs(series).isEmpty());
        }
    }
}
