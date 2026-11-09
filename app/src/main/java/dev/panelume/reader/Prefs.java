package dev.panelume.reader;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.LocaleList;

import java.util.Locale;

final class Prefs {
    private static final String FILE = "panelume_preferences";

    private Prefs() {}

    private static SharedPreferences values(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static String language(Context context) {
        return values(context).getString("language", "en").equals("ru") ? "ru" : "en";
    }

    static void language(Context context, String tag) {
        values(context).edit().putString("language", tag).apply();
    }

    static Context localized(Context context) {
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.setLocales(new LocaleList(Locale.forLanguageTag(language(context))));
        return context.createConfigurationContext(configuration);
    }

    static Locale locale(Context context) {
        return context.getResources().getConfiguration().getLocales().get(0);
    }

    static boolean ready(Context context) {
        return values(context).getBoolean("setup_finished", false);
    }

    static void finishSetup(Context context) {
        values(context).edit().putBoolean("setup_finished", true).apply();
    }

    static boolean unmetered(Context context) {
        return values(context).getBoolean("unmetered", true);
    }

    static void unmetered(Context context, boolean enabled) {
        values(context).edit().putBoolean("unmetered", enabled).apply();
    }
}
