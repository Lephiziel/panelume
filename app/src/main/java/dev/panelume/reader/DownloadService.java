package dev.panelume.reader;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class DownloadService extends Service {
    private static final String CHANNEL = "chapter_downloads";
    private static final int NOTIFICATION = 101;
    private static final int MAX_PAGE_BYTES = 48 * 1024 * 1024;
    private static final int MAX_PAGES = 6000;
    private static final String TAG = "PanelumeDownloads";
    static volatile boolean active;

    private final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private Future<?> transfer;
    private final CatalogHttp http = new CatalogHttp();
    private ShelfDb shelf;
    private CatalogQueue queue;
    private volatile boolean closed;
    private boolean working;
    private int startId;

    static void begin(Activity activity, boolean notifyPermission) {
        try {
            activity.startForegroundService(new Intent(activity, DownloadService.class));
        } catch (IllegalStateException | SecurityException e) {
            Toast.makeText(activity, R.string.download_paused, Toast.LENGTH_SHORT).show();
            return;
        }
        if (notifyPermission && Build.VERSION.SDK_INT >= 33
            && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            && !activity.getPreferences(MODE_PRIVATE).getBoolean("asked_download_notifications", false)) {
            activity.getPreferences(MODE_PRIVATE).edit().putBoolean("asked_download_notifications", true).apply();
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 23);
        }
    }

    static boolean allowBulk(Context context) {
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        return !Prefs.unmetered(context) || (connectivity != null && !connectivity.isActiveNetworkMetered());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        shelf = ShelfDb.get(this);
        queue = new CatalogQueue(shelf);
        Context text = Prefs.localized(this);
        NotificationChannel channel = new NotificationChannel(CHANNEL, text.getString(R.string.downloads),
            NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        WORKER.execute(queue::resetRunning);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int id) {
        startId = id;
        active = true;
        startForeground(NOTIFICATION, notification(null, 0, 0));
        startWork();
        return START_STICKY;
    }

    private void startWork() {
        if (working || closed) return;
        working = true;
        int request = startId;
        transfer = WORKER.submit(() -> runQueue(request));
    }

    private void runQueue(int request) {
        boolean blocked = false;
        try {
            while (!closed && !Thread.currentThread().isInterrupted()) {
                boolean allowBulk = allowBulk(this);
                CatalogQueue.Job job = queue.next(allowBulk);
                if (job == null) {
                    if (queue.hasQueued(allowBulk)) continue;
                    break;
                }
                try {
                    save(job);
                } catch (IOException | RuntimeException failure) {
                    String code = errorCode(failure);
                    if (closed || Thread.currentThread().isInterrupted() || (job.bulk && !allowBulk(this))) {
                        queue.pause(job.id);
                    } else {
                        queue.fail(job.id, code);
                    }
                    if (CatalogQueue.LIMIT.equals(code) || CatalogQueue.NETWORK.equals(code)) {
                        blocked = true;
                        break;
                    }
                    if (!CatalogQueue.ACCESS.equals(code)) Log.w(TAG, "Chapter transfer stopped: " + code);
                }
            }
        } finally {
            boolean waitForUser = blocked;
            main.post(() -> {
                working = false;
                if (closed) return;
                if ((!waitForUser || request != startId) && queue.hasQueued(allowBulk(this))) {
                    startWork();
                    return;
                }
                stopSelfResult(startId);
            });
        }
    }

    private String errorCode(Exception error) {
        if (error instanceof Fault) {
            int message = ((Fault) error).message;
            if (message == R.string.access_required) return CatalogQueue.ACCESS;
            if (message == R.string.source_limit) return CatalogQueue.LIMIT;
        }
        if (error instanceof UnknownHostException || error instanceof ConnectException || error instanceof SocketTimeoutException) {
            return CatalogQueue.NETWORK;
        }
        return CatalogQueue.FILE;
    }

    private void check(CatalogQueue.Job job) throws IOException {
        if (closed || Thread.currentThread().isInterrupted() || !queue.running(job.id)
            || (job.bulk && !allowBulk(this))) throw new Fault(R.string.operation_cancelled);
    }

    private void save(CatalogQueue.Job job) throws IOException {
        CatalogSource source = CatalogSource.named(job.title.provider);
        if (source == null) throw new Fault(R.string.source_error);
        if (job.bulk) SystemClock.sleep(300);
        check(job);
        List<String> pages = source.pages(job.episode);
        if (pages.isEmpty()) throw new Fault(R.string.empty_chapter);
        if (pages.size() > MAX_PAGES) throw new Fault(R.string.page_limit, MAX_PAGES);
        File target = ImportFiles.temporaryFile(this);
        try {
            long bytes = 0;
            try (FileOutputStream file = new FileOutputStream(target);
                 ZipOutputStream archive = new ZipOutputStream(file)) {
                archive.setLevel(Deflater.NO_COMPRESSION);
                for (int i = 0; i < pages.size(); i++) {
                    check(job);
                    if (job.bulk && i > 0) SystemClock.sleep(180);
                    bytes += copyPage(archive, i, pages.get(i), job, source, bytes);
                    if (!queue.progress(job.id, i + 1, pages.size())) throw new Fault(R.string.operation_cancelled);
                    if (i == 0 || i + 1 == pages.size() || i % 3 == 0) postProgress(job, i + 1, pages.size());
                }
                archive.finish();
                file.getFD().sync();
            }
            check(job);
            String number = job.episode.number.isEmpty() ? "special" : job.episode.number;
            ImportFiles.Staged staged = ImportFiles.inspectArchive(target, "Chapter " + number + ".cbz");
            ImportFiles.store(shelf, staged, directory -> queue.save(job, staged, directory));
        } finally {
            target.delete();
        }
    }

    private long copyPage(ZipOutputStream archive, int index, String address, CatalogQueue.Job job,
                          CatalogSource source, long previousBytes) throws IOException {
        String referer = source.imageReferer(job.episode);
        boolean report = "mangadex".equals(source.id()) && Uri.parse(address).getHost() != null
            && Uri.parse(address).getHost().endsWith(".mangadex.network");
        long began = SystemClock.elapsedRealtime();
        long size = 0;
        boolean success = false;
        boolean cached = false;
        try (CatalogHttp.Response response = http.open(address, "image/*", referer, source::imageHost);
             BufferedInputStream input = new BufferedInputStream(response.stream)) {
            cached = response.cached;
            if (response.length > MAX_PAGE_BYTES) throw new Fault(R.string.image_limit);
            input.mark(16);
            byte[] magic = new byte[12];
            int count = 0;
            while (count < magic.length) {
                int read = input.read(magic, count, magic.length - count);
                if (read < 0) break;
                count += read;
            }
            input.reset();
            String extension = imageType(magic, count);
            archive.putNextEntry(new ZipEntry(String.format(Locale.ROOT, "%05d.%s", index + 1, extension)));
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (closed || Thread.currentThread().isInterrupted()) throw new Fault(R.string.operation_cancelled);
                size += read;
                if (size > MAX_PAGE_BYTES) throw new Fault(R.string.image_limit);
                if (previousBytes + size > ImportFiles.MAX_BYTES) throw new Fault(R.string.file_limit);
                archive.write(buffer, 0, read);
            }
            archive.closeEntry();
            success = true;
            return size;
        } finally {
            if (report) MangaDexReport.send(address, success, cached, size, SystemClock.elapsedRealtime() - began);
        }
    }

    static String imageType(byte[] prefix, int count) throws IOException {
        if (count >= 3 && prefix[0] == (byte) 0xff && prefix[1] == (byte) 0xd8 && prefix[2] == (byte) 0xff) return "jpg";
        if (count >= 8 && prefix[0] == (byte) 0x89 && prefix[1] == 'P' && prefix[2] == 'N' && prefix[3] == 'G') return "png";
        if (count >= 12 && prefix[0] == 'R' && prefix[1] == 'I' && prefix[2] == 'F' && prefix[3] == 'F'
            && prefix[8] == 'W' && prefix[9] == 'E' && prefix[10] == 'B' && prefix[11] == 'P') return "webp";
        throw new Fault(R.string.bad_image);
    }

    private void postProgress(CatalogQueue.Job job, int current, int total) {
        main.post(() -> {
            if (!closed) getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(job, current, total));
        });
    }

    private Notification notification(CatalogQueue.Job job, int current, int total) {
        Context text = Prefs.localized(this);
        Intent intent = new Intent(this, DownloadsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_download_notification)
            .setContentTitle(job == null ? text.getString(R.string.downloads) : job.title.name)
            .setContentText(job == null ? text.getString(R.string.download_notification)
                : text.getString(R.string.download_progress, current, total))
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(total, current, total == 0);
        return builder.build();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onTimeout(int startId, int fgsType) {
        stopSelf();
    }

    @Override public void onDestroy() {
        closed = true;
        active = false;
        if (transfer != null) transfer.cancel(true);
        super.onDestroy();
    }
}
