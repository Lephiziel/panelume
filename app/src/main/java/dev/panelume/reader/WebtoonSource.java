package dev.panelume.reader;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/*
 * Android port of chapter discovery and viewer extraction from
 * Zehina/Webtoon-Downloader (MIT), core/webtoon/{api,fetchers,extractor}.py.
 * Copyright (c) 2023, Ali Taibi. See assets/third-party/webtoon-downloader-MIT.txt.
 */
final class WebtoonSource implements CatalogSource {
    private static final String WEB = "https://www.webtoons.com";
    private static final String MOBILE = "https://m.webtoons.com";
    private static final String VIRAL_HIT_ID = "webtoon/2268";
    private static final String VIRAL_HIT_URL = WEB + "/en/action/viral-hit/list?title_no=2268";
    private static final String VIRAL_HIT_COVER =
        "https://swebtoon-phinf.pstatic.net/20250204_162/1738646049154CFTNt_JPEG/2268.jpg?type=crop540_540";
    private final CatalogHttp http;

    WebtoonSource(CatalogHttp http) { this.http = http; }
    @Override public String id() { return "webtoon"; }
    @Override public String name() { return "WEBTOON"; }
    @Override public boolean accepts(String host) { return site(host) || imageHost(host); }

    @Override
    public boolean imageHost(String host) {
        return host.equals("webtoon-phinf.pstatic.net") || host.equals("swebtoon-phinf.pstatic.net")
            || host.equals("webtoons-static.pstatic.net") || host.equals("static.webtoon.com");
    }

    private boolean site(String host) {
        return host.equals("webtoons.com") || host.endsWith(".webtoons.com");
    }

    @Override
    public Title knownTitle(String query) {
        Title link = query.startsWith("https://") ? titleLink(query, "", "") : null;
        if (Names.isViralHit(query) || (link != null && link.id.equals(VIRAL_HIT_ID))) {
            return new Title(id(), VIRAL_HIT_ID, "Viral Hit", VIRAL_HIT_URL, VIRAL_HIT_COVER, "");
        }
        return null;
    }

    @Override
    public List<Title> search(String query) throws IOException {
        Title known = knownTitle(query);
        if (known != null) return Collections.singletonList(known);
        Title fromLink = titleLink(query, "", "");
        if (fromLink != null) {
            WebtoonMarkup markup = new WebtoonMarkup(http.text(fromLink.url, "text/html", this::site));
            String title = markup.text(markup.firstClass("subj"));
            if (title.isEmpty()) title = markup.text(markup.firstClass("subject"));
            WebtoonMarkup.Node image = markup.firstMeta("og:image");
            String cover = image == null ? "" : absolute(image.attr("content"));
            if (title.isEmpty()) throw new Fault(R.string.source_error);
            return Collections.singletonList(new Title(id(), fromLink.id, title, fromLink.url, cover,
                markup.text(markup.firstClass("summary"))));
        }
        if (query.startsWith("https://")) return Collections.emptyList();
        String html = http.text(WEB + "/en/search?keyword=" + Uri.encode(query), "text/html", this::site);
        return searchResults(html, query);
    }

