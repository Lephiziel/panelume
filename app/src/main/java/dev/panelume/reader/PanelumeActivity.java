package dev.panelume.reader;

import android.app.Activity;
import android.content.Context;

abstract class PanelumeActivity extends Activity {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Prefs.localized(base));
    }

    final boolean languageChanged() {
        return !Prefs.locale(this).getLanguage().equals(Prefs.language(this));
    }
}
