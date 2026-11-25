package dev.panelume.reader;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

final class OpenMetrics {
    static final int LOCAL = 0, CACHED = 1, NETWORK = 2;
    private static final String FILE = "panelume_open_metrics";
    private static final long MAX_MS = 60 * 60 * 1000L;
    private static final int[] LABELS = {R.string.open_metrics_local, R.string.open_metrics_cached, R.string.open_metrics_network};

    private OpenMetrics() {}
    static void record(Context context, int type, long started) {
        if (type < 0 || type >= LABELS.length || started <= 0) return;
        long elapsed = SystemClock.elapsedRealtime() - started;
        if (elapsed < 0 || elapsed > MAX_MS) return;
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putLong("last_" + type, elapsed).apply();
    }
    static String label(Context context, int type) {
        SharedPreferences prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        long elapsed = prefs.getLong("last_" + type, -1L);
        String name = context.getString(LABELS[type]);
        return elapsed < 0 ? context.getString(R.string.open_metrics_empty, name)
            : context.getString(R.string.open_metrics_value, name, elapsed / 1000.0);
    }
}