    List<Title> searchResults(String html, String query) {
        WebtoonMarkup markup = new WebtoonMarkup(html);
        boolean hasSearchList = markup.firstClass("webtoon_list") != null;
        Map<WebtoonMarkup.Node, WebtoonMarkup.Node> headings = new LinkedHashMap<>();
        Map<WebtoonMarkup.Node, WebtoonMarkup.Node> covers = new LinkedHashMap<>();
        List<WebtoonMarkup.Node> links = new ArrayList<>();
        for (WebtoonMarkup.Node node : markup.nodes) {
            if ("a".equals(node.tag)) {
                if ((!hasSearchList || insideSearchList(node)) && titleLink(node.attr("href"), "", "") != null) {
                    links.add(node);
                }
                continue;
            }
            if (!"img".equals(node.tag) && !node.hasClass("title") && !node.hasClass("subj") && !node.hasClass("subject")) continue;
            WebtoonMarkup.Node anchor = node.parent;
            while (anchor != null && !"a".equals(anchor.tag)) anchor = anchor.parent;
            if (anchor == null) continue;
            if ("img".equals(node.tag)) covers.putIfAbsent(anchor, node);
            else headings.putIfAbsent(anchor, node);
        }
        Map<String, Title> result = new LinkedHashMap<>();
        for (WebtoonMarkup.Node link : links) {
            WebtoonMarkup.Node image = covers.get(link);
            String title = markup.text(headings.get(link));
            if (title.isEmpty() && image != null) title = image.attr("alt");
            if (title.isEmpty()) title = link.attr("title");
            if (title.isEmpty() || title.length() > 180) continue;
            String cover = image == null ? "" : image.attr("data-url");
            if (cover.isEmpty() && image != null) cover = image.attr("src");
            Title match = titleLink(link.attr("href"), title, absolute(cover));
            if (match != null) result.putIfAbsent(match.id, match);
            if (result.size() == 60) break;
        }
        List<Title> titles = new ArrayList<>(result.values());
        String key = Names.key(query);
        titles.sort(Comparator.comparingInt(value -> Names.key(value.name).startsWith(key) ? 0 : 1));
        return titles;
    }

    private boolean insideSearchList(WebtoonMarkup.Node node) {
        for (WebtoonMarkup.Node parent = node.parent; parent != null; parent = parent.parent) {
            if (parent.hasClass("webtoon_list")) return true;
        }
        return false;
    }

    private Title titleLink(String value, String name, String cover) {
        Uri uri = Uri.parse(absolute(value));
        String host = uri.getHost();
        String path = uri.getEncodedPath();
        if (!"https".equals(uri.getScheme()) || host == null || !site(host) || path == null
            || !path.startsWith("/en/") || !path.endsWith("/list")) return null;
        String title = uri.getQueryParameter("title_no");
        if (title == null || !title.matches("[0-9]{1,12}")) return null;
        String type = path.startsWith("/en/canvas/") || path.startsWith("/en/challenge/") ? "canvas" : "webtoon";
        return new Title(id(), type + "/" + title, name, WEB + path + "?title_no=" + title, cover, "");
    }

    @Override
    public List<Episode> episodes(Title title) throws IOException {
        if (!title.id.matches("(canvas|webtoon)/[0-9]{1,12}")) throw new Fault(R.string.bad_url);
        String endpoint = MOBILE + "/api/v1/" + title.id + "/episodes?pageSize=99999&readingLanguageCode=en";
        try {
            List<Episode> fromApi = parseEpisodes(http.text(endpoint, "application/json", this::site), title.url);
            if (!fromApi.isEmpty()) return fromApi;
        } catch (IOException e) {
            if (Thread.currentThread().isInterrupted()
                || (e instanceof Fault && ((Fault) e).message == R.string.source_limit)) throw e;
        }
        return publicEpisodes(title);
    }

