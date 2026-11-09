package dev.panelume.reader;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class CatalogQueue {
    static final int QUEUED = 0;
    static final int RUNNING = 1;
    static final int FAILED = 2;
    static final String ACCESS = "access";
    static final String LIMIT = "limit";
    static final String NETWORK = "network";
    static final String FILE = "file";

    private static final String JOBS = "SELECT d.id,d.series_id,d.episode_id,d.number,d.caption,d.url,d.credit,"
        + "d.bulk,d.state,d.current_page,d.total_pages,d.error,"
        + "s.catalog_provider,s.catalog_id,s.title,s.catalog_url,s.catalog_cover,s.catalog_description"
        + " FROM downloads d JOIN series s ON s.id=d.series_id";

    private final ShelfDb shelf;

    CatalogQueue(ShelfDb shelf) { this.shelf = shelf; }

    static void createSchema(SQLiteDatabase db) {
        for (String column : new String[]{"catalog_provider", "catalog_id", "catalog_url", "catalog_cover", "catalog_description"}) {
            db.execSQL("ALTER TABLE series ADD COLUMN " + column + " TEXT NOT NULL DEFAULT ''");
        }
        for (String column : new String[]{"catalog_id", "caption", "credit"}) {
            db.execSQL("ALTER TABLE chapters ADD COLUMN " + column + " TEXT NOT NULL DEFAULT ''");
        }
        db.execSQL("CREATE UNIQUE INDEX catalog_episode ON chapters(series_id,catalog_id) WHERE catalog_id<>''");
        db.execSQL("CREATE TABLE downloads(id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "series_id INTEGER NOT NULL REFERENCES series(id) ON DELETE CASCADE,"
            + "episode_id TEXT NOT NULL,number TEXT NOT NULL,caption TEXT NOT NULL,url TEXT NOT NULL,credit TEXT NOT NULL,"
            + "bulk INTEGER NOT NULL,state INTEGER NOT NULL DEFAULT 0,current_page INTEGER NOT NULL DEFAULT 0,"
            + "total_pages INTEGER NOT NULL DEFAULT 0,error TEXT NOT NULL DEFAULT '',UNIQUE(series_id,episode_id))");
    }

    long enqueue(CatalogSource.Title title, List<CatalogSource.Episode> episodes, boolean bulk) {
        SQLiteDatabase db = shelf.getWritableDatabase();
        db.beginTransactionNonExclusive();
        try {
            long series = getOrCreate(db, title);
            for (CatalogSource.Episode episode : episodes) {
                if (episode.external || savedId(db, series, episode.id) > 0) continue;
                ContentValues values = new ContentValues();
                values.put("series_id", series);
                values.put("episode_id", episode.id);
                values.put("number", episode.number);
                values.put("caption", episode.name);
                values.put("url", episode.url);
                values.put("credit", episode.credit);
                values.put("bulk", bulk ? 1 : 0);
                if (db.insertWithOnConflict("downloads", null, values, SQLiteDatabase.CONFLICT_IGNORE) == -1) {
                    db.execSQL("UPDATE downloads SET bulk=MIN(bulk,?), state=CASE WHEN state=2 THEN 0 ELSE state END,"
                        + "error=CASE WHEN state=2 THEN '' ELSE error END WHERE series_id=? AND episode_id=? AND error<>?",
                        new Object[]{bulk ? 1 : 0, series, episode.id, ACCESS});
                }
            }
            db.setTransactionSuccessful();
            return series;
        } finally {
            db.endTransaction();
        }
    }

    long seriesId(CatalogSource.Title title) {
        return seriesId(shelf.getReadableDatabase(), title);
    }

    private static long seriesId(SQLiteDatabase db, CatalogSource.Title title) {
        try (Cursor cursor = db.rawQuery("SELECT id FROM series WHERE key=?", new String[]{key(title)})) {
            return cursor.moveToFirst() ? cursor.getLong(0) : -1;
        }
    }

    private static String key(CatalogSource.Title title) { return "@catalog/" + title.key(); }

    private static long getOrCreate(SQLiteDatabase db, CatalogSource.Title title) {
        long id = seriesId(db, title);
        ContentValues values = new ContentValues();
        if (!title.cover.isEmpty()) values.put("catalog_cover", title.cover);
        values.put("catalog_description", title.description);
        values.put("catalog_url", title.url);
        if (id > 0) {
            db.update("series", values, "id=?", args(id));
            return id;
        }
        values.put("key", key(title));
        values.put("title", title.name);
        values.put("catalog_provider", title.provider);
        values.put("catalog_id", title.id);
        return db.insertOrThrow("series", null, values);
    }

    CatalogSource.Title title(long series) {
        try (Cursor cursor = shelf.getReadableDatabase().rawQuery(
            "SELECT catalog_provider,catalog_id,title,catalog_url,catalog_cover,catalog_description FROM series"
                + " WHERE id=? AND catalog_provider<>''", args(series))) {
            return cursor.moveToFirst() ? title(cursor, 0) : null;
        }
    }

    private static CatalogSource.Title title(Cursor cursor, int offset) {
        return new CatalogSource.Title(cursor.getString(offset), cursor.getString(offset + 1),
            cursor.getString(offset + 2), cursor.getString(offset + 3), cursor.getString(offset + 4), cursor.getString(offset + 5));
    }

    private static long savedId(SQLiteDatabase db, long series, String episode) {
        try (Cursor cursor = db.rawQuery("SELECT id FROM chapters WHERE series_id=? AND catalog_id=?",
            new String[]{Long.toString(series), episode})) {
            return cursor.moveToFirst() ? cursor.getLong(0) : -1;
        }
    }

    List<Job> jobs(long series) {
        return read(JOBS + (series > 0 ? " WHERE d.series_id=?" : "")
            + " ORDER BY d.state=2,d.bulk,d.id", series > 0 ? args(series) : null);
    }

    Job next(boolean allowBulk) {
        List<Job> jobs = read(JOBS + " WHERE d.state=0" + (allowBulk ? "" : " AND d.bulk=0")
            + " ORDER BY d.bulk,d.id LIMIT 1", null);
        if (jobs.isEmpty()) return null;
        Job job = jobs.get(0);
        ContentValues change = new ContentValues();
        change.put("state", RUNNING);
        change.put("current_page", 0);
        change.put("total_pages", 0);
        return shelf.getWritableDatabase().update("downloads", change, "id=? AND state=0", args(job.id)) > 0
            ? job : null;
    }

    boolean hasQueued(boolean allowBulk) {
        try (Cursor c = shelf.getReadableDatabase().rawQuery(
            "SELECT 1 FROM downloads WHERE state=0" + (allowBulk ? "" : " AND bulk=0") + " LIMIT 1", null)) {
            return c.moveToFirst();
        }
    }

    void resetRunning() {
        shelf.getWritableDatabase().execSQL("UPDATE downloads SET state=0,current_page=0,total_pages=0 WHERE state=1");
    }

    boolean progress(long job, int current, int total) {
        ContentValues values = new ContentValues();
        values.put("current_page", current);
        values.put("total_pages", total);
        return shelf.getWritableDatabase().update("downloads", values, "id=? AND state=1", args(job)) > 0;
    }

    boolean running(long job) {
        try (Cursor c = shelf.getReadableDatabase().rawQuery("SELECT 1 FROM downloads WHERE id=? AND state=1", args(job))) {
            return c.moveToFirst();
        }
    }

    void fail(long job, String error) {
        shelf.getWritableDatabase().execSQL("UPDATE downloads SET state=2,error=? WHERE id=? AND state=1",
            new Object[]{error, job});
    }

    void cancel(long job) {
        shelf.getWritableDatabase().delete("downloads", "id=?", args(job));
    }

    void cancelSeries(long series) {
        shelf.getWritableDatabase().delete("downloads", "series_id=? AND state<>2", args(series));
    }

    void retry(long job) {
        shelf.getWritableDatabase().execSQL("UPDATE downloads SET state=0,error='' WHERE id=? AND state=2 AND error<>?",
            new Object[]{job, ACCESS});
    }

    void pause(long job) {
        shelf.getWritableDatabase().execSQL("UPDATE downloads SET state=0 WHERE id=? AND state=1", new Object[]{job});
    }

    long save(Job job, ImportFiles.Staged staged, String directory) throws IOException {
        SQLiteDatabase db = shelf.getWritableDatabase();
        db.beginTransactionNonExclusive();
        try {
            if (!running(job.id)) throw new Fault(R.string.operation_cancelled);
            long id = savedId(db, job.seriesId, job.episode.id);
            if (id < 0) {
                ContentValues values = new ContentValues();
                values.put("series_id", job.seriesId);
                try {
                    String number = Names.normalizeNumber(job.episode.number);
                    if (!number.isEmpty()) values.put("number", Double.parseDouble(number));
                } catch (IllegalArgumentException ignored) {
                    // Special chapter labels sort after numeric chapters.
                }
                values.put("number_text", job.episode.number);
                values.put("source", staged.name);
                values.put("catalog_id", job.episode.id);
                values.put("caption", job.episode.name);
                values.put("credit", job.episode.credit);
                values.put("directory", directory);
                values.put("digest", staged.digest + ":" + job.episode.id);
                values.put("format", staged.format);
                values.put("pages", staged.pages);
                id = db.insertOrThrow("chapters", null, values);
            }
            db.delete("downloads", "id=?", args(job.id));
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    private List<Job> read(String query, String[] args) {
        List<Job> result = new ArrayList<>();
        try (Cursor c = shelf.getReadableDatabase().rawQuery(query, args)) {
            while (c.moveToNext()) {
                CatalogSource.Episode episode = new CatalogSource.Episode(c.getString(2), c.getString(3), c.getString(4),
                    c.getString(5), c.getString(6), false);
                result.add(new Job(c.getLong(0), c.getLong(1), title(c, 12), episode, c.getInt(7) == 1,
                    c.getInt(8), c.getInt(9), c.getInt(10), c.getString(11)));
            }
        }
        return result;
    }

    private static String[] args(long id) { return new String[]{Long.toString(id)}; }

    static final class Job {
        final long id;
        final long seriesId;
        final CatalogSource.Title title;
        final CatalogSource.Episode episode;
        final boolean bulk;
        final int state;
        final int current;
        final int total;
        final String error;

        Job(long id, long seriesId, CatalogSource.Title title, CatalogSource.Episode episode, boolean bulk,
            int state, int current, int total, String error) {
            this.id = id;
            this.seriesId = seriesId;
            this.title = title;
            this.episode = episode;
            this.bulk = bulk;
            this.state = state;
            this.current = current;
            this.total = total;
            this.error = error;
        }
    }
}
