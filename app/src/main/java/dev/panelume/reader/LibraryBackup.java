package dev.panelume.reader;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.*;

/** Portable v1 ZIP envelope around the existing v1 JSON progress format. */
final class LibraryBackup {
    private static final int META_LIMIT = 16 * 1024 * 1024;
    private static final long COVER_LIMIT = 2L * 1024 * 1024;
    private static final long TOTAL_LIMIT = 32L * 1024 * 1024 * 1024;
    private static final String METADATA = "progress.json";
    private static final String END = "panelume-library-v1.complete";
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private LibraryBackup() {}

    static boolean empty(ShelfDb shelf) { return empty(shelf.getReadableDatabase()); }
    private static boolean empty(SQLiteDatabase db) {
        try (Cursor c = db.rawQuery("SELECT (SELECT COUNT(*) FROM series)+(SELECT COUNT(*) FROM chapters)+(SELECT COUNT(*) FROM downloads)",null)) {
            return c.moveToFirst() && c.getLong(0) == 0;
        }
    }

    static void write(Context context, ShelfDb shelf, OutputStream output) throws IOException {
        ByteArrayOutputStream meta = new ByteArrayOutputStream();
        ProgressExport.write(context,shelf,new OutputStream() {
            @Override public void write(int b) throws IOException {
                if (meta.size() >= META_LIMIT) throw new IOException("Metadata too large");
                meta.write(b);
            }
            @Override public void write(byte[] b,int off,int len) throws IOException {
                if (len > META_LIMIT-meta.size()) throw new IOException("Metadata too large");
                meta.write(b,off,len);
            }
        });
        byte[] bytes = meta.toByteArray();
        Model model = model(bytes);
        Budget budget = new Budget();
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(output))) {
            zip.setLevel(0);
            zip.putNextEntry(new ZipEntry(METADATA));
            zip.write(bytes);
            zip.closeEntry();
            for (Chapter chapter : model.chapters) {
                File folder = new File(shelf.root,chapter.directory);
                if (chapter.present) exportFile(zip,shelf.root,folder,chapter,"chapter."+chapter.format,ImportFiles.MAX_BYTES,budget,chapter.hash);
                exportFile(zip,shelf.root,folder,chapter,"cover.jpg",COVER_LIMIT,budget,null);
            }
            zip.putNextEntry(new ZipEntry(END));
            zip.write(1);
            zip.closeEntry();
        }
    }

    private static void exportFile(ZipOutputStream zip, File root, File folder, Chapter chapter,
                                   String name,long limit,Budget budget,String expected) throws IOException {
        File file = new File(folder,name);
        if (!file.getCanonicalFile().equals(new File(root.getCanonicalFile(),chapter.directory+"/"+name))
            || !file.isFile()) throw new IOException("Missing or unexpected file");
        zip.putNextEntry(new ZipEntry(path(chapter,name)));
        try (InputStream input = new FileInputStream(file)) {
            copy(input,zip,limit,budget,expected);
        }
        zip.closeEntry();
    }

    static Preview preview(InputStream input) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(input))) {
            byte[] metadata = readMetadata(zip);
            Model model = model(metadata);
            int missing=0;
            for (Chapter chapter:model.chapters) if (!chapter.present) missing++;
            return new Preview(model.series.length(),model.chapters.size(),missing,hash(metadata),model.settings);
        }
    }

    /** Preferences are a separate commit after successful library restoration. */
    static boolean restoreWithSettings(Context context, ShelfDb shelf, InputStream input, Preview preview,
                                       int selected) throws IOException {
        restore(shelf,input,preview);
        try {
            return Prefs.restore(context,preview.settings,selected);
        } catch (RuntimeException preferencesFailure) {
            // Library commit has already succeeded. Do not misreport it as a failed restore.
            return false;
        }
    }

    static void restore(ShelfDb shelf, InputStream input, Preview preview) throws IOException {
        if (!empty(shelf)) throw new IOException("Library not empty");
        List<File> staged = new ArrayList<>();
        boolean committed = false;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(input))) {
            byte[] metadata = readMetadata(zip);
            if (!hash(metadata).equals(preview.metadataHash)) throw new IOException("Backup changed after preview");
            Model model = model(metadata);
            if (!shelf.root.isDirectory() && !shelf.root.mkdirs()) throw new IOException("Cannot create chapter root");
            Budget budget = new Budget();
            // The new directories have unique local names and are unreferenced until commit.
            for (Chapter chapter : model.chapters) {
                File folder = new File(shelf.root,UUID.randomUUID().toString());
                if (!folder.mkdir()) throw new IOException("Cannot stage chapter");
                staged.add(folder);
                chapter.localDirectory = folder.getName();
                if (chapter.present) extract(zip,chapter,folder,"chapter."+chapter.format,ImportFiles.MAX_BYTES,budget,chapter.hash);
                extract(zip,chapter,folder,"cover.jpg",COVER_LIMIT,budget,null);
                JSONObject bookmark = chapter.row.getJSONObject("bookmark");
                Bookmark position = new Bookmark(new File(folder,"position"));
                position.save(bookmark.getInt("page"),bookmark.getDouble("fraction"),
                    bookmark.getInt("percent"),bookmark.getDouble("frontier"));
                position.flush();
            }
            expect(zip,END);
            if (zip.read()!=1 || zip.read()!=-1) throw new IOException("Invalid completion marker");
            zip.closeEntry();
            if (zip.getNextEntry()!=null) throw new IOException("Unexpected ZIP entry");
            SQLiteDatabase db = shelf.getWritableDatabase();
            db.beginTransactionNonExclusive();
            try {
                if (!empty(db)) throw new IOException("Library changed during restore");
                for (int i=0;i<model.series.length();i++) {
                    JSONObject row=model.series.getJSONObject(i);
                    ContentValues v=new ContentValues();
                    v.put("id",row.getLong("id"));
                    strings(v,row,"title","key","catalog_provider","catalog_id","catalog_url","catalog_cover","catalog_description");
                    db.insertOrThrow("series",null,v);
                }
                for (int i=0;i<model.aliases.length();i++) {
                    JSONObject row=model.aliases.getJSONObject(i);
                    ContentValues v=new ContentValues();
                    v.put("key",row.getString("key")); v.put("series_id",row.getLong("series_id"));
                    db.insertOrThrow("aliases",null,v);
                }
                for (Chapter chapter:model.chapters) {
                    JSONObject row=chapter.row;
                    ContentValues v=new ContentValues();
                    v.put("id",row.getLong("id")); v.put("series_id",row.getLong("series_id"));
                    if (row.isNull("number")) v.putNull("number"); else v.put("number",row.getDouble("number"));
                    strings(v,row,"number_text","source","digest","format","catalog_id","caption","credit");
                    v.put("directory",chapter.localDirectory);
                    v.put("pages",row.getInt("pages")); v.put("opened",row.getLong("opened")); v.put("done",row.getInt("done"));
                    db.insertOrThrow("chapters",null,v);
                }
                for (int i=0;i<model.days.length();i++) {
                    JSONObject row=model.days.getJSONObject(i);
                    ContentValues v=new ContentValues();
                    v.put("chapter_id",row.getLong("chapter_id"));
                    v.put("day",row.getString("day")); v.put("ms",row.getLong("ms"));
                    db.insertOrThrow("reading_day",null,v);
                }
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
            committed=true;
        } catch (JSONException | RuntimeException e) {
            throw new IOException("Invalid library backup",e);
        } finally {
            if (!committed) for (File folder:staged) {
                // Commit acknowledgement can fail. Never remove a directory if its reference
                // exists or the DB cannot be checked; old orphan cleanup handles leftovers.
                try { if (!shelf.hasDirectory(folder.getName())) ImportFiles.removeDirectory(folder); }
                catch (RuntimeException uncertain) { /* retain */ }
            }
        }
    }

    private static void extract(ZipInputStream zip,Chapter chapter,File folder,String name,long limit,Budget budget,String expected) throws IOException {
        expect(zip,path(chapter,name));
        try (FileOutputStream out=new FileOutputStream(new File(folder,name))) {
            copy(zip,out,limit,budget,expected);
            out.getFD().sync();
        }
        zip.closeEntry();
    }

    private static String path(Chapter chapter,String name) {
        return "chapters/"+chapter.directory+"/"+name;
    }
    private static void expect(ZipInputStream zip,String name) throws IOException {
        ZipEntry entry=zip.getNextEntry();
        if (entry==null || entry.isDirectory() || !name.equals(entry.getName())) throw new IOException("Missing or unexpected ZIP entry");
    }
    private static byte[] readMetadata(ZipInputStream zip) throws IOException {
        expect(zip,METADATA);
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        copy(zip,out,META_LIMIT,new Budget(),null);
        zip.closeEntry();
        return out.toByteArray();
    }

    private static void copy(InputStream input,OutputStream output,long limit,Budget budget,String expected) throws IOException {
        MessageDigest sha=digest();
        long count=0;
        byte[] buffer=new byte[65536];
        int n;
        while ((n=input.read(buffer))!=-1) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Interrupted");
            if (n>limit-count || n>TOTAL_LIMIT-budget.bytes) throw new IOException("Backup size limit");
            count+=n; budget.bytes+=n;
            sha.update(buffer,0,n); output.write(buffer,0,n);
        }
        if (count==0 || (expected!=null && !expected.equals(hex(sha.digest())))) throw new IOException("Missing or mismatched file");
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static String hash(byte[] bytes) { return hex(digest().digest(bytes)); }
    private static String hex(byte[] bytes) {
        StringBuilder s=new StringBuilder(bytes.length*2);
        for (byte b:bytes) s.append(String.format(Locale.ROOT,"%02x",b&255));
        return s.toString();
    }

    private static void strings(ContentValues values,JSONObject row,String... keys) throws JSONException {
        for (String key:keys) values.put(key,row.getString(key));
    }

    private static Model model(byte[] bytes) throws IOException {
        String text=new String(bytes,StandardCharsets.UTF_8);
        ProgressRestore.parse(text);
        try {
            JSONObject root=new JSONObject(text);
            Model model=new Model(root.getJSONArray("series"),root.getJSONArray("aliases"),root.getJSONArray("reading_day"),BackupSettings.parse(root.optJSONObject("settings")));
            Map<Long,String> providers=new HashMap<>();
            for (int i=0;i<model.series.length();i++) {
                JSONObject row=model.series.getJSONObject(i);
                String provider=row.getString("catalog_provider"),catalogId=row.getString("catalog_id");
                String key=row.getString("key"), title=row.getString("title");
                if (title.isEmpty()) throw new IOException("Empty title");
                if (provider.isEmpty()) {
                    if (!catalogId.isEmpty() || !Names.key(title).equals(key)) throw new IOException("Invalid local series");
                } else {
                    CatalogSource source=CatalogSource.named(provider);
                    if (source==null || catalogId.isEmpty() || !key.equals("@catalog/"+provider+":"+catalogId)) throw new IOException("Invalid catalogue");
                    String url=row.getString("catalog_url"),cover=row.getString("catalog_cover");
                    if (!url.isEmpty()) CatalogHttp.check(url,source::accepts);
                    if (!cover.isEmpty()) CatalogHttp.check(cover,h->source.accepts(h)||source.imageHost(h));
                }
                row.getString("catalog_description");
                providers.put(row.getLong("id"),provider);
            }
            Set<String> aliases=new HashSet<>();
            for (int i=0;i<model.aliases.length();i++) {
                JSONObject row=model.aliases.getJSONObject(i);
                String key=row.getString("key");
                if (key.isEmpty() || !aliases.add(key) || !providers.containsKey(row.getLong("series_id"))) throw new IOException("Invalid alias");
            }
            JSONArray chapters=root.getJSONArray("chapters");
            Set<String> directories=new HashSet<>();
            Set<String> catalogEpisodes=new HashSet<>();
            for (int i=0;i<chapters.length();i++) {
                JSONObject row=chapters.getJSONObject(i);
                long series=row.getLong("series_id");
                String provider=providers.get(series);
                String directory=row.getString("directory"),format=row.getString("format"),stored=row.getString("digest");
                String episode=row.getString("catalog_id");
                if (provider==null || !directory.matches(UUID_PATTERN) || !directories.add(directory)
                    || row.getInt("pages")>6000 || stored.length()<64 || !stored.substring(0,64).matches("[a-f0-9]{64}")) throw new IOException("Invalid chapter identity");
                String hash=stored.substring(0,64);
                if (provider.isEmpty()) {
                    if (!episode.isEmpty() || !stored.equals(hash)) throw new IOException("Invalid local digest");
                } else if (episode.isEmpty() || !stored.equals(hash+":"+episode) || !catalogEpisodes.add(series+":"+episode)) {
                    throw new IOException("Invalid catalogue chapter");
                }
                String number=row.getString("number_text");
                String normalized;
                try { normalized=Names.normalizeNumber(number); }
                catch (IllegalArgumentException special) {
                    if (provider.isEmpty()) throw new IOException("Invalid local chapter number",special);
                    normalized="";
                }
                if (row.isNull("number")) {
                    if (!normalized.isEmpty()) throw new IOException("Missing numeric chapter number");
                } else if (!Double.isFinite(row.getDouble("number")) || normalized.isEmpty()
                    || Double.parseDouble(normalized)!=row.getDouble("number")) {
                    throw new IOException("Invalid chapter number");
                }
                row.getString("source"); row.getString("caption"); row.getString("credit");
                // Old v1 backups always contain the archive. New v1 backups may omit
                // online archives intentionally while retaining their reading metadata.
                Object presentValue = row.has("archive_present") ? row.get("archive_present") : Boolean.TRUE;
                if (!(presentValue instanceof Boolean)) throw new IOException("Invalid archive presence");
                boolean present = (Boolean) presentValue;
                if (!present && provider.isEmpty()) throw new IOException("Missing local chapter archive");
                model.chapters.add(new Chapter(row,directory,format,hash,present));
            }
            return model;
        } catch (JSONException | RuntimeException e) { throw new IOException("Invalid library metadata",e); }
    }

    static final class Preview {
        final int series,chapters,missing;
        final String metadataHash;
        final BackupSettings settings;
        Preview(int series,int chapters,int missing,String metadataHash,BackupSettings settings) {
            this.series=series; this.chapters=chapters; this.missing=missing; this.metadataHash=metadataHash; this.settings=settings;
        }
    }
    private static final class Budget { long bytes; }
    private static final class Model {
        final JSONArray series,aliases,days;
        final List<Chapter> chapters=new ArrayList<>();
        final BackupSettings settings;
        Model(JSONArray series,JSONArray aliases,JSONArray days,BackupSettings settings) {
            this.series=series; this.aliases=aliases; this.days=days; this.settings=settings;
        }
    }
    private static final class Chapter {
        final JSONObject row;
        final String directory,format,hash;
        final boolean present;
        String localDirectory;
        Chapter(JSONObject row,String directory,String format,String hash,boolean present) {
            this.row=row; this.directory=directory; this.format=format; this.hash=hash; this.present=present;
        }
    }
}
