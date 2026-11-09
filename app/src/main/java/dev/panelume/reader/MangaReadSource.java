package dev.panelume.reader;

import android.net.Uri;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MangaReadSource implements CatalogSource {
    private static final String WEB = "https://www.mangaread.org";
    private static final String VIRAL_HIT = "how-to-fight-manhwa";
    private static final String COVER = WEB + "/wp-content/uploads/2020/07/Manga-Read-19-193x278.jpg";
    private static final String SLUG = "[a-z0-9][a-z0-9.-]{0,199}";
    private static final Pattern SERIES_PATH = Pattern.compile("/manga/(" + SLUG + ")/?");
    private static final Pattern CHAPTER_PATH = Pattern.compile("/manga/(" + SLUG + ")/(" + SLUG + ")/?");
    private final CatalogHttp http;

    MangaReadSource(CatalogHttp http) { this.http = http; }
    @Override public String id() { return "mangaread"; }
    @Override public String name() { return "MangaRead"; }
    @Override public boolean accepts(String host) { return host.equals("mangaread.org") || host.equals("www.mangaread.org"); }
    @Override public boolean imageHost(String host) { return accepts(host); }
    @Override public String imageReferer(Episode episode) { return episode.url; }

    @Override public Title knownTitle(String query) {
        return Names.isViralHit(query) ? title(VIRAL_HIT, "How to Fight", COVER) : null;
    }

    @Override
    public List<Title> search(String query) throws IOException {
        Title known = knownTitle(query);
        if (known != null) return Collections.singletonList(known);
        if (query.startsWith("https://")) return Collections.emptyList();
        String url = WEB + "/?s=" + Uri.encode(query) + "&post_type=wp-manga";
        return searchResults(http.text(url, "text/html", this::accepts));
    }

    List<Title> searchResults(String html) throws IOException {
        WebtoonMarkup markup = new WebtoonMarkup(html);
        if (markup.firstClass("no-results") != null) return Collections.emptyList();
        if (markup.firstClass("search-wrap") == null) throw new Fault(R.string.source_error);
        Map<String, String> covers = new LinkedHashMap<>();
        Map<String, Title> results = new LinkedHashMap<>();
        for (WebtoonMarkup.Node node : markup.nodes) {
            WebtoonMarkup.Node parent = node.parent;
            if (parent == null || parent.parent == null) continue;
            if ("img".equals(node.tag) && "a".equals(parent.tag) && parent.parent.hasClass("tab-thumb")) {
                Matcher series = SERIES_PATH.matcher(path(parent.attr("href")));
                if (series.matches()) covers.putIfAbsent(series.group(1), image(node));
            }
            if (!"a".equals(node.tag) || !parent.parent.hasClass("post-title")) continue;
            Matcher series = SERIES_PATH.matcher(path(node.attr("href")));
            String name = markup.text(node);
            if (!series.matches() || name.isEmpty()) continue;
            String slug = series.group(1);
            results.putIfAbsent(slug, title(slug, name, covers.getOrDefault(slug, "")));
        }
        return new ArrayList<>(results.values());
    }

    @Override
    public List<Episode> episodes(Title title) throws IOException {
        if (!title.id.matches(SLUG)) throw new Fault(R.string.bad_url);
        String url = WEB + "/manga/" + title.id + "/";
        return chapterList(http.text(url, "text/html", this::accepts), title.id);
    }

    List<Episode> chapterList(String html, String series) throws IOException {
        WebtoonMarkup markup = new WebtoonMarkup(html);
        if (markup.firstClass("listing-chapters_wrap") == null) throw new Fault(R.string.source_error);
        Map<String, Episode> result = new LinkedHashMap<>();
        for (WebtoonMarkup.Node node : markup.nodes) {
            if (!"a".equals(node.tag) || node.parent == null || !node.parent.hasClass("wp-manga-chapter")) continue;
            Matcher chapter = CHAPTER_PATH.matcher(path(node.attr("href")));
            if (!chapter.matches() || !series.equals(chapter.group(1))) continue;
            String label = markup.text(node);
            String slug = chapter.group(2);
            result.putIfAbsent(slug, new Episode(slug, Names.guess(label).number, label,
                WEB + "/manga/" + series + "/" + slug + "/", "", false));
        }
        List<Episode> ordered = new ArrayList<>(result.values());
        ordered.sort(Comparator.comparing(episode -> episode.number.isEmpty() ? null : new BigDecimal(episode.number),
            Comparator.nullsLast(Comparator.naturalOrder())));
        return ordered;
    }

    @Override
    public List<String> pages(Episode episode) throws IOException {
        Matcher chapter = CHAPTER_PATH.matcher(path(episode.url));
        if (!chapter.matches() || !episode.id.equals(chapter.group(2))) throw new Fault(R.string.bad_url);
        String url = WEB + "/manga/" + chapter.group(1) + "/" + episode.id + "/?style=list";
        return pageLinks(http.text(url, "text/html", this::accepts));
    }

    List<String> pageLinks(String html) throws IOException {
        WebtoonMarkup markup = new WebtoonMarkup(html);
        WebtoonMarkup.Node content = markup.firstClass("reading-content");
        List<String> pages = new ArrayList<>();
        if (content != null) {
            for (WebtoonMarkup.Node node : markup.nodes) {
                if (!"img".equals(node.tag) || !node.hasClass("wp-manga-chapter-img") || !node.within(content)) continue;
                String address = image(node);
                CatalogHttp.check(address, this::imageHost);
                pages.add(address);
            }
        }
        if (pages.isEmpty()) throw new Fault(R.string.access_required);
        return pages;
    }

    private Title title(String slug, String name, String cover) {
        return new Title(id(), slug, name, WEB + "/manga/" + slug + "/", cover, "");
    }

    private String path(String href) {
        Uri url = Uri.parse(CatalogHttp.resolve(WEB, href));
        String host = url.getHost();
        return "https".equals(url.getScheme()) && host != null && accepts(host) && url.getEncodedPath() != null
            ? url.getEncodedPath() : "";
    }

    private String image(WebtoonMarkup.Node node) {
        String address = node.attr("data-src");
        if (address.isEmpty()) address = node.attr("src");
        return CatalogHttp.resolve(WEB, address);
    }
}
