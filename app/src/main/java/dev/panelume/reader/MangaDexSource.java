package dev.panelume.reader;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class MangaDexSource implements CatalogSource {
    private static final String API = "https://api.mangadex.org";
    private static final String WEB = "https://mangadex.org";
    private static final int FEED_PAGE = 100;
    private final CatalogHttp http;

    MangaDexSource(CatalogHttp http) { this.http = http; }
    @Override public String id() { return "mangadex"; }
    @Override public String name() { return "MangaDex"; }

    @Override
    public boolean accepts(String host) {
        return host.equals("api.mangadex.org") || host.equals("mangadex.org")
            || host.equals("www.mangadex.org") || imageHost(host);
    }

    @Override
    public boolean imageHost(String host) {
        return host.equals("uploads.mangadex.org") || host.endsWith(".mangadex.network");
    }

    private boolean apiHost(String host) {
        return host.equals("api.mangadex.org");
    }

    @Override
    public List<Title> search(String query) throws IOException {
        if (query.startsWith("https://")) return Collections.emptyList();
        String endpoint = API + "/manga?limit=30&title=" + Uri.encode(query)
            + "&originalLanguage%5B%5D=ko&availableTranslatedLanguage%5B%5D=en"
            + "&contentRating%5B%5D=safe&contentRating%5B%5D=suggestive&includes%5B%5D=cover_art"
            + "&hasAvailableChapters=true";
        return parseTitles(http.text(endpoint, "application/json", this::apiHost));
    }

    List<Title> parseTitles(String body) throws IOException {
        try {
            JSONArray rows = new JSONObject(body).getJSONArray("data");
            List<Title> titles = new ArrayList<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                String id = value(row, "id");
                JSONObject attrs = row.getJSONObject("attributes");
                if (!isUuid(id) || !"ko".equals(value(attrs, "originalLanguage"))) continue;
                JSONArray languages = attrs.optJSONArray("availableTranslatedLanguages");
                if (languages != null && !hasEnglish(languages)) continue;
                String title = displayTitle(attrs);
                if (title.isEmpty()) continue;
                String filename = relationship(row, "cover_art", "fileName");
                String cover = filename.isEmpty() ? "" : "https://uploads.mangadex.org/covers/"
                    + id + "/" + Uri.encode(filename) + ".256.jpg";
                titles.add(new Title(id(), id, title, WEB + "/title/" + id, cover,
                    value(attrs.optJSONObject("description"), "en")));
            }
            return titles;
        } catch (JSONException e) {
            throw new Fault(R.string.source_error);
        }
    }

    @Override
    public List<Episode> episodes(Title title) throws IOException {
        if (!isUuid(title.id)) throw new Fault(R.string.bad_url);
        Map<String, Episode> chapters = new LinkedHashMap<>();
        Set<String> ids = new LinkedHashSet<>();
        for (int offset = 0; offset < 10000; offset += FEED_PAGE) {
            String endpoint = API + "/manga/" + title.id + "/feed?limit=" + FEED_PAGE + "&offset=" + offset
                + "&translatedLanguage%5B%5D=en&order%5Bchapter%5D=asc&order%5BpublishAt%5D=asc"
                + "&includes%5B%5D=scanlation_group&includeExternalUrl=0&includeFuturePublishAt=0"
                + "&contentRating%5B%5D=safe&contentRating%5B%5D=suggestive";
            String body = http.text(endpoint, "application/json", this::apiHost);
            int total = parseFeedPage(body, chapters, ids);
            if (total <= offset + FEED_PAGE) break;
        }
        List<Episode> result = new ArrayList<>(chapters.values());
        result.sort((left, right) -> {
            if (left.number.isEmpty() != right.number.isEmpty()) return left.number.isEmpty() ? 1 : -1;
            return Names.naturalCompare(left.number, right.number);
        });
        return result;
    }

    int parseFeedPage(String body, Map<String, Episode> chapters, Set<String> ids) throws IOException {
        try {
            JSONObject data = new JSONObject(body);
            JSONArray rows = data.getJSONArray("data");
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                String id = value(row, "id");
                JSONObject attrs = row.getJSONObject("attributes");
                if (!isUuid(id) || !"en".equals(value(attrs, "translatedLanguage"))
                    || !value(attrs, "externalUrl").isEmpty() || attrs.optInt("pages", 0) < 1 || !ids.add(id)) continue;
                String number = value(attrs, "chapter");
                try {
                    number = Names.normalizeNumber(number);
                } catch (IllegalArgumentException ignored) {
                    // Unnumbered and special releases can still have a chapter title.
                }
                String key = number.isEmpty() ? id : number;
                chapters.putIfAbsent(key, new Episode(id, number, value(attrs, "title"), WEB + "/chapter/" + id,
                    relationship(row, "scanlation_group", "name"), false));
            }
            return data.optInt("total", rows.length());
        } catch (JSONException e) {
            throw new Fault(R.string.source_error);
        }
    }

    @Override
    public List<String> pages(Episode episode) throws IOException {
        if (!isUuid(episode.id)) throw new Fault(R.string.bad_url);
        return parsePages(http.text(API + "/at-home/server/" + episode.id + "?forcePort443=true",
            "application/json", this::apiHost));
    }

    List<String> parsePages(String body) throws IOException {
        try {
            JSONObject result = new JSONObject(body);
            String base = result.getString("baseUrl").replaceFirst("/+$", "");
            CatalogHttp.check(base, this::imageHost);
            JSONObject chapter = result.getJSONObject("chapter");
            String hash = chapter.getString("hash");
            if (!hash.matches("[A-Za-z0-9_-]{8,128}")) throw new Fault(R.string.source_error);
            JSONArray files = chapter.optJSONArray("dataSaver");
            String quality = "data-saver";
            if (files == null || files.length() == 0) {
                files = chapter.getJSONArray("data");
                quality = "data";
            }
            List<String> urls = new ArrayList<>();
            for (int i = 0; i < files.length(); i++) {
                String file = files.getString(i);
                if (file.isEmpty() || file.contains("/") || file.contains("\\")) throw new Fault(R.string.source_error);
                urls.add(base + "/" + quality + "/" + hash + "/" + Uri.encode(file));
            }
            if (urls.isEmpty()) throw new Fault(R.string.access_required);
            return urls;
        } catch (JSONException e) {
            throw new Fault(R.string.source_error);
        }
    }

    private static String displayTitle(JSONObject attributes) {
        JSONObject title = attributes.optJSONObject("title");
        String english = value(title, "en");
        if (!english.isEmpty()) return english;
        JSONArray alternatives = attributes.optJSONArray("altTitles");
        if (alternatives != null) {
            for (int i = 0; i < alternatives.length(); i++) {
                english = value(alternatives.optJSONObject(i), "en");
                if (!english.isEmpty()) return english;
            }
        }
        if (title == null) return "";
        java.util.Iterator<String> keys = title.keys();
        return keys.hasNext() ? value(title, keys.next()) : "";
    }

    private static String relationship(JSONObject row, String type, String field) {
        JSONArray relations = row.optJSONArray("relationships");
        if (relations == null) return "";
        List<String> values = new ArrayList<>();
        for (int i = 0; i < relations.length(); i++) {
            JSONObject relation = relations.optJSONObject(i);
            if (relation == null || !type.equals(value(relation, "type"))) continue;
            String entry = value(relation.optJSONObject("attributes"), field);
            if (!entry.isEmpty()) values.add(entry);
        }
        return String.join(", ", values);
    }

    private static boolean hasEnglish(JSONArray values) {
        for (int i = 0; i < values.length(); i++) if ("en".equals(values.optString(i))) return true;
        return false;
    }

    private static String value(JSONObject object, String name) {
        return object == null || object.isNull(name) ? "" : object.optString(name, "").trim();
    }

    private static boolean isUuid(String text) {
        return text.matches("(?i)[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}");
    }
}
