package dev.panelume.reader;

import android.content.Context;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Collections;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ProgressRestoreTest {
    @Test public void previewMergeAndRepeatWithoutLoss() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        try (TestShelf fixture = new TestShelf(context)) {
            ShelfDb shelf = fixture.shelf;
        String directory = "22222222-2222-2222-2222-222222222222";
        long series = shelf.addChapter("Restore fixture","1","","test.cbz",directory,"restore-digest","cbz",10);
        ShelfDb.Chapter chapter = shelf.chapters(series).get(0);
        File folder = new File(shelf.root,directory);
        assertTrue(folder.mkdirs() || folder.isDirectory());
        try (FileOutputStream stream = new FileOutputStream(chapter.file(shelf.root))) { stream.write(1); }
        LocalDate day = LocalDate.of(2026,11,25);
        Bookmark bookmark = new Bookmark(chapter.bookmark(shelf.root));
        bookmark.save(3,.4,34,3.4); bookmark.flush();
        shelf.addTime(chapter.id, Collections.singletonMap(day, 1200L));
        shelf.finish(chapter.id);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ProgressExport.write(context,shelf,out);
        String json = out.toString("UTF-8");
        ProgressRestore.Backup backup = ProgressRestore.parse(json);
        // Simulate a freshly imported identical chapter with no local reading history.
        assertTrue(chapter.bookmark(shelf.root).delete());
        shelf.getWritableDatabase().execSQL("DELETE FROM reading_day WHERE chapter_id=?",new Object[]{chapter.id});
        shelf.getWritableDatabase().execSQL("UPDATE chapters SET done=0,opened=0 WHERE id=?",new Object[]{chapter.id});
        ProgressRestore.Summary preview = ProgressRestore.preview(shelf,backup);
        assertEquals(1,preview.matched);
        assertFalse(chapter.bookmark(shelf.root).exists());
        assertFalse(shelf.chapter(chapter.id).done);
        assertEquals(1,ProgressRestore.apply(shelf,backup).matched);
        assertTrue(shelf.chapter(chapter.id).done);
        assertEquals(3,Bookmark.read(chapter.bookmark(shelf.root)).page);
        assertEquals(1200,shelf.chapter(chapter.id).ms);
        // A later local bookmark and longer reading time must survive re-import.
        Bookmark later = new Bookmark(chapter.bookmark(shelf.root));
        later.save(5,.2,52,5.2); later.flush();
        shelf.addTime(chapter.id,Collections.singletonMap(day,800L));
        ProgressRestore.apply(shelf,backup);
        assertEquals(2000,shelf.chapter(chapter.id).ms);
        assertEquals(5,Bookmark.read(chapter.bookmark(shelf.root)).page);
        assertEquals(1,shelf.chapters(series).size());
        assertTrue(chapter.file(shelf.root).isFile());
        // Missing content must never produce phantom library entries.
        assertTrue(chapter.file(shelf.root).delete());
        assertEquals(1,ProgressRestore.preview(shelf,backup).skipped);
        }
    }

    @Test public void invalidOrUnsupportedBackupRejected() throws Exception {
        for (String text : new String[]{"{}", "{\"format\":\"panelume-progress\",\"version\":2,\"complete\":true}",
            "{\"format\":\"panelume-progress\",\"version\":1,\"complete\":false,\"chapter_files_included\":false}"}) {
            try { ProgressRestore.parse(text); fail("Invalid backup was accepted"); } catch (IOException expected) {}
        }
    }
}
