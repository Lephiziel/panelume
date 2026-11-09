package dev.panelume.reader;

import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;

/** Health reports requested by the volunteer image network. */
final class MangaDexReport {
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(32), new ThreadPoolExecutor.DiscardOldestPolicy());
    private static long nextRequest;

    static {
        WORKER.allowCoreThreadTimeOut(true);
    }

    private MangaDexReport() {}

    static void send(String address, boolean success, boolean cached, long bytes, long duration) {
        WORKER.execute(() -> {
            long wait = nextRequest - SystemClock.elapsedRealtime();
            if (wait > 0) SystemClock.sleep(wait);
            nextRequest = SystemClock.elapsedRealtime() + 3100;
            HttpsURLConnection connection = null;
            try {
                JSONObject payload = new JSONObject().put("url", address).put("success", success)
                    .put("cached", cached).put("bytes", bytes).put("duration", duration);
                byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
                connection = (HttpsURLConnection) new URL("https://api.mangadex.network/report").openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("User-Agent", CatalogHttp.AGENT);
                connection.setFixedLengthStreamingMode(body.length);
                connection.setDoOutput(true);
                try (OutputStream output = connection.getOutputStream()) { output.write(body); }
                if (connection.getResponseCode() == 429) nextRequest = SystemClock.elapsedRealtime() + 60_000;
            } catch (IOException | JSONException ignored) {
                // Reporting is best-effort and independent of reading and local storage.
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }
}
