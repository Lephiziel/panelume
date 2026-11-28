package dev.panelume.reader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfDocument;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.time.LocalDate;
import java.util.Collections;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

/** Real Android framework checks, without network or personal library data. */
@RunWith(AndroidJUnit4.class)
public final class RuntimeRegressionTest {
    private static Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private static final class Fixture implements AutoCloseable {
        final Context context=context();
        final String name="runtime-"+UUID.randomUUID()+".db";
        final File root=new File(context.getFilesDir(),name+"-chapters");
        final ShelfDb shelf=new ShelfDb(context,name,root);
        @Override public void close() {
            shelf.close();
            context.deleteDatabase(name);
            // The test root contains registered chapter subdirectories. Never touch
            // the app's production chapter root.
            File[] directories=root.listFiles();
            if (directories!=null) for (File directory:directories) {
                if (directory.isDirectory()) ImportFiles.removeDirectory(directory);
                else directory.delete();
            }
            ImportFiles.removeDirectory(root);
        }
    }

    private static File cbz() throws IOException { return cbz(3); }

    private static File cbz(int pages) throws IOException {
        File file=File.createTempFile("runtime-", ".cbz",context().getCacheDir());
        try (ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(file))) {
            for (int i=0;i<pages;i++) {
                Bitmap bitmap=Bitmap.createBitmap(240,800+i*100,Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(i==0?Color.RED:i==1?Color.GREEN:Color.BLUE);
                try {
                    zip.putNextEntry(new ZipEntry(String.format(java.util.Locale.ROOT,"%02d.png",i+1)));
                    if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,zip)) throw new IOException("fixture image");
                    zip.closeEntry();
                } finally {bitmap.recycle();}
            }
        }
        return file;
    }

    private static File pdf() throws IOException {
        File file=File.createTempFile("runtime-", ".pdf",context().getCacheDir());
        PdfDocument document=new PdfDocument();
        try (FileOutputStream out=new FileOutputStream(file)) {
            for (int i=0;i<2;i++) {
                PdfDocument.Page page=document.startPage(new PdfDocument.PageInfo.Builder(300,600,i+1).create());
                page.getCanvas().drawColor(i==0?Color.RED:Color.BLUE);
                document.finishPage(page);
            }
            document.writeTo(out);
        } finally { document.close(); }
        return file;
    }

    private static ShelfDb.Chapter addCbz(Fixture fixture) throws Exception {
        File file=cbz();
        try {
            ImportFiles.Staged staged=ImportFiles.inspectArchive(file,"Runtime fixture.cbz");
            assertEquals(3,staged.pages);
            long series=ImportFiles.store(fixture.shelf,staged,"Runtime fixture","1");
            return fixture.shelf.chapters(series).get(0);
        } finally {file.delete();}
    }

    private static void assertTile(MangaDocument document,int page,int expectedColor) throws IOException {
        Bitmap tile=document.tile(page,120,0,100);
        try {
            assertEquals(120,tile.getWidth());
            assertEquals(100,tile.getHeight());
            int actual=tile.getPixel(60,50);
            assertTrue(Math.abs(Color.red(actual)-Color.red(expectedColor))<12);
            assertTrue(Math.abs(Color.green(actual)-Color.green(expectedColor))<12);
            assertTrue(Math.abs(Color.blue(actual)-Color.blue(expectedColor))<12);
        } finally {tile.recycle();}
    }

    private static void assertPosition(ShelfDb shelf,ShelfDb.Chapter chapter) {
        Bookmark.Position position=Bookmark.read(chapter.bookmark(shelf.root));
        assertEquals(1,position.page);
        assertEquals(.25,position.fraction,.00001);
        assertEquals(42,position.percent);
        assertEquals(1.25,position.frontier,.00001);
        assertTrue(chapter.done);
        assertEquals(1700L,chapter.ms);
    }

    private static final CatalogSource.Title CATALOG = new CatalogSource.Title(
        "xkcd","runtime-fixture","Runtime catalogue","https://xkcd.com/","","");
    private static final CatalogSource.Episode EPISODE = new CatalogSource.Episode(
        "1","1","Chapter 1","https://xkcd.com/1/","",false);

    private static long saveCatalogue(ShelfDb shelf, int pages) throws Exception {
        File file=cbz(pages);
        try {
            ImportFiles.Staged staged=ImportFiles.inspectArchive(file,"Runtime catalogue.cbz");
            assertEquals(pages,staged.pages);
            CatalogQueue queue=new CatalogQueue(shelf);
            queue.enqueue(CATALOG,Collections.singletonList(EPISODE),false);
            CatalogQueue.Job job=queue.next(false);
            assertNotNull(job);
            return ImportFiles.store(shelf,staged,dir -> queue.save(job,staged,dir));
        } finally {file.delete();}
    }

    @Test public void evictedCatalogueSurvivesPortableRestoreAndRedownload() throws Exception {
        try(Fixture source=new Fixture(); Fixture target=new Fixture()) {
            long id=saveCatalogue(source.shelf,3);
            ShelfDb.Chapter chapter=source.shelf.chapter(id);
            Bookmark position=new Bookmark(chapter.bookmark(source.root));
            position.save(1,.25,42,1.25);
            position.flush();
            source.shelf.opened(id);
            source.shelf.finish(id);
            source.shelf.addTime(id,Collections.singletonMap(LocalDate.of(2026,11,28),1700L));
            ShelfDb.Chapter local=addCbz(source);
            long archiveBytes=chapter.file(source.root).length();
            OfflineStorage.Report before=OfflineStorage.scan(source.shelf);
            assertEquals(2,before.ready);
            assertEquals(archiveBytes,before.reclaimable);
            OfflineStorage.Candidate selected=null;
            for (OfflineStorage.Entry entry:before.series)
                for (OfflineStorage.Candidate candidate:entry.candidates)
                    if(candidate.id==id) selected=candidate;
            assertNotNull(selected);
            assertNotNull(source.shelf.acquireReader(id));
            try {
                assertEquals(1,OfflineStorage.evict(source.shelf,Collections.singletonList(selected)).failed);
                assertTrue(chapter.available(source.root));
            } finally {source.shelf.releaseReader(id);}
            OfflineStorage.Result removed=OfflineStorage.evict(source.shelf,Collections.singletonList(selected));
            assertEquals(1,removed.removed);
            assertEquals(archiveBytes,removed.bytes);
            assertFalse(chapter.available(source.root));
            assertPosition(source.shelf,source.shelf.chapter(id));
            assertTrue(local.available(source.root));
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            LibraryBackup.write(context(),source.shelf,output);
            byte[] zip=output.toByteArray();
            LibraryBackup.Preview preview=LibraryBackup.preview(new ByteArrayInputStream(zip));
            assertEquals(2,preview.chapters);
            assertEquals(1,preview.missing);
            LibraryBackup.restore(target.shelf,new ByteArrayInputStream(zip),preview);
            ShelfDb.Chapter restored=target.shelf.chapter(id);
            assertNotNull(restored);
            assertFalse(restored.available(target.root));
            assertTrue(target.shelf.cover(restored.directory).isFile());
            assertPosition(target.shelf,restored);
            assertTrue(target.shelf.chapter(local.id).available(target.root));
            try {
                saveCatalogue(target.shelf,2);
                fail("Changed page count accepted");
            } catch(Fault expected) {assertEquals(R.string.offline_chapter_changed,expected.message);}
            assertPosition(target.shelf,target.shelf.chapter(id));
            assertFalse(target.shelf.chapter(id).available(target.root));
            CatalogQueue queue=new CatalogQueue(target.shelf);
            CatalogQueue.Job interrupted=queue.jobs(restored.seriesId).get(0);
            queue.cancel(interrupted.id);
            assertEquals(id,saveCatalogue(target.shelf,3));
            ShelfDb.Chapter downloaded=target.shelf.chapter(id);
            assertPosition(target.shelf,downloaded);
            assertTrue(downloaded.available(target.root));
            assertEquals(1,target.shelf.chapters(restored.seriesId).size());
            assertTrue(queue.jobs(restored.seriesId).isEmpty());
            try(MangaDocument document=MangaDocument.openRegistered(downloaded.file(target.root),
                    downloaded.format,downloaded.digest,downloaded.pages)) {
                assertTile(document,1,Color.GREEN);
            }
            assertTrue(target.shelf.chapter(local.id).available(target.root));
        }
    }

    @Test public void actualCbzRenderAndPersistentIndex() throws Exception {
        try(Fixture fixture=new Fixture()) {
            ShelfDb.Chapter chapter=addCbz(fixture);
            File file=chapter.file(fixture.root);
            try(MangaDocument document=MangaDocument.openRegistered(file,"cbz",chapter.digest,chapter.pages)) {
                assertEquals(3,document.pages.length);
                assertEquals(900,document.pages[1].height);
                assertTile(document,0,Color.RED);
                assertTile(document,1,Color.GREEN);
            }
            assertTrue(PageIndex.sidecar(file).isFile());
            try(RandomAccessFile index=new RandomAccessFile(PageIndex.sidecar(file),"rw")) {index.setLength(9);}
            try(MangaDocument document=MangaDocument.openRegistered(file,"cbz",chapter.digest,chapter.pages)) {
                assertEquals(3,document.pages.length);
                assertTile(document,2,Color.BLUE);
            }
            assertTrue(PageIndex.sidecar(file).length()>9);
        }
    }

    @Test public void actualPdfRenderAndIndex() throws Exception {
        File file=pdf();
        File folder=new File(context().getCacheDir(),"runtime-pdf-"+UUID.randomUUID());
        assertTrue(folder.mkdir());
        File privatePdf=new File(folder,"chapter.pdf");
        try {
            assertTrue(file.renameTo(privatePdf));
            String digest=LibraryBackup.hash(java.nio.file.Files.readAllBytes(privatePdf.toPath()));
            for(int i=0;i<2;i++) try(MangaDocument document=MangaDocument.openRegistered(privatePdf,"pdf",digest,2)) {
                assertEquals(2,document.pages.length);
                assertEquals(300,document.pages[0].width);
                assertTile(document,0,Color.RED);
                assertTile(document,1,Color.BLUE);
            }
            assertTrue(PageIndex.sidecar(privatePdf).isFile());
        } finally {file.delete();ImportFiles.removeDirectory(folder);}
    }

    @Test public void portableRestoreRealArchiveAndProgress() throws Exception {
        try(Fixture source=new Fixture();Fixture target=new Fixture();Fixture damaged=new Fixture()) {
            ShelfDb.Chapter chapter=addCbz(source);
            Bookmark position=new Bookmark(chapter.bookmark(source.root));
            position.save(1,.25,42,1.25);
            position.flush();
            source.shelf.opened(chapter.id);
            source.shelf.finish(chapter.id);
            source.shelf.addTime(chapter.id,Collections.singletonMap(LocalDate.of(2026,11,28),1700L));
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            LibraryBackup.write(context(),source.shelf,output);
            byte[] zip=output.toByteArray();
            LibraryBackup.Preview preview=LibraryBackup.preview(new ByteArrayInputStream(zip));
            assertEquals(1,preview.chapters);
            assertEquals(0,preview.missing);
            LibraryBackup.restore(target.shelf,new ByteArrayInputStream(zip),preview);
            ShelfDb.Chapter restored=target.shelf.chapter(chapter.id);
            assertNotNull(restored);
            assertEquals(chapter.digest,restored.digest);
            assertPosition(target.shelf,restored);
            try(MangaDocument document=MangaDocument.openRegistered(restored.file(target.root),"cbz",restored.digest,restored.pages)) {
                assertTile(document,1,Color.GREEN);
            }
            try {
                LibraryBackup.restore(target.shelf,new ByteArrayInputStream(zip),preview);
                fail("Duplicate restore accepted");
            } catch(IOException expected) {}
            assertPosition(target.shelf,target.shelf.chapter(chapter.id));
            try {
                LibraryBackup.restore(damaged.shelf,new ByteArrayInputStream(java.util.Arrays.copyOf(zip,zip.length/2)),preview);
                fail("Truncated backup accepted");
            } catch(IOException expected) {}
            assertTrue(LibraryBackup.empty(damaged.shelf));
        }
    }
}
