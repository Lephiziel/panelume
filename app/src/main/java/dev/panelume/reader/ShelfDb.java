package dev.panelume.reader;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ShelfDb extends SQLiteOpenHelper {
    private static ShelfDb instance;
    private static final String ORDER = "c.number IS NULL, c.number, c.id";
    private static final String CHAPTERS =
        "SELECT c.id,c.series_id,s.title,c.number_text,c.source,c.directory,c.format,c.pages,c.opened,c.done,"
            + "COALESCE((SELECT SUM(d.ms) FROM reading_day d WHERE d.chapter_id=c.id),0)"
            + ",c.catalog_id,s.catalog_provider,c.caption,c.credit"
            + " FROM chapters c JOIN series s ON s.id=c.series_id";

    final File root;

    static synchronized ShelfDb get(Context context) {
        if (instance == null) instance = new ShelfDb(context.getApplicationContext());
        return instance;
    }

    private ShelfDb(Context context) {
        super(context, "panelume.db", null, 2);
        root = new File(context.getFilesDir(), "chapters");
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE series(id INTEGER PRIMARY KEY, title TEXT NOT NULL, key TEXT NOT NULL UNIQUE)");
        db.execSQL("CREATE TABLE aliases(key TEXT PRIMARY KEY, series_id INTEGER NOT NULL REFERENCES series(id) ON DELETE CASCADE)");
        db.execSQL("CREATE TABLE chapters(id INTEGER PRIMARY KEY, series_id INTEGER NOT NULL REFERENCES series(id),"
            + " number REAL, number_text TEXT NOT NULL, source TEXT NOT NULL, directory TEXT NOT NULL UNIQUE,"
            + " digest TEXT NOT NULL, format TEXT NOT NULL, pages INTEGER NOT NULL, opened INTEGER NOT NULL DEFAULT 0,"
            + " done INTEGER NOT NULL DEFAULT 0, UNIQUE(series_id,digest))");
        db.execSQL("CREATE INDEX chapter_order ON chapters(series_id,number,id)");
        db.execSQL("CREATE INDEX chapter_opened ON chapters(opened)");
        db.execSQL("CREATE TABLE reading_day(chapter_id INTEGER NOT NULL REFERENCES chapters(id) ON DELETE CASCADE,"
            + " day TEXT NOT NULL, ms INTEGER NOT NULL CHECK(ms>=0), PRIMARY KEY(chapter_id,day))");
        db.execSQL("CREATE INDEX reading_by_day ON reading_day(day)");
        CatalogQueue.createSchema(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion == 1 && newVersion == 2) {
            CatalogQueue.createSchema(db);
            return;
        }
        throw new IllegalStateException("No migration from " + oldVersion + " to " + newVersion);
    }

    List<Series> series() {
        List<Series> result = new ArrayList<>();
        String sql = "SELECT s.id,s.title,COUNT(c.id),SUM(c.done),"
            + " (SELECT cover.directory FROM chapters cover WHERE cover.series_id=s.id"
            + " ORDER BY cover.number IS NULL,cover.number,cover.id LIMIT 1),s.catalog_provider"
            + " FROM series s JOIN chapters c ON c.series_id=s.id GROUP BY s.id"
            + " ORDER BY MAX(c.opened) DESC,s.title COLLATE NOCASE";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, null)) {
            while (cursor.moveToNext()) {
                result.add(new Series(cursor.getLong(0), cursor.getString(1), cursor.getInt(2),
                    cursor.getInt(3), cursor.getString(4), cursor.getString(5)));
            }
        }
        return result;
    }

    String suggestedTitle(String guess) {
        if (Names.key(guess).isEmpty()) return guess;
        String key = Names.key(guess);
        String sql = "SELECT title FROM series WHERE key=? UNION ALL"
            + " SELECT s.title FROM aliases a JOIN series s ON a.series_id=s.id WHERE a.key=? LIMIT 1";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, new String[]{key, key})) {
            return cursor.moveToFirst() ? cursor.getString(0) : guess;
        }
    }

    List<Chapter> chapters(long seriesId) {
        return readChapters(CHAPTERS + " WHERE c.series_id=? ORDER BY " + ORDER, seriesId);
    }

    Chapter chapter(long id) {
        return first(readChapters(CHAPTERS + " WHERE c.id=?", id));
    }

    Chapter lastOpened() {
        return first(readChapters(CHAPTERS + " WHERE c.opened>0 ORDER BY c.opened DESC,c.id DESC LIMIT 1"));
    }

    Chapter next(Chapter current) {
        boolean passed = false;
        for (Chapter chapter : chapters(current.seriesId)) {
            if (passed) return chapter;
            passed = chapter.id == current.id;
        }
        return null;
    }

    private Chapter first(List<Chapter> chapters) {
        return chapters.isEmpty() ? null : chapters.get(0);
    }

    private List<Chapter> readChapters(String sql, long... args) {
        String[] selection = new String[args.length];
        for (int i = 0; i < args.length; i++) selection[i] = Long.toString(args[i]);
        List<Chapter> chapters = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, selection)) {
            while (cursor.moveToNext()) {
                chapters.add(new Chapter(cursor.getLong(0), cursor.getLong(1), cursor.getString(2),
                    cursor.getString(3), cursor.getString(4), cursor.getString(5), cursor.getString(6),
                    cursor.getInt(7), cursor.getLong(8), cursor.getInt(9) == 1, cursor.getLong(10),
                    cursor.getString(11), cursor.getString(12), cursor.getString(13), cursor.getString(14)));
            }
        }
        return chapters;
    }

    long addChapter(String title, String number, String alias, String filename, String directory,
                    String digest, String format, int pages) throws IOException {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransactionNonExclusive();
        try {
            long seriesId = getOrCreateSeries(db, title);
            try (Cursor cursor = db.rawQuery("SELECT id FROM chapters WHERE series_id=? AND digest=?",
                new String[]{Long.toString(seriesId), digest})) {
                if (cursor.moveToFirst()) throw new Fault(R.string.duplicate_file);
            }
            ensureNumberAvailable(db, seriesId, number, -1);
            ContentValues values = chapterFields(seriesId, number);
            values.put("source", filename);
            values.put("directory", directory);
            values.put("digest", digest);
            values.put("format", format);
            values.put("pages", pages);
            db.insertOrThrow("chapters", null, values);
            rememberAlias(db, alias, seriesId);
            db.setTransactionSuccessful();
            return seriesId;
        } finally {
            db.endTransaction();
        }
    }

    void editChapter(Chapter chapter, String title, String number) throws IOException {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransactionNonExclusive();
        try {
            long seriesId = getOrCreateSeries(db, title);
            ensureNumberAvailable(db, seriesId, number, chapter.id);
            db.update("chapters", chapterFields(seriesId, number), "id=?", new String[]{Long.toString(chapter.id)});
            rememberAlias(db, Names.guess(chapter.source).title, seriesId);
            db.execSQL("DELETE FROM series WHERE catalog_provider='' AND NOT EXISTS(SELECT 1 FROM chapters WHERE series_id=series.id)");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private ContentValues chapterFields(long seriesId, String number) {
        ContentValues values = new ContentValues();
        values.put("series_id", seriesId);
        values.put("number_text", number);
        if (number.isEmpty()) values.putNull("number");
        else values.put("number", Double.parseDouble(number));
        return values;
    }

    private long getOrCreateSeries(SQLiteDatabase db, String title) {
        String key = Names.key(title);
        if (key.isEmpty()) throw new IllegalArgumentException("Empty series name");
        try (Cursor cursor = db.rawQuery("SELECT id FROM series WHERE key=?", new String[]{key})) {
            if (cursor.moveToFirst()) return cursor.getLong(0);
        }
        ContentValues values = new ContentValues();
        values.put("key", key);
        values.put("title", title);
        return db.insertOrThrow("series", null, values);
    }

    private void ensureNumberAvailable(SQLiteDatabase db, long seriesId, String number, long except) throws IOException {
        if (number.isEmpty()) return;
        String sql = "SELECT 1 FROM chapters WHERE series_id=? AND number_text=? AND id<>? LIMIT 1";
        try (Cursor cursor = db.rawQuery(sql, new String[]{Long.toString(seriesId), number, Long.toString(except)})) {
            if (cursor.moveToFirst()) throw new Fault(R.string.duplicate_number, number);
        }
    }

    private void rememberAlias(SQLiteDatabase db, String alias, long seriesId) {
        String key = Names.key(alias);
        if (key.isEmpty()) return;
        ContentValues values = new ContentValues();
        values.put("key", key);
        values.put("series_id", seriesId);
        db.insertWithOnConflict("aliases", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    void opened(long chapterId) {
        getWritableDatabase().execSQL("UPDATE chapters SET opened=MAX(?,"
            + " (SELECT IFNULL(MAX(opened),0)+1 FROM chapters)) WHERE id=?",
            new Object[]{System.currentTimeMillis(), chapterId});
    }

    void finish(long chapterId) {
        getWritableDatabase().execSQL("UPDATE chapters SET done=1 WHERE id=?", new Object[]{chapterId});
    }

    void addTime(long chapterId, Map<LocalDate, Long> segments) {
        if (segments.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransactionNonExclusive();
        try {
            for (Map.Entry<LocalDate, Long> entry : segments.entrySet()) {
                String day = entry.getKey().toString();
                long ms = entry.getValue();
                ContentValues values = new ContentValues();
                values.put("chapter_id", chapterId);
                values.put("day", day);
                values.put("ms", ms);
                if (db.insertWithOnConflict("reading_day", null, values, SQLiteDatabase.CONFLICT_IGNORE) == -1) {
                    db.execSQL("UPDATE reading_day SET ms=ms+? WHERE chapter_id=? AND day=?",
                        new Object[]{ms, chapterId, day});
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    Map<LocalDate, Long> dailyTotals(LocalDate first, LocalDate last) {
        Map<LocalDate, Long> result = new HashMap<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
            "SELECT day,SUM(ms) FROM reading_day WHERE day BETWEEN ? AND ? GROUP BY day",
            new String[]{first.toString(), last.toString()})) {
            while (cursor.moveToNext()) result.put(LocalDate.parse(cursor.getString(0)), cursor.getLong(1));
        }
        return result;
    }

    List<DailyEntry> day(Context context, LocalDate date) {
        List<DailyEntry> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
            "SELECT c.id,s.title,c.number_text,c.source,d.ms,c.caption FROM reading_day d"
                + " JOIN chapters c ON c.id=d.chapter_id JOIN series s ON s.id=c.series_id"
                + " WHERE d.day=? ORDER BY d.ms DESC,c.id",
            new String[]{date.toString()})) {
            while (cursor.moveToNext()) {
                result.add(new DailyEntry(cursor.getLong(0), cursor.getString(1),
                    chapterLabel(context, cursor.getString(2), cursor.getString(3), cursor.getString(5)), cursor.getLong(4)));
            }
        }
        return result;
    }

    void remove(Chapter chapter) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransactionNonExclusive();
        try {
            db.delete("chapters", "id=?", new String[]{Long.toString(chapter.id)});
            db.execSQL("DELETE FROM series WHERE catalog_provider='' AND NOT EXISTS(SELECT 1 FROM chapters WHERE series_id=series.id)");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        ImportFiles.removeDirectory(new File(root, chapter.directory));
    }

    boolean hasDirectory(String directory) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
            "SELECT 1 FROM chapters WHERE directory=?", new String[]{directory})) {
            return cursor.moveToFirst();
        }
    }

    File cover(String directory) {
        return new File(new File(root, directory), "cover.jpg");
    }

    static String chapterLabel(Context context, String number, String filename, String caption) {
        if (number.isEmpty() && !caption.isEmpty()) return caption;
        if (number.isEmpty()) return filename.replaceFirst("(?i)\\.(pdf|cbz|zip)$", "");
        return context.getString(R.string.chapter_label, number);
    }

    static final class Series {
        final long id;
        final String title;
        final int count;
        final int done;
        final String coverDirectory;
        final String provider;

        Series(long id, String title, int count, int done, String coverDirectory, String provider) {
            this.id = id;
            this.title = title;
            this.count = count;
            this.done = done;
            this.coverDirectory = coverDirectory;
            this.provider = provider;
        }
    }

    static final class Chapter {
        final long id;
        final long seriesId;
        final String title;
        final String number;
        final String source;
        final String directory;
        final String format;
        final int pages;
        final long opened;
        final boolean done;
        final long ms;
        final String catalogId;
        final String provider;
        final String caption;
        final String credit;

        Chapter(long id, long seriesId, String title, String number, String source, String directory,
                String format, int pages, long opened, boolean done, long ms,
                String catalogId, String provider, String caption, String credit) {
            this.id = id;
            this.seriesId = seriesId;
            this.title = title;
            this.number = number;
            this.source = source;
            this.directory = directory;
            this.format = format;
            this.pages = pages;
            this.opened = opened;
            this.done = done;
            this.ms = ms;
            this.catalogId = catalogId;
            this.provider = provider;
            this.caption = caption;
            this.credit = credit;
        }

        File file(File root) {
            return new File(new File(root, directory), "chapter." + format);
        }

        File bookmark(File root) {
            return new File(new File(root, directory), "position");
        }

        String label(Context context) {
            return chapterLabel(context, number, source, caption);
        }
    }

    static final class DailyEntry {
        final long chapterId;
        final String series;
        final String label;
        final long ms;

        DailyEntry(long chapterId, String series, String label, long ms) {
            this.chapterId = chapterId;
            this.series = series;
            this.label = label;
            this.ms = ms;
        }
    }
}
