package dev.panelume.reader;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.OpenableColumns;

import javax.net.ssl.HttpsURLConnection;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ImportFiles {
    static final long MAX_BYTES = 512L * 1024 * 1024;
    private static final long ORPHAN_AGE_MS = 24L * 60 * 60 * 1000;
    private static final Pattern URL_NAME = Pattern.compile("(?i)(?:^|;)\\s*filename\\s*=\\s*(?:\"([^\"]*)\"|([^;]*))");
    private static final Pattern URL_NAME_UTF = Pattern.compile("(?i)(?:^|;)\\s*filename\\*\\s*=\\s*UTF-8'[^']*'([^;]*)");

    private ImportFiles() {}

    static Staged fromUri(Context context, Uri uri) throws IOException {
        if (!"content".equals(uri.getScheme())) throw new Fault(R.string.file_picker_required);
        ContentResolver resolver = context.getContentResolver();
        String name = uri.getLastPathSegment();
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && cursor.getString(0) != null) name = cursor.getString(0);
        }
        try (InputStream stream = resolver.openInputStream(uri)) {
            if (stream == null) throw new Fault(R.string.file_open_error);
            return stage(context, stream, name);
        }
    }

    static void validateLink(String link) throws IOException {
        parseLink(link);
    }

    private static URL parseLink(String link) throws IOException {
        try {
            URI uri = new URI(link);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new Fault(R.string.https_required);
            }
            return uri.toURL();
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new Fault(R.string.bad_url);
        }
    }

    static Staged fromLink(Context context, String link) throws IOException {
        URL url = parseLink(link);
        for (int redirects = 0; redirects <= 5; redirects++) {
            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(false);
            try {
                int code = connection.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new Fault(R.string.bad_redirect);
                    url = parseLink(new URL(url, location).toString());
                    continue;
                }
                if (code != 200) throw new Fault(R.string.http_status, code);
                if (connection.getContentLengthLong() > MAX_BYTES) throw new Fault(R.string.file_limit);
                String name = linkName(connection.getHeaderField("Content-Disposition"), url);
                try (InputStream stream = connection.getInputStream()) {
                    return stage(context, stream, name);
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new Fault(R.string.redirect_limit);
    }

    private static String linkName(String disposition, URL url) {
        if (disposition != null) {
            Matcher utf = URL_NAME_UTF.matcher(disposition);
            if (utf.find()) {
                try {
                    return URLDecoder.decode(utf.group(1).trim().replace("+", "%2B"), "UTF-8");
                } catch (IOException | IllegalArgumentException ignored) {
                    // Try the regular filename, then the final URL.
                }
            }
            Matcher regular = URL_NAME.matcher(disposition);
            if (regular.find()) return regular.group(1) != null ? regular.group(1) : regular.group(2).trim();
        }
        return Uri.parse(url.toString()).getLastPathSegment();
    }

    static File temporaryFile(Context context) throws IOException {
        File cache = new File(context.getCacheDir(), "imports");
        if (!cache.isDirectory() && !cache.mkdirs()) throw new Fault(R.string.storage_error);
        return File.createTempFile("chapter-", ".part", cache);
    }

    private static Staged stage(Context context, InputStream stream, String displayName) throws IOException {
        File copy = temporaryFile(context);
        boolean keep = false;
        try {
            BufferedInputStream in = new BufferedInputStream(stream);
            in.mark(2048);
            byte[] head = new byte[1024];
            int length = 0;
            while (length < head.length) {
                int count = in.read(head, length, head.length - length);
                if (count == -1) break;
                length += count;
            }
            in.reset();
            String format = detect(head, length);
            MessageDigest sha = sha256();
            try (FileOutputStream out = new FileOutputStream(copy)) {
                byte[] buffer = new byte[64 * 1024];
                long written = 0;
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new Fault(R.string.operation_cancelled);
                    written += count;
                    if (written > MAX_BYTES) throw new Fault(R.string.file_limit);
                    sha.update(buffer, 0, count);
                    out.write(buffer, 0, count);
                }
                out.getFD().sync();
            }
            Staged staged = inspect(copy, displayName, format, sha);
            keep = true;
            return staged;
        } finally {
            if (!keep) copy.delete();
        }
    }

    static Staged inspectArchive(File file, String displayName) throws IOException {
        MessageDigest sha = sha256();
        try (InputStream input = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new Fault(R.string.operation_cancelled);
                sha.update(buffer, 0, count);
            }
        }
        return inspect(file, displayName, "cbz", sha);
    }

    private static Staged inspect(File file, String displayName, String format, MessageDigest sha) throws IOException {
        String name = cleanName(displayName, format);
        Names.Guess guess = Names.guess(name);
        try (MangaDocument doc = MangaDocument.open(file, format)) {
            int width = 320;
            Bitmap cover = doc.tile(0, width, 0, Math.min(480, doc.heightAtWidth(0, width)));
            ByteArrayOutputStream thumbnail = new ByteArrayOutputStream();
            try {
                if (!cover.compress(Bitmap.CompressFormat.JPEG, 87, thumbnail)) throw new Fault(R.string.cover_error);
            } finally {
                cover.recycle();
            }
            String title = doc.series.isEmpty() ? guess.title : doc.series;
            String number = doc.number.isEmpty() ? guess.number : doc.number;
            StringBuilder digest = new StringBuilder(64);
            for (byte b : sha.digest()) digest.append(String.format(Locale.ROOT, "%02x", b & 255));
            return new Staged(file, name, format, digest.toString(), doc.pages.length,
                shorten(title, 120), number, guess.title, thumbnail.toByteArray());
        }
    }

    private static String detect(byte[] header, int count) throws IOException {
        if (count >= 4 && header[0] == 'P' && header[1] == 'K' && header[2] == 3 && header[3] == 4) return "cbz";
        String first = new String(header, 0, count, StandardCharsets.ISO_8859_1);
        if (first.contains("%PDF-") && !first.trim().startsWith("<")) return "pdf";
        throw new Fault(R.string.format_error);
    }

    private static String cleanName(String filename, String format) {
        if (filename == null || filename.trim().isEmpty()) return "Untitled." + format;
        int separator = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        String name = filename.substring(separator + 1).replaceAll("\\p{Cntrl}", "").trim();
        if (name.isEmpty()) return "Untitled." + format;
        if (!name.toLowerCase(Locale.ROOT).matches(".*\\.(pdf|cbz|zip)$")) name += "." + format;
        return name.length() <= 200 ? name : shorten(name, 191) + "." + format;
    }

    private static String shorten(String value, int maxLength) {
        if (value.length() <= maxLength) return value;
        int end = Character.isHighSurrogate(value.charAt(maxLength - 1)) ? maxLength - 1 : maxLength;
        return value.substring(0, end);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static long store(ShelfDb db, Staged staged, String title, String number) throws IOException {
        return store(db, staged, directory -> db.addChapter(title, number, staged.alias, staged.name,
            directory, staged.digest, staged.format, staged.pages));
    }

    interface Commit {
        long save(String directory) throws IOException;
    }

    static long store(ShelfDb db, Staged staged, Commit commit) throws IOException {
        if (!db.root.isDirectory() && !db.root.mkdirs()) throw new Fault(R.string.storage_error);
        String directory = UUID.randomUUID().toString();
        File folder = new File(db.root, directory);
        if (!folder.mkdir()) throw new Fault(R.string.storage_error);
        File content = new File(folder, "chapter." + staged.format);
        boolean committed = false;
        try {
            try (FileOutputStream cover = new FileOutputStream(new File(folder, "cover.jpg"))) {
                cover.write(staged.thumbnail);
            }
            if (!staged.file.renameTo(content)) throw new Fault(R.string.storage_error);
            long result = commit.save(directory);
            committed = true;
            return result;
        } finally {
            if (!committed) {
                if (content.isFile() && !content.renameTo(staged.file)) {
                    // Keep the copy in place for orphan cleanup if a rollback cannot restore it.
                    content = null;
                }
                if (content != null) removeDirectory(folder);
            }
        }
    }

    static void cleanup(Context context, ShelfDb db) {
        long before = System.currentTimeMillis() - ORPHAN_AGE_MS;
        File[] temporary = new File(context.getCacheDir(), "imports").listFiles();
        if (temporary != null) {
            for (File file : temporary) if (file.lastModified() < before) file.delete();
        }
        File[] chapters = db.root.listFiles();
        if (chapters != null) {
            for (File chapter : chapters) {
                if (chapter.lastModified() < before && !db.hasDirectory(chapter.getName())) removeDirectory(chapter);
            }
        }
    }

    static void removeDirectory(File directory) {
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) file.delete();
        directory.delete();
    }

    static final class Staged {
        final File file;
        final String name;
        final String format;
        final String digest;
        final int pages;
        final String guess;
        final String number;
        final String alias;
        final byte[] thumbnail;

        Staged(File file, String name, String format, String digest, int pages,
               String guess, String number, String alias, byte[] thumbnail) {
            this.file = file;
            this.name = name;
            this.format = format;
            this.digest = digest;
            this.pages = pages;
            this.guess = guess;
            this.number = number;
            this.alias = alias;
            this.thumbnail = thumbnail;
        }
    }
}
