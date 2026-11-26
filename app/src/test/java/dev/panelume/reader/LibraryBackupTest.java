package dev.panelume.reader;
import android.content.Context;
import java.io.*;
import java.time.LocalDate;
import java.util.Collections;
import java.util.zip.*;
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
public final class LibraryBackupTest {
    private static final byte[] ARCHIVE = {1,2,3,4,5};
    private static final byte[] COVER = {6,7,8};
    private static final LocalDate DAY=LocalDate.of(2026,11,26);
    private static void put(File f,byte[] content) throws IOException {
        try (FileOutputStream out=new FileOutputStream(f)) { out.write(content); }
    }
    private byte[] fixture(Context context,ShelfDb shelf) throws Exception {
        String dir="11111111-1111-1111-1111-111111111111";
        long series=shelf.addChapter("Backup fixture","1","Alias","fixture.cbz",dir,LibraryBackup.hash(ARCHIVE),"cbz",10);
        ShelfDb.Chapter chapter=shelf.chapters(series).get(0);
        File folder=new File(shelf.root,dir);
        assertTrue(folder.mkdirs());
        put(chapter.file(shelf.root),ARCHIVE); put(shelf.cover(dir),COVER);
        Bookmark b=new Bookmark(chapter.bookmark(shelf.root));
        b.save(3,.25,32,3.25); b.flush();
        shelf.opened(chapter.id); shelf.finish(chapter.id);
        shelf.addTime(chapter.id,Collections.singletonMap(DAY,1200L));
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        LibraryBackup.write(context,shelf,out);
        return out.toByteArray();
    }
    @Test public void roundTripToEmptyLibraryAndRefuseRepeat() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        try (TestShelf source=new TestShelf(context);TestShelf dest=new TestShelf(context)) {
            byte[] zip=fixture(context,source.shelf);
            LibraryBackup.Preview preview=LibraryBackup.preview(new ByteArrayInputStream(zip));
            assertEquals(1,preview.series); assertEquals(1,preview.chapters);
            assertTrue(LibraryBackup.empty(dest.shelf));
            LibraryBackup.restore(dest.shelf,new ByteArrayInputStream(zip),preview);
            ShelfDb.Chapter chapter=dest.shelf.lastOpened();
            assertNotNull(chapter); assertTrue(chapter.done); assertEquals(1200,chapter.ms);
            assertArrayEquals(ARCHIVE,java.nio.file.Files.readAllBytes(chapter.file(dest.shelf.root).toPath()));
            assertArrayEquals(COVER,java.nio.file.Files.readAllBytes(dest.shelf.cover(chapter.directory).toPath()));
            Bookmark.Position position=Bookmark.read(chapter.bookmark(dest.shelf.root));
            assertEquals(3,position.page); assertEquals(.25,position.fraction,.00001);
            assertEquals("Backup fixture",dest.shelf.suggestedTitle("Alias"));
            try { LibraryBackup.restore(dest.shelf,new ByteArrayInputStream(zip),preview); fail("Repeated restore accepted"); }
            catch (IOException expected) {}
            assertEquals(1200,dest.shelf.lastOpened().ms);
            assertEquals(1,dest.shelf.series().size());
        }
    }

    @Test public void corruptArchiveRollsBackBeforeDatabaseWrites() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        try (TestShelf source=new TestShelf(context);TestShelf dest=new TestShelf(context)) {
            byte[] zip=fixture(context,source.shelf);
            LibraryBackup.Preview preview=LibraryBackup.preview(new ByteArrayInputStream(zip));
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try (ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(zip));ZipOutputStream out=new ZipOutputStream(bytes)) {
                ZipEntry entry;
                while ((entry=in.getNextEntry())!=null) {
                    out.putNextEntry(new ZipEntry(entry.getName()));
                    byte[] content=in.readAllBytes();
                    if (entry.getName().endsWith("chapter.cbz")) content[0]^=1;
                    out.write(content); out.closeEntry();
                }
            }
            try { LibraryBackup.restore(dest.shelf,new ByteArrayInputStream(bytes.toByteArray()),preview); fail("Corrupt archive accepted"); }
            catch (IOException expected) {}
            assertTrue(LibraryBackup.empty(dest.shelf));
            assertEquals(0,dest.shelf.root.list().length);
        }
    }
    @Test public void unexpectedEntryRejectedWithoutExtraction() throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try (ZipOutputStream out=new ZipOutputStream(bytes)) {
            out.putNextEntry(new ZipEntry("../outside")); out.write(1); out.closeEntry();
        }
        try { LibraryBackup.preview(new ByteArrayInputStream(bytes.toByteArray())); fail("Traversal accepted"); }
        catch (IOException expected) {}
    }
}
