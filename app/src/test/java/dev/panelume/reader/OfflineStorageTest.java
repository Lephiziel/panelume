package dev.panelume.reader;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
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
@Config(sdk = 28)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class OfflineStorageTest {
    @Test public void reportCountsOnlyRegisteredArchivesAndNeverDeletes() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        try (TestShelf fixture = new TestShelf(context)) {
            ShelfDb shelf = fixture.shelf;
            assertEquals(0,OfflineStorage.scan(shelf).series.size());
            String a = "11111111-1111-1111-1111-111111111111";
            String b = "22222222-2222-2222-2222-222222222222";
            String c = "33333333-3333-3333-3333-333333333333";
            long first = shelf.addChapter("First","1","","a.cbz",a,"a","cbz",5);
            shelf.addChapter("First","2","","b.cbz",b,"b","cbz",5);
            long second = shelf.addChapter("Second","1","","c.pdf",c,"c","pdf",5);
            File folder = new File(shelf.root,a); assertTrue(folder.mkdirs());
            File archive = new File(folder,"chapter.cbz");
            try (FileOutputStream out = new FileOutputStream(archive)) { out.write(new byte[100]); }
            try (FileOutputStream out = new FileOutputStream(new File(folder,"cover.jpg"))) { out.write(new byte[80]); }
            File empty = new File(shelf.root,c); assertTrue(empty.mkdirs());
            assertTrue(new File(empty,"chapter.pdf").createNewFile());
            ShelfDb.Chapter chapter = shelf.chapters(first).get(0);
            shelf.finish(chapter.id);
            shelf.addTime(chapter.id,Collections.singletonMap(LocalDate.of(2026,11,25),1000L));
            OfflineStorage.Report report = OfflineStorage.scan(shelf);
            assertEquals(1,report.ready);
            assertEquals(2,report.missing);
            assertEquals(100,report.bytes);
            assertEquals("First",report.series.get(0).title);
            assertEquals(2,report.series.size());
            assertEquals(1,report.series.get(0).missing);
            assertTrue(archive.isFile());
            assertTrue(shelf.chapter(chapter.id).done);
            assertEquals(1000,shelf.chapter(chapter.id).ms);
            assertEquals(2,shelf.chapters(first).size());
            assertEquals(1,shelf.chapters(second).size());
        }
    }
}
