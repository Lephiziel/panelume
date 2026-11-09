package dev.panelume.reader;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.Predicate;
import javax.net.ssl.HttpsURLConnection;

class CatalogHttp {
    static final String AGENT = "Panelume/0.2.3 (Android comic reader)";
    private static final int TEXT_LIMIT = 8 * 1024 * 1024;

    String text(String address, String accept, Predicate<String> allowed) throws IOException {
        try (Response response = open(address, accept, "", allowed);
             ByteArrayOutputStream content = new ByteArrayOutputStream()) {
            if (response.length > TEXT_LIMIT) throw new Fault(R.string.source_error);
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = response.stream.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new Fault(R.string.operation_cancelled);
                if (content.size() + read > TEXT_LIMIT) throw new Fault(R.string.source_error);
                content.write(buffer, 0, read);
            }
            return new String(content.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    Response open(String address, String accept, String referer, Predicate<String> allowed) throws IOException {
        URL url = check(address, allowed);
        for (int redirects = 0; redirects <= 4; redirects++) {
            if (Thread.currentThread().isInterrupted()) throw new Fault(R.string.operation_cancelled);
            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(18000);
            connection.setRequestProperty("Accept", accept);
            connection.setRequestProperty("Accept-Language", "en");
            connection.setRequestProperty("User-Agent", AGENT);
            if (!referer.isEmpty()) connection.setRequestProperty("Referer", referer);
            boolean ownsStream = false;
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new Fault(R.string.bad_redirect);
                    url = check(new URL(url, location).toExternalForm(), allowed);
                    continue;
                }
                if (status == 429) throw new Fault(R.string.source_limit);
                if (status == 401 || status == 403 || status == 404 || status == 451) {
                    throw new Fault(R.string.access_required);
                }
                if (status != 200) throw new Fault(R.string.http_status, status);
                Response response = new Response(connection.getInputStream(), connection.getContentLengthLong(),
                    connection.getContentType(), connection::disconnect);
                String cache = connection.getHeaderField("X-Cache");
                response.cached = cache != null && cache.startsWith("HIT");
                ownsStream = true;
                return response;
            } finally {
                if (!ownsStream) connection.disconnect();
            }
        }
        throw new Fault(R.string.redirect_limit);
    }

    static URL check(String address, Predicate<String> allowed) throws IOException {
        try {
            URI uri = URI.create(address);
            String host = uri.getHost();
            int port = uri.getPort();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null
                || (port != -1 && port != 443) || !allowed.test(host.toLowerCase(Locale.ROOT))) {
                throw new Fault(R.string.bad_redirect);
            }
            return uri.toURL();
        } catch (IllegalArgumentException e) {
            throw new Fault(R.string.bad_url);
        }
    }

    static String resolve(String base, String address) {
        if (address.isEmpty()) return "";
        try {
            return URI.create(base).resolve(address).toString();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    static class Response implements Closeable {
        final InputStream stream;
        final long length;
        final String type;
        boolean cached;
        private final Runnable disconnect;

        Response(InputStream stream, long length, String type, Runnable disconnect) {
            this.stream = stream;
            this.length = length;
            this.type = type == null ? "" : type;
            this.disconnect = disconnect;
        }

        @Override public void close() throws IOException {
            try {
                stream.close();
            } finally {
                disconnect.run();
            }
        }
    }
}
