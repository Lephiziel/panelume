package dev.panelume.reader;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Conservative merge into chapters already present. Never creates or deletes chapter files. */
final class ProgressRestore {
    private static final int LIMIT = 16 * 1024 * 1024;
    private ProgressRestore() {}

    static Backup read(Context context, Uri uri) throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("Cannot open backup");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[32768];
            int n;
            while ((n = input.read(buffer)) != -1) {
                if (bytes.size() > LIMIT - n) throw new IOException("Backup too large");
                bytes.write(buffer, 0, n);
            }
            return parse(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    static Backup parse(String text) throws IOException {
        try {
            JSONObject root = new JSONObject(text);
            if (!"panelume-progress".equals(root.getString("format"))
                || root.getInt("version") != 1 || !root.getBoolean("complete")
                || root.getBoolean("chapter_files_included")) throw new IOException("Unsupported backup");
            Map<Long,String> keys = new HashMap<>();
            Set<String> uniqueKeys = new HashSet<>();
            JSONArray series = root.getJSONArray("series");
            for (int i = 0; i < series.length(); i++) {
                JSONObject row = series.getJSONObject(i);
                long id = row.getLong("id");
                String key = row.getString("key");
                if (id <= 0 || key.isEmpty() || key.length() > 4096 || !uniqueKeys.add(key)
                    || keys.put(id,key) != null) throw new IOException("Invalid series");
            }
            Backup backup = new Backup();
            Map<Long,Entry> ids = new HashMap<>();
            Set<String> identities = new HashSet<>();
            JSONArray chapters = root.getJSONArray("chapters");
            for (int i = 0; i < chapters.length(); i++) {
                JSONObject row = chapters.getJSONObject(i);
                String key = keys.get(row.getLong("series_id"));
                long id = row.getLong("id");
                Entry e = new Entry();
                e.key = key;
                e.digest = row.getString("digest");
                e.catalogId = row.getString("catalog_id");
                e.format = row.getString("format");
                e.pages = row.getInt("pages");
                e.opened = row.getLong("opened");
                int done = row.getInt("done");
                e.done = done == 1;
                JSONObject p = row.getJSONObject("bookmark");
                e.page = p.getInt("page");
                e.fraction = p.getDouble("fraction");
                e.percent = p.getInt("percent");
                e.frontier = p.getDouble("frontier");
                if (key == null || id <= 0 || e.digest.isEmpty() || e.digest.length() > 4096
                    || e.catalogId.length() > 4096 || !("cbz".equals(e.format) || "pdf".equals(e.format))
                    || e.pages < 1 || e.pages > 100000 || e.opened < 0
                    || (done != 0 && done != 1) || e.page < 0 || e.page >= e.pages
                    || !(e.fraction >= 0 && e.fraction <= 1) || e.percent < 0 || e.percent > 100
                    || !(e.frontier >= 0 && e.frontier <= e.pages)
                    || ids.put(id,e) != null || !identities.add(key.length() + ":" + key + e.digest)) {
                    throw new IOException("Invalid chapter");
                }
                backup.entries.add(e);
            }
            JSONArray days = root.getJSONArray("reading_day");
            for (int i = 0; i < days.length(); i++) {
                JSONObject row = days.getJSONObject(i);
                Entry e = ids.get(row.getLong("chapter_id"));
                String day = row.getString("day");
                long ms = row.getLong("ms");
                if (e == null || !LocalDate.parse(day).toString().equals(day) || ms < 0 || ms > 86400000L
                    || e.days.put(day,ms) != null) throw new IOException("Invalid reading time");
            }
            return backup;
        } catch (JSONException | RuntimeException e) {
            throw new IOException("Invalid backup", e);
        }
    }

    static Summary preview(ShelfDb shelf, Backup backup) throws IOException {
        return merge(shelf, backup, false);
    }

    static Summary apply(ShelfDb shelf, Backup backup) throws IOException {
        return merge(shelf, backup, true);
    }

    private static Summary merge(ShelfDb shelf, Backup backup, boolean apply) throws IOException {
        SQLiteDatabase db = shelf.getWritableDatabase();
        Summary result = new Summary();
        db.beginTransactionNonExclusive();
        try {
            for (Entry e : backup.entries) {
                // Exact content identity, not a title or a possibly reused source chapter number.
                try (Cursor c = db.rawQuery("SELECT c.id,c.directory,c.pages,c.format,c.opened FROM chapters c "
                    + "JOIN series s ON c.series_id=s.id WHERE s.key=? AND c.digest=? AND c.catalog_id=?",
                    new String[]{e.key,e.digest,e.catalogId})) {
                    if (!c.moveToFirst() || c.getInt(2) != e.pages || !e.format.equals(c.getString(3))) {
                        result.skipped++; continue;
                    }
                    long id = c.getLong(0);
                    String directory = c.getString(1);
                    if (!directory.matches("[a-fA-F0-9-]{36}")) throw new IOException("Invalid local directory");
                    File folder = new File(shelf.root,directory);
                    if (!new File(folder,"chapter."+e.format).isFile()) { result.skipped++; continue; }
                    result.matched++;
                    if (!apply) continue;
                    // Never overwrite a valid local bookmark. Copy the backup position only
                    // for a chapter that has no valid local record.
                    File position = new File(folder,"position");
                    Bookmark.Position local = Bookmark.read(position);
                    if (local.sequence == 0 && (e.page != 0 || e.fraction != 0 || e.percent != 0 || e.frontier != 0)) {
                        try {
                            Bookmark bookmark = new Bookmark(position);
                            bookmark.save(e.page,e.fraction,e.percent,e.frontier);
                            bookmark.flush();
                        } catch (IOException | RuntimeException failure) {
                            result.positionFailures++;
                        }
                    }
                    db.execSQL("UPDATE chapters SET opened=MAX(opened,?),done=MAX(done,?) WHERE id=?",
                        new Object[]{e.opened,e.done?1:0,id});
                    // Max rather than sum makes re-import idempotent. Distinct sessions
                    // on different devices cannot be safely added without session IDs.
                    for (Map.Entry<String,Long> day : e.days.entrySet()) {
                        db.execSQL("INSERT OR IGNORE INTO reading_day(chapter_id,day,ms) VALUES(?,?,?)",
                            new Object[]{id,day.getKey(),day.getValue()});
                        db.execSQL("UPDATE reading_day SET ms=MAX(ms,?) WHERE chapter_id=? AND day=?",
                            new Object[]{day.getValue(),id,day.getKey()});
                    }
                }
            }
            if (apply) db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return result;
    }

    static final class Backup { final List<Entry> entries = new ArrayList<>(); }
    private static final class Entry {
        String key,digest,catalogId,format;
        int pages,page,percent;
        long opened;
        double fraction,frontier;
        boolean done;
        final Map<String,Long> days = new HashMap<>();
    }
    static final class Summary { int matched, skipped, positionFailures; }
}
