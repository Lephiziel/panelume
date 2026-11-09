package dev.panelume.reader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class RemoteCovers {
    private static final CatalogHttp HTTP = new CatalogHttp();
    private static final ExecutorService WORKER = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int LIMIT = 2 * 1024 * 1024;
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };

    private RemoteCovers() {}

    static ImageView view(Context context, CatalogSource.Title title, int radius) {
        return show(context, title, radius, false);
    }

    static ImageView poster(Context context, CatalogSource.Title title, int radius) {
        return show(context, title, radius, true);
    }

    private static ImageView show(Context context, CatalogSource.Title title, int radius, boolean keep) {
        ImageView view = Ui.mark(context, radius);
        if (title == null) return view;
        CatalogSource source = CatalogSource.named(title.provider);
        if (source == null) return view;
        String key = title.key();
        String name = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)) + ".png";
        File file = new File(new File(context.getFilesDir(), "posters"), name);
        Bitmap cached = CACHE.get(key);
        if (cached != null) set(view, cached);
        if (cached != null && (!keep || file.isFile())) return view;
        WORKER.execute(() -> {
            Bitmap cover = cached == null ? CACHE.get(key) : cached;
            boolean saved = file.isFile();
            if (cover == null && saved) {
                cover = BitmapFactory.decodeFile(file.getPath());
                saved = cover != null;
            }
            if (cover == null && !title.cover.isEmpty()) cover = load(title.cover, source);
            if (cover == null) return;
            if (keep && !saved) save(file, cover);
            CACHE.put(key, cover);
            Bitmap image = cover;
            if (image != cached) MAIN.post(() -> set(view, image));
        });
        return view;
    }

    private static void save(File file, Bitmap bitmap) {
        File folder = file.getParentFile();
        if (!folder.isDirectory() && !folder.mkdirs() && !folder.isDirectory()) return;
        File pending = null;
        try {
            pending = File.createTempFile("poster-", ".part", folder);
            boolean encoded;
            try (FileOutputStream stream = new FileOutputStream(pending)) {
                encoded = bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            }
            if (encoded) pending.renameTo(file);
        } catch (IOException ignored) {
            // The in-memory cover remains available if storage is full.
        } finally {
            if (pending != null) pending.delete();
        }
    }

    private static void set(ImageView view, Bitmap cover) {
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setImageBitmap(cover);
    }

    private static Bitmap load(String address, CatalogSource source) {
        try (CatalogHttp.Response response = HTTP.open(address, "image/*", "", source::imageHost);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            if (response.length > LIMIT) return null;
            byte[] buffer = new byte[8192];
            int read;
            while ((read = response.stream.read(buffer)) != -1) {
                if (bytes.size() + read > LIMIT) return null;
                bytes.write(buffer, 0, read);
            }
            byte[] image = bytes.toByteArray();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(image, 0, image.length, options);
            if (options.outWidth < 1 || options.outHeight < 1) return null;
            options.inSampleSize = Math.max(1, Integer.highestOneBit(Math.max(options.outWidth, options.outHeight) / 640));
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(image, 0, image.length, options);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
