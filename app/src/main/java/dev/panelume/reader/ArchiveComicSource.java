package dev.panelume.reader;

import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ArchiveComicSource implements CatalogSource {
    private static final String WEB = "https://archive.org";
    private static final String ID = "[A-Za-z0-9_.-]{1,160}";
    private final CatalogHttp http;
    ArchiveComicSource(CatalogHttp http) { this.http = http; }
    @Override public String id() { return "archive-comics"; }
    @Override public String name() { return "Internet Archive Comics"; }
    @Override public boolean accepts(String host) { return host.equals("archive.org") || host.endsWith(".archive.org"); }
    @Override public boolean imageHost(String host) { return accepts(host); }
    private static boolean valid(String id) { return id.matches(ID) && !id.equals(".") && !id.equals(".."); }
    private static String value(JSONObject object, String name) {
        return object == null || object.isNull(name) ? "" : object.optString(name, "").trim();
    }
    private static boolean openLicense(JSONObject metadata) {
        String license = value(metadata, "licenseurl").toLowerCase(Locale.ROOT);
        String rights = value(metadata, "rights").toLowerCase(Locale.ROOT);
        return license.contains("creativecommons.org/publicdomain/") ||
            license.contains("creativecommons.org/licenses/") ||
            rights.equals("public domain") || rights.equals("public-domain");
    }
    private static boolean restricted(JSONObject m) {
        String flag = value(m, "access-restricted-item");
        return "true".equalsIgnoreCase(flag) || "1".equals(flag)
            || "true".equalsIgnoreCase(value(m, "is_lending"));
    }
    @Override public List<Title> search(String query) throws IOException {
        List<Title> results = new ArrayList<>();
        if (query.startsWith("https://")) {
            Uri uri = Uri.parse(query);
            List<String> path = uri.getPathSegments();
            if (uri.getHost() != null && accepts(uri.getHost()) && path.size() == 2
                && "details".equals(path.get(0)) && valid(path.get(1))) {
                JSONObject m = metadata(path.get(1));
                if (openLicense(m) && !restricted(m)) results.add(title(path.get(1), m));
            }
            return results;
        }
        // Escape search syntax; only public-domain / open-license comics.
        String safe = query.replaceAll("[^\\p{L}\\p{N} '’.-]", " ").trim();
        if (safe.isEmpty()) return results;
        String q = "mediatype:texts AND (subject:comics OR subject:comic OR subject:comicbooks) AND "
            + "(licenseurl:*creativecommons.org* OR rights:\"public domain\") AND title:\"" + safe + "\"";
        String url = WEB + "/advancedsearch.php?q=" + Uri.encode(q)
            + "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=licenseurl&fl%5B%5D=rights"
            + "&fl%5B%5D=description&rows=50&page=1&output=json";
        try {
            JSONArray docs = new JSONObject(http.text(url, "application/json", this::accepts))
                .getJSONObject("response").getJSONArray("docs");
            for (int i = 0; i < docs.length(); i++) {
                JSONObject row = docs.getJSONObject(i);
                String item = value(row, "identifier");
                if (valid(item) && openLicense(row) && !restricted(row)) results.add(title(item, row));
            }
            return results;
        } catch (JSONException e) { throw new Fault(R.string.source_error); }
    }
    private Title title(String item, JSONObject m) {
        String title = value(m, "title");
        return new Title(id(), item, title.isEmpty() ? item : title, WEB + "/details/" + item,
            WEB + "/services/img/" + item, "");
    }
    private JSONObject metadata(String item) throws IOException {
        if (!valid(item)) throw new Fault(R.string.bad_url);
        try {
            return new JSONObject(http.text(WEB + "/metadata/" + item, "application/json", this::accepts))
                .getJSONObject("metadata");
        } catch (JSONException e) { throw new Fault(R.string.source_error); }
    }
    @Override public List<Episode> episodes(Title title) throws IOException {
        JSONObject m = metadata(title.id);
        if (!openLicense(m) || restricted(m)) throw new Fault(R.string.access_required);
        List<Episode> issues = new ArrayList<>();
        issues.add(new Episode(title.id, "1", title.name, WEB + "/details/" + title.id,
            "Internet Archive · " + value(m, "licenseurl"), false));
        return issues;
    }
    @Override public List<String> pages(Episode episode) throws IOException {
        JSONObject m = metadata(episode.id);
        if (!openLicense(m) || restricted(m)) throw new Fault(R.string.access_required);
        int count = m.optInt("imagecount", 0);
        if (count <= 0) throw new Fault(R.string.source_error);
        if (count > 6000) throw new Fault(R.string.page_limit, 6000);
        List<String> links = new ArrayList<>();
        for (int i = 0; i < count; i++) links.add(WEB + "/download/" + episode.id + "/page/n" + i + ".jpg");
        return links;
    }
}
