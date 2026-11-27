package dev.panelume.reader;
import android.content.Context;
import android.content.Intent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public final class ChapterNavigatorTest {
    @Test public void chapterListIntentReusesCatalogueAndPreservesTitle() {
        Context context=RuntimeEnvironment.getApplication();
        CatalogSource.Title title=new CatalogSource.Title("mangadex","series-42","A title",
            "https://mangadex.org/title/series-42","https://mangadex.org/cover.jpg","description");
        Intent intent=CatalogActivity.chapterListIntent(context,title);
        assertEquals(CatalogActivity.class.getName(),intent.getComponent().getClassName());
        assertEquals("mangadex",intent.getStringExtra("provider"));
        assertEquals("series-42",intent.getStringExtra("id"));
        assertEquals("A title",intent.getStringExtra("name"));
        assertEquals("https://mangadex.org/title/series-42",intent.getStringExtra("url"));
        assertTrue((intent.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TOP)!=0);
        assertTrue((intent.getFlags() & Intent.FLAG_ACTIVITY_SINGLE_TOP)!=0);
    }
}