    private List<Episode> publicEpisodes(Title title) throws IOException {
        Title series = titleLink(title.url, "", "");
        if (series == null || !series.id.equals(title.id)) throw new Fault(R.string.bad_url);
        String titleNumber = Uri.parse(series.url).getQueryParameter("title_no");
        Map<String, Episode> found = new LinkedHashMap<>();
        Queue<String> pages = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        pages.add(series.url);
        seen.add("1");
        while (!pages.isEmpty()) {
            WebtoonMarkup markup = new WebtoonMarkup(http.text(pages.remove(), "text/html", this::site));
            WebtoonMarkup.Node list = markup.firstId("_listUl");
            if (list == null) throw new Fault(R.string.source_error);
            for (WebtoonMarkup.Node node : markup.nodes) {
                if (node.hasClass("subj") && node.within(list) && node.parent != null
                    && "a".equals(node.parent.tag)) {
                    String address = absolute(node.parent.attr("href"));
                    Uri url = Uri.parse(address);
                    if (isViewer(url) && titleNumber.equals(url.getQueryParameter("title_no"))) {
                        String number = url.getQueryParameter("episode_no");
                        if (number != null && number.matches("[1-9][0-9]{0,8}")) {
                            found.putIfAbsent(number, new Episode(number, number, markup.text(node), address, "", false));
                        }
                    }
                }
                if (!"a".equals(node.tag) || !(node.hasClass("pg_page") || node.hasClass("pg_next"))) continue;
                String href = node.attr("href");
                if (href.startsWith("?")) href = Uri.parse(series.url).buildUpon().clearQuery().build() + href;
                Title linkedSeries = titleLink(href, "", "");
                if (linkedSeries == null || !linkedSeries.id.equals(title.id)) continue;
                String page = Uri.parse(absolute(href)).getQueryParameter("page");
                if (page != null && page.matches("[1-9][0-9]{0,5}") && seen.add(page)) {
                    if (seen.size() > 200) throw new Fault(R.string.source_error);
                    pages.add(series.url + "&page=" + page);
                }
            }
        }
        return sortedEpisodes(found);
    }

    List<Episode> parseEpisodes(String body, String seriesUrl) throws IOException {
        try {
            JSONArray items = new JSONObject(body).getJSONObject("result").getJSONArray("episodeList");
            Map<String, Episode> episodes = new LinkedHashMap<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                int number = item.optInt("episodeNo");
                String url = absolute(item.optString("viewerLink", ""));
                Uri uri = Uri.parse(url);
                if (number <= 0) continue;
                boolean viewable = isViewer(uri);
                boolean external = !viewable || item.optBoolean("locked", false) || item.optBoolean("isLocked", false);
                String key = Integer.toString(number);
                episodes.putIfAbsent(key, new Episode(key, key, item.optString("episodeTitle").trim(),
                    viewable ? url : seriesUrl, "", external));
            }
            return sortedEpisodes(episodes);
        } catch (JSONException e) {
            throw new Fault(R.string.source_error);
        }
    }

    private boolean isViewer(Uri uri) {
        String host = uri.getHost();
        String path = uri.getPath();
        return "https".equals(uri.getScheme()) && host != null && site(host)
            && path != null && path.startsWith("/en/") && path.endsWith("/viewer");
    }

    private static List<Episode> sortedEpisodes(Map<String, Episode> episodes) {
        List<Episode> result = new ArrayList<>(episodes.values());
        result.sort(Comparator.comparingInt(value -> Integer.parseInt(value.id)));
        return result;
    }

    @Override public String imageReferer(Episode episode) { return episode.url; }

    @Override
    public List<String> pages(Episode episode) throws IOException {
        if (episode.external) throw new Fault(R.string.access_required);
        return parsePages(http.text(episode.url, "text/html", this::site));
    }

    List<String> parsePages(String html) throws IOException {
        WebtoonMarkup markup = new WebtoonMarkup(html);
        WebtoonMarkup.Node viewer = null;
        for (WebtoonMarkup.Node node : markup.nodes) {
            if ("div".equals(node.tag) && node.hasClass("viewer_img") && node.hasClass("_img_viewer_area")) {
                viewer = node;
                break;
            }
        }
        if (viewer == null) throw new Fault(R.string.access_required);
        List<String> pages = new ArrayList<>();
        for (WebtoonMarkup.Node node : markup.nodes) {
            if (!"img".equals(node.tag) || !node.within(viewer)) continue;
            String address = absolute(node.attr("data-url").replace("?type=q90", ""));
            if (address.isEmpty()) continue;
            CatalogHttp.check(address, this::imageHost);
            pages.add(address);
        }
        if (pages.isEmpty()) throw new Fault(R.string.access_required);
        return pages;
    }

    private static String absolute(String url) {
        return CatalogHttp.resolve(WEB, url);
    }
}
