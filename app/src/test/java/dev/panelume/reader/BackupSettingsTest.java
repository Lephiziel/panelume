package dev.panelume.reader;

import android.content.Context;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public final class BackupSettingsTest {
    @Test public void choicesAreExplicitAndIndependent() throws Exception {
        Context c=RuntimeEnvironment.getApplication();
        Prefs.language(c,"en"); Prefs.unmetered(c,true); Prefs.lastSearch(c,"Existing");
        BackupSettings b=BackupSettings.parse(new JSONObject("{\"language\":\"ru\",\"unmetered\":false,\"last_search\":\"New\",\"setup_finished\":true}"));
        assertEquals(7,b.available());
        assertTrue(Prefs.restore(c,b,0));
        assertEquals("en",Prefs.language(c)); assertTrue(Prefs.unmetered(c)); assertEquals("Existing",Prefs.lastSearch(c));
        assertTrue(Prefs.restore(c,b,BackupSettings.LANGUAGE));
        assertEquals("ru",Prefs.language(c)); assertTrue(Prefs.unmetered(c)); assertEquals("Existing",Prefs.lastSearch(c));
        assertTrue(Prefs.restore(c,b,BackupSettings.NETWORK|BackupSettings.SEARCH));
        assertFalse(Prefs.unmetered(c)); assertEquals("New",Prefs.lastSearch(c)); assertFalse(Prefs.ready(c));
    }
    @Test public void malformedAndAbsentFieldsAreIgnored() throws Exception {
        Context c=RuntimeEnvironment.getApplication();
        Prefs.language(c,"en"); Prefs.unmetered(c,true); Prefs.lastSearch(c,"Existing");
        BackupSettings b=BackupSettings.parse(new JSONObject("{\"language\":\"de\",\"unmetered\":\"false\",\"last_search\":42}"));
        assertEquals(0,b.available()); assertEquals(0,BackupSettings.parse(null).available());
        assertTrue(Prefs.restore(c,b,7)); assertEquals("en",Prefs.language(c)); assertTrue(Prefs.unmetered(c)); assertEquals("Existing",Prefs.lastSearch(c));
        assertEquals(0,BackupSettings.parse(new JSONObject().put("last_search","a\nspoof")).available());
        assertEquals(0,BackupSettings.parse(new JSONObject().put("last_search",new String(new char[2049]).replace('\0','x'))).available());
        BackupSettings clear=BackupSettings.parse(new JSONObject().put("last_search",""));
        assertTrue(Prefs.restore(c,clear,BackupSettings.SEARCH)); assertEquals("",Prefs.lastSearch(c));
    }
}
