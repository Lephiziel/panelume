package dev.panelume.reader;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.util.JsonWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/** Read-only, versioned metadata snapshot. Does not copy page archives or restore data. */
final class ProgressExport {
    private ProgressExport() {}

    static void save(Context context, Uri target) throws IOException {
        File temp = File.createTempFile("panelume-progress-", ".json", context.getCacheDir());
        try {
            try (OutputStream stream = new FileOutputStream(temp)) {
                write(context, ShelfDb.get(context), stream);
            }
            try (FileInputStream input = new FileInputStream(temp);
                 OutputStream output = context.getContentResolver().openOutputStream(target, "wt")) {
                if (output == null) throw new IOException("Cannot open destination");
                byte[] buffer = new byte[32768];
                int n;
                while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
                output.flush();
            }
        } finally {
            temp.delete();
        }
    }

    static void write(Context context, ShelfDb shelf, OutputStream output) throws IOException {
        SQLiteDatabase db = shelf.getReadableDatabase();
        JsonWriter json = new JsonWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        db.beginTransactionNonExclusive();
        try {
            json.beginObject();
            json.name("format").value("panelume-progress");
            json.name("version").value(1);
            json.name("exported_at_ms").value(System.currentTimeMillis());
            json.name("chapter_files_included").value(false);
            json.name("settings").beginObject();
            json.name("language").value(Prefs.language(context));
            json.name("unmetered").value(Prefs.unmetered(context));
            json.name("last_search").value(Prefs.lastSearch(context));
            json.endObject();
            rows(json, db, "series", "SELECT id,title,key,catalog_provider,catalog_id,catalog_url,catalog_cover,catalog_description FROM series ORDER BY id", null);
            rows(json, db, "aliases", "SELECT key,series_id FROM aliases ORDER BY key", null);
            rows(json, db, "chapters", "SELECT id,series_id,number,number_text,source,directory,digest,format,pages,opened,done,catalog_id,caption,credit FROM chapters ORDER BY id", shelf.root);
            rows(json, db, "reading_day", "SELECT chapter_id,day,ms FROM reading_day ORDER BY chapter_id,day", null);
            json.name("complete").value(true);
            json.endObject();
            json.flush();
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static void rows(JsonWriter json, SQLiteDatabase db, String name, String sql, File root) throws IOException {
        json.name(name).beginArray();
        try (Cursor cursor = db.rawQuery(sql, null)) {
            while (cursor.moveToNext()) {
                json.beginObject();
                for (int i = 0; i < cursor.getColumnCount(); i++) {
                    json.name(cursor.getColumnName(i));
                    switch (cursor.getType(i)) {
                        case Cursor.FIELD_TYPE_NULL: json.nullValue(); break;
                        case Cursor.FIELD_TYPE_INTEGER: json.value(cursor.getLong(i)); break;
                        case Cursor.FIELD_TYPE_FLOAT: json.value(cursor.getDouble(i)); break;
                        case Cursor.FIELD_TYPE_STRING: json.value(cursor.getString(i)); break;
                        default: throw new IOException("Unexpected metadata field type");
                    }
                }
                if (root != null) {
                    String directory = cursor.getString(cursor.getColumnIndexOrThrow("directory"));
                    if (!directory.matches("[a-fA-F0-9-]{36}")) throw new IOException("Invalid chapter directory");
                    json.name("archive_present").value(OfflineStorage.archiveBytes(root,directory,
                        cursor.getString(cursor.getColumnIndexOrThrow("format")))>0);
                    Bookmark.Position p = Bookmark.read(new File(new File(root, directory), "position"));
                    json.name("bookmark").beginObject();
                    json.name("page").value(p.page);
                    json.name("fraction").value(p.fraction);
                    json.name("percent").value(p.percent);
                    json.name("frontier").value(p.frontier);
                    json.endObject();
                }
                json.endObject();
            }
        }
        json.endArray();
    }
}
