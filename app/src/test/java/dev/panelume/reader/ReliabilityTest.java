package dev.panelume.reader;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import android.content.Context;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ReliabilityTest {
    private static final class FixtureHttp extends CatalogHttp {
        final Map<String, String> responses = new LinkedHashMap<>();
        FixtureHttp add(String path, String response) { responses.put(path, response); return this; }
        @Override String text(String address, String accept, Predicate<String> allowed) throws IOException {
            CatalogHttp.check(address, allowed);
            String body = responses.get(address);
            if (body == null) throw new IOException("Unexpected fixture URL: " + address);
            return body;
        }
    }
    private static CatalogSource.Title title(CatalogSource source, String id) {
        return new CatalogSource.Title(source.id(), id, "Fixture", "https://example.invalid", "", "");
    }
    private static void rejects(Throwing op) throws Exception {
        try { op.run(); fail("Expected IOException"); } catch (IOException expected) {}
    }
    interface Throwing { void run() throws Exception; }

    @Test public void webtoonLockedAndOrdered() throws Exception {
        WebtoonSource source = new WebtoonSource(new FixtureHttp());
        List<CatalogSource.Episode> episodes = source.parseEpisodes(
            "{\"result\":{\"episodeList\":[{\"episodeNo\":2,\"locked\":true,\"viewerLink\":\"https://www.webtoons.com/en/a/b/viewer\"},{\"episodeNo\":1,\"viewerLink\":\"https://www.webtoons.com/en/a/b/viewer\"}]}}", "https://www.webtoons.com/en/a/b/list");
        assertEquals("1", episodes.get(0).id);
        assertFalse(episodes.get(0).external);
        assertTrue(episodes.get(1).external);
        rejects(() -> source.pages(episodes.get(1)));
        assertEquals(Collections.singletonList("https://webtoon-phinf.pstatic.net/a.jpg"), source.parsePages(
            "<div class='viewer_img _img_viewer_area'><img data-url='https://webtoon-phinf.pstatic.net/a.jpg'></div>"));
    }

    @Test public void weebCentralDeduplicatesAndRejectsUnknownImages() throws Exception {
        WeebCentralSource source = new WeebCentralSource(new FixtureHttp());
        String id = "01J76XYE8HKQ5KFPY5MRR9CNCR";
        String item = "<a href='/chapters/" + id + "'><span class='flex'><span>Chapter 2.5</span></span></a>";
        List<CatalogSource.Episode> episodes = source.chapterList(item + item);
        assertEquals(1, episodes.size()); assertEquals("2.5", episodes.get(0).number);
        assertEquals(1, source.pageLinks("<section x-data='scroll'><img src='https://temp.compsci88.com/a.jpg'></section>").size());
        rejects(() -> source.pageLinks("<section x-data='scroll'><img src='https://example.invalid/a.jpg'></section>"));
    }

    @Test public void mangaReadOrdersDecimalChapters() throws Exception {
        MangaReadSource source = new MangaReadSource(new FixtureHttp());
        String html = "<div class='listing-chapters_wrap'><li class='wp-manga-chapter'><a href='/manga/demo/chapter-10/'>Chapter 10</a></li>"
            + "<li class='wp-manga-chapter'><a href='/manga/demo/chapter-2-5/'>Chapter 2.5</a></li></div>";
        assertEquals("2.5", source.chapterList(html, "demo").get(0).number);
        assertEquals(1, source.pageLinks("<div class='reading-content'><img class='wp-manga-chapter-img' src='/page.jpg'></div>").size());
    }

    @Test public void mangaDexEnglishAndOriginalQuality() throws Exception {
        MangaDexSource source = new MangaDexSource(new FixtureHttp());
        String id = "11111111-1111-1111-1111-111111111111";
        String body = "{\"data\":[{\"id\":\"" + id + "\",\"attributes\":{\"originalLanguage\":\"ja\",\"title\":{\"en\":\"Fixture\"},\"availableTranslatedLanguages\":[\"en\"]}}]}";
        assertEquals(1, source.parseTitles(body).size());
        String chapter = "{\"baseUrl\":\"https://test.mangadex.network\",\"chapter\":{\"hash\":\"abcdef123\",\"data\":[\"a.jpg\"],\"dataSaver\":[\"b.jpg\"]}}";
        assertTrue(source.parsePages(chapter).get(0).contains("/data/"));
        rejects(() -> source.parsePages(chapter.replace("test.mangadex.network","example.invalid")));
    }

    @Test public void atsumaruDeduplicatesAndOrders() throws Exception {
        AtsumaruSource source = new AtsumaruSource(new FixtureHttp());
        List<CatalogSource.Episode> episodes = source.chapterList("{\"chapters\":[{\"id\":\"aa\",\"number\":10},{\"id\":\"bb\",\"number\":2.5},{\"id\":\"bb\",\"number\":2.5}]}", "demo");
        assertEquals(2, episodes.size()); assertEquals("2.5", episodes.get(0).number);
        List<String> pages = source.pageLinks("{\"readChapter\":{\"pages\":[{\"number\":2,\"image\":\"two.jpg\"},{\"number\":1,\"image\":\"one.jpg\"}]}}");
        assertTrue(pages.get(0).endsWith("one.jpg"));
    }

    @Test public void atsumaruGroupsNormalizedNumbersAndKeepsExistingIdentity() throws Exception {
        AtsumaruSource source = new AtsumaruSource(new FixtureHttp());
        String body = "{\"chapters\":[{\"id\":\"zz\",\"number\":\"1.0\"},{\"id\":\"aa\",\"number\":1},"
            + "{\"id\":\"cc\",\"number\":\"2.5\"},{\"id\":\"bb\",\"number\":\"2.50\"},{\"id\":\"dd\",\"number\":10}]}";
        List<CatalogSource.Episode> canonical=source.chapterList(body,"demo");
        assertEquals(3,canonical.size());
        assertEquals("demo/aa",canonical.get(0).id);
        assertEquals("2.5",canonical.get(1).number);
        assertEquals("10",canonical.get(2).number);
        List<CatalogSource.Episode> preferred=source.chapterList(body,"demo",
            Arrays.asList("demo/zz","demo/cc"));
        assertEquals(3,preferred.size());
        assertEquals("demo/zz",preferred.get(0).id);
        assertEquals("demo/cc",NextChapter.after(preferred,"demo/zz").episode.id);
        assertEquals("demo/dd",NextChapter.after(preferred,"demo/cc").episode.id);
    }

    @Test public void archiveRejectsRestrictedMetadata() throws Exception {
        FixtureHttp http = new FixtureHttp().add("https://archive.org/metadata/demo",
            "{\"metadata\":{\"rights\":\"public domain\",\"imagecount\":2}}");
        ArchiveComicSource source = new ArchiveComicSource(http);
        CatalogSource.Episode episode = source.episodes(title(source,"demo")).get(0);
        assertEquals(2, source.pages(episode).size());
        http.add("https://archive.org/metadata/demo","{\"metadata\":{\"rights\":\"public domain\",\"access-restricted-item\":true,\"imagecount\":2}}");
        rejects(() -> source.pages(episode));
    }

    @Test public void pepperCarrotSortsAndPrefersHiRes() throws Exception {
        String base = "https://www.peppercarrot.com";
        String file = "en_Pepper-and-Carrot_by-David-Revoy_E01P01.jpg";
        FixtureHttp http = new FixtureHttp().add(base+"/en/webcomics/peppercarrot.html","<a href='/en/webcomic/ep01_Demo.html'>Demo</a>")
            .add(base+"/en/webcomic/ep01_Demo.html","<img src='/low/"+file+"'><a href='/hi-res/"+file+"'>Full</a>");
        PepperCarrotSource source = new PepperCarrotSource(http);
        assertTrue(source.pages(source.episodes(title(source,"peppercarrot")).get(0)).get(0).contains("/hi-res/"));
    }

    @Test public void xkcdSkipsMissingNumberAndChecksImageHost() throws Exception {
        FixtureHttp http = new FixtureHttp().add("https://xkcd.com/info.0.json","{\"num\":405}")
            .add("https://xkcd.com/1/info.0.json","{\"num\":1,\"img\":\"https://imgs.xkcd.com/comics/demo.png\"}");
        XkcdSource source = new XkcdSource(http);
        List<CatalogSource.Episode> episodes = source.episodes(title(source,"xkcd"));
        assertEquals(404, episodes.size());
        assertEquals("405", episodes.get(403).id);
        assertEquals(1, source.pages(episodes.get(0)).size());
        http.add("https://xkcd.com/1/info.0.json","{\"num\":1,\"img\":\"https://example.invalid/a.png\"}");
        rejects(() -> source.pages(episodes.get(0)));
    }

    @Test public void interruptedBookmarkFallsBackToPreviousSlot() throws Exception {
        File file = File.createTempFile("bookmark", ".bin");
        try {
            Bookmark bookmark = new Bookmark(file);
            bookmark.save(1,.25,20,1.25);
            bookmark.save(2,.5,40,2.5);
            bookmark.flush();
            try (RandomAccessFile raw = new RandomAccessFile(file,"rw")) { raw.seek(0); raw.writeLong(0L); }
            Bookmark.Position recovered = Bookmark.read(file);
            assertEquals(1, recovered.page); assertEquals(.25, recovered.fraction, .000001);
        } finally { file.delete(); }
    }

    @Test public void queueRecoveryAndReadOnlyExport() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        try (TestShelf fixture = new TestShelf(context)) {
            ShelfDb shelf = fixture.shelf;
        CatalogQueue queue = new CatalogQueue(shelf);
        CatalogSource.Title series = new CatalogSource.Title("xkcd","fixture-series","Fixture","https://xkcd.com/","","");
        CatalogSource.Episode episode = new CatalogSource.Episode("fixture-1","1","Chapter 1","https://xkcd.com/1/","",false);
        long seriesId = queue.enqueue(series, Collections.singletonList(episode), false);
        assertEquals(1, queue.jobs(seriesId).size());
        CatalogQueue.Job active = queue.next(true);
        assertNotNull(active); assertTrue(queue.progress(active.id,2,5));
        queue.resetRunning();
        assertEquals(CatalogQueue.QUEUED, queue.jobs(seriesId).get(0).state);
        assertEquals(0, queue.jobs(seriesId).get(0).current);
        queue.enqueue(series, Collections.singletonList(episode), false);
        assertEquals(1, queue.jobs(seriesId).size());
        active = queue.next(true); assertNotNull(active);
        queue.fail(active.id, CatalogQueue.NETWORK); queue.retry(active.id);
        assertEquals(CatalogQueue.QUEUED, queue.jobs(seriesId).get(0).state);
        String directory = "11111111-1111-1111-1111-111111111111";
        long local = shelf.addChapter("Local fixture","1","","test.cbz",directory,"fixture-digest","cbz",10);
        ShelfDb.Chapter chapter = shelf.chapters(local).get(0);
        File folder = new File(shelf.root, directory); assertTrue(folder.mkdirs() || folder.isDirectory());
        Bookmark bookmark = new Bookmark(chapter.bookmark(shelf.root));
        bookmark.save(3,.4,34,3.4); bookmark.flush();
        shelf.addTime(chapter.id, Collections.singletonMap(LocalDate.of(2026,11,25),1234L));
        shelf.opened(chapter.id);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ProgressExport.write(context,shelf,output);
        JSONObject json = new JSONObject(output.toString("UTF-8"));
        assertTrue(json.getBoolean("complete")); assertFalse(json.getBoolean("chapter_files_included"));
        assertEquals(3,json.getJSONArray("chapters").getJSONObject(0).getJSONObject("bookmark").getInt("page"));
        assertEquals(1234,json.getJSONArray("reading_day").getJSONObject(0).getLong("ms"));
        assertEquals(1,shelf.chapters(local).size()); assertEquals(1,queue.jobs(seriesId).size());
        queue.cancel(active.id);
        assertTrue(queue.jobs(seriesId).isEmpty());
        }
    }
}
