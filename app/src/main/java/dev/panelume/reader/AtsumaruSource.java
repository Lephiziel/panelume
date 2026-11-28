package dev.panelume.reader;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Atsumaru's public JSON API. Chapter images remain on Atsumaru's own CDN.
 * No user credentials, HTML scraping, account-gated episodes, or DRM handling.
 */
final class AtsumaruSource implements CatalogSource {
    private static final String WEB = "https://atsu.moe";
    private static final String API = WEB + "/api";
    private static final String CDN = "https://cdn.atsu.moe";
    private static final String ID = "[A-Za-z0-9_-]{2,80}";
    private final CatalogHttp http;

    AtsumaruSource(CatalogHttp http) { this.http = http; }

    @Override public String id() { return "atsumaru"; }
    @Override public String name() { return "Atsumaru"; }

    @Override public boolean accepts(String host) {
        return host.equals("atsu.moe") || host.equals("www.atsu.moe") || imageHost(host);
    }

    @Override public boolean imageHost(String host) { return host.equals("cdn.atsu.moe"); }
    @Override public String imageReferer(Episode episode) { return WEB + "/"; }

    @Override public Title knownTitle(String query) {
        if (!query.startsWith("https://")) return null;
        Uri uri = Uri.parse(query);
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || host == null || !accepts(host)) return null;
        List<String> segments = uri.getPathSegments();
        if (segments.size() != 2 || !"manga".equals(segments.get(0))) return null;
        String manga = segments.get(1);
        if (!manga.matches(ID)) return null;
        return new Title(id(), manga, "Atsumaru title", WEB + "/manga/" + manga, "", "");
    }

    @Override public List<Title> search(String query) throws IOException {
        Title direct = knownTitle(query);
        if (direct != null) return Collections.singletonList(direct);
        if (query.startsWith("https://")) return Collections.emptyList();
        String url = API + "/search/manga?q=" + Uri.encode(query)
            + "&query_by=title&per_page=30";
        return titles(http.text(url, "application/json", this::apiHost));
    }

    private boolean apiHost(String host) { return host.equals("atsu.moe"); }

    List<Title> titles(String body) throws IOException {
        try {
            JSONArray hits = new JSONObject(body).getJSONArray("hits");
            Map<String, Title> found = new LinkedHashMap<>();
            for (int i = 0; i < hits.length(); i++) {
                JSONObject document = hits.getJSONObject(i).optJSONObject("document");
                if (document == null || document.optBoolean("isAdult", false)) continue;
                String medium = document.optString("medium");
                if (!medium.isEmpty() && !"Comic".equalsIgnoreCase(medium)) continue;
                String manga = document.optString("id");
                String title = document.optString("title").trim();
                if (!manga.matches(ID) || title.isEmpty()) continue;
                String cover = asset(document.optString("poster"), true);
                found.putIfAbsent(manga, new Title(id(), manga, title, WEB + "/manga/" + manga,
                    cover, document.optString("synopsis")));
            }
            return new ArrayList<>(found.values());
        } catch (JSONException e) {
            throw new Fault(R.string.source_error);
        }
    }

    @Override public List<Episode> episodes(Title title) throws IOException {
        return episodes(title, Collections.emptyList());
    }

    @Override public List<Episode> episodes(Title title, List<String> preferredIds) throws IOException {
        if (!title.id.matches(ID)) throw new Fault(R.string.bad_url);
        String url = API + "/manga/allChapters?mangaId=" + Uri.encode(title.id);
        return chapterList(http.text(url, "application/json", this::apiHost), title.id, preferredIds);
    }

    List<Episode> chapterList(String body, String mangaId) throws IOException {
        return chapterList(body, mangaId, Collections.emptyList());
    }

    List<Episode> chapterList(String body, String mangaId, List<String> preferredIds) throws IOException {
        try {
            JSONArray chapters = new JSONObject(body).getJSONArray("chapters");
            Map<String, Episode> result = new LinkedHashMap<>();
            for (int i = 0; i < chapters.length(); i++) {
                JSONObject chapter = chapters.getJSONObject(i);
                String chapterId = chapter.optString("id");
                if (!chapterId.matches(ID)) continue;
                String number = chapter.optString("number", "").trim();
                if (number.isEmpty() || "null".equals(number)) continue;
                try {
                    number = Names.normalizeNumber(number);
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                String name = chapter.optString("title", "").trim();
                if (name.isEmpty()) name = "Chapter " + number;
                String key = mangaId + "/" + chapterId;
                result.putIfAbsent(key, new Episode(key, number, name,
                    WEB + "/read/" + mangaId + "/" + chapterId, "", false));
            }
            List<Episode> ordered = new ArrayList<>(result.values());
            ordered.sort(Comparator.comparing((Episode episode) -> new BigDecimal(episode.number))
                .thenComparing(episode -> episode.id));
            // A title can expose several release IDs for the same normalized chapter number.
            // Keep one deterministic representative, but prefer an explicitly requested,
            // saved or queued ID so an upgrade does not abandon existing reading state.
            Map<String, Integer> rank = new LinkedHashMap<>();
            for (String id : preferredIds) rank.putIfAbsent(id, rank.size());
            Map<String, Episode> byNumber = new LinkedHashMap<>();
            for (Episode episode : ordered) {
                Episode previous = byNumber.get(episode.number);
                if (previous == null || rank.getOrDefault(episode.id, Integer.MAX_VALUE)
                        < rank.getOrDefault(previous.id, Integer.MAX_VALUE))
                    byNumber.put(episode.number, episode);
            }
            return new ArrayList<>(byNumber.values());
        } catch (JSONException e) {
            throw new Fault(R.string.source_error);
        }
    }

    @Override public List<String> pages(Episode episode) throws IOException {
        String[] ids = episode.id.split("/", -1);
        if (ids.length != 2 || !ids[0].matches(ID) || !ids[1].matches(ID)) {
            throw new Fault(R.string.bad_url);
        }
        String url = API + "/read/chapter?mangaId=" + Uri.encode(ids[0])
            + "&chapterId=" + Uri.encode(ids[1]);
        return pageLinks(http.text(url, "application/json", this::apiHost));
    }

    List<String> pageLinks(String body) throws IOException {
        try {
            JSONArray pages = new JSONObject(body).getJSONObject("readChapter").getJSONArray("pages");
            Map<Integer, String> indexed = new LinkedHashMap<>();
            for (int i = 0; i < pages.length(); i++) {
                JSONObject page = pages.getJSONObject(i);
                String image = asset(page.optString("image"), false);
                if (image.isEmpty()) continue;
                CatalogHttp.check(image, this::imageHost);
                indexed.putIfAbsent(page.optInt("number", i), image);
            }
            List<Integer> indices = new ArrayList<>(indexed.keySet());
            Collections.sort(indices);
            List<String> links = new ArrayList<>();
            for (Integer index : indices) links.add(indexed.get(index));
            if (links.isEmpty()) throw new Fault(R.string.access_required);
            return links;
        } catch (JSONException e) {
            throw new Fault(R.string.source_error);
        }
    }

    private static String asset(String value, boolean poster) {
        if (value == null || value.isEmpty()) return "";
        if (value.startsWith("https://")) return value;
        if (value.startsWith("http://") || value.startsWith("//")) return "";
        String path = value.startsWith("/") ? value.substring(1) : value;
        if (poster && !path.startsWith("static/")) path = "static/" + path;
        if (path.contains("..") || path.startsWith("/")) return "";
        return CDN + "/" + path;
    }
}
