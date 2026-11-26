package dev.panelume.reader;

import android.database.Cursor;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only snapshot of registered local chapter archives, excluding ancillary files. */
final class OfflineStorage {
    private OfflineStorage() {}

    static Report scan(ShelfDb shelf) {
        Report report = new Report();
        Map<Long, Entry> groups = new LinkedHashMap<>();
        try (Cursor c = shelf.getReadableDatabase().rawQuery(
            "SELECT s.id,s.title,s.catalog_provider,c.directory,c.format FROM chapters c "
                + "JOIN series s ON s.id=c.series_id ORDER BY s.id,c.id", null)) {
            while (c.moveToNext()) {
                long id = c.getLong(0);
                Entry entry = groups.get(id);
                if (entry == null) {
                    entry = new Entry(c.getString(1), c.getString(2));
                    groups.put(id, entry);
                }
                long bytes = archiveBytes(shelf.root, c.getString(3), c.getString(4));
                if (bytes > 0) {
                    entry.ready++;
                    entry.bytes += bytes;
                    report.ready++;
                    report.bytes += bytes;
                } else {
                    entry.missing++;
                    report.missing++;
                }
            }
        }
        report.series.addAll(groups.values());
        report.series.sort(Comparator.comparingLong((Entry e) -> e.bytes).reversed()
            .thenComparing(e -> e.title, String.CASE_INSENSITIVE_ORDER));
        return report;
    }

    private static long archiveBytes(File root, String directory, String format) {
        if (directory == null || !directory.matches("[a-fA-F0-9-]{36}")
            || !("cbz".equals(format) || "pdf".equals(format))) return 0;
        File file = new File(new File(root, directory), "chapter." + format);
        try {
            // Reject unexpected paths rather than count another file through a symlink.
            if (!file.getCanonicalFile().equals(new File(root.getCanonicalFile(),
                directory + "/chapter." + format))) return 0;
        } catch (java.io.IOException e) {
            return 0;
        }
        return file.isFile() ? file.length() : 0;
    }

    static final class Report {
        int ready, missing;
        long bytes;
        final List<Entry> series = new ArrayList<>();
    }

    static final class Entry {
        final String title, provider;
        int ready, missing;
        long bytes;
        Entry(String title, String provider) { this.title = title; this.provider = provider; }
    }
}
