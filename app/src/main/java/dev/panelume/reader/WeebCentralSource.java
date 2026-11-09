package dev.panelume.reader;

import android.net.Uri;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class WeebCentralSource implements CatalogSource {
    private static final String WEB = "https://weebcentral.com";
    private static final String VIRAL_HIT = "01J76XYE8HKQ5KFPY5MRR9CNCR";
    private static final String ULID = "[0-9A-HJKMNP-TV-Z]{26}";
    private static final Pattern SERIES_PATH = Pattern.compile("/series/(" + ULID + ")(?:/[^/]+)?/?");
    private static final Pattern CHAPTER_PATH = Pattern.compile("/chapters/(" + ULID + ")/?");
    private final CatalogHttp http;

    WeebCentralSource(CatalogHttp http) { this.http = http; }
    @Override public String id() { return "weebcentral"; }
    @Override public String name() { return "Weeb Central"; }
    @Override public boolean accepts(String host) { return host.equals("weebcentral.com") || host.equals("www.weebcentral.com"); }

    @Override
    public boolean imageHost(String host) {
        return accepts(host) || host.endsWith(".compsci88.com") || host.endsWith(".lowee.us");
    }

    @Override public String imageReferer(Episode episode) { return episode.url; }

    @Override
    public Title knownTitle(String query) {
        if (!Names.isViralHit(query)) return null;
        return new Title(id(), VIRAL_HIT, "Viral Hit", WEB + "/series/" + VIRAL_HIT + "/Viral-Hit",
            "https://temp.compsci88.com/cover/fallback/" + VIRAL_HIT + ".jpg", "");
    }

    @Override
    public List<Title> search(String query) throws IOException {
        Title known = knownTitle(query);
        if (known != null) return Collections.singletonList(known);
        if (query.startsWith("https://")) return Collections.emptyList();
        String url = WEB + "/search/data?text=" + Uri.encode(query) + "&sort=Best%20Match&order=Descending"
            + "&official=Any&anime=Any&adult=No&limit=32&offset=0&display_mode=Full%20Display";
        return searchResults(http.text(url, "text/html", this::accepts));
    }

    List<Title> searchResults(String html) throws IOException {
        WebtoonMarkup markup = new WebtoonMarkup(html);
        Map<String, Title> results = new LinkedHashMap<>();
        for (int index = 0; index < markup.nodes.size(); index++) {
            WebtoonMarkup.Node link = markup.nodes.get(index);
            WebtoonMarkup.Node section = link.parent;
            if (!"a".equals(link.tag) || section == null || !"section".equals(section.tag)
                || section.parent == null || !"article".equals(section.parent.tag)) continue;
            String path = path(link.attr("href"));
            Matcher series = SERIES_PATH.matcher(path);
            if (!series.matches()) continue;
            String name = "";
            String source = "";
            String image = "";
            for (int i = index + 1; i < markup.nodes.size(); i++) {
                WebtoonMarkup.Node node = markup.nodes.get(i);
                if (!node.within(link)) break;
                if (node.parent == link && "div".equals(node.tag) && node.attr("class").isEmpty()) name = markup.text(node);
                if ("source".equals(node.tag) && source.isEmpty()) source = node.attr("srcset").split("\\s+", 2)[0];
                if ("img".equals(node.tag) && image.isEmpty()) image = node.attr("src");
            }
            if (name.isEmpty()) continue;
            String slug = series.group(1);
            results.putIfAbsent(slug, new Title(id(), slug, name, WEB + path,
                CatalogHttp.resolve(WEB, source.isEmpty() ? image : source), ""));
        }
        if (results.isEmpty() && (html.contains("<title>Site Unavailable</title>") || html.contains("cf-chl-"))) {
            throw new Fault(R.string.source_error);
        }
        return new ArrayList<>(results.values());
    }

    @Override
    public List<Episode> episodes(Title title) throws IOException {
        if (!title.id.matches(ULID)) throw new Fault(R.string.bad_url);
        String url = WEB + "/series/" + title.id + "/full-chapter-list";
        return chapterList(http.text(url, "text/html", this::accepts));
    }

    List<Episode> chapterList(String html) throws IOException {
        WebtoonMarkup markup = new WebtoonMarkup(html);
        Map<String, Episode> result = new LinkedHashMap<>();
        for (WebtoonMarkup.Node node : markup.nodes) {
            WebtoonMarkup.Node parent = node.parent;
            if (!"span".equals(node.tag) || parent == null || !"span".equals(parent.tag) || !parent.hasClass("flex")) continue;
            WebtoonMarkup.Node anchor = parent.parent;
            while (anchor != null && !"a".equals(anchor.tag)) anchor = anchor.parent;
            if (anchor == null) continue;
            Matcher chapter = CHAPTER_PATH.matcher(path(anchor.attr("href")));
            String label = markup.text(node);
            if (!chapter.matches() || label.isEmpty()) continue;
            String id = chapter.group(1);
            result.putIfAbsent(id, new Episode(id, Names.guess(label).number, label, WEB + "/chapters/" + id, "", false));
        }
        if (result.isEmpty()) throw new Fault(R.string.source_error);
        // The source's order also preserves seasons that restart their chapter numbering.
        List<Episode> ordered = new ArrayList<>(result.values());
        Collections.reverse(ordered);
        return ordered;
    }

    @Override
    public List<String> pages(Episode episode) throws IOException {
        if (!episode.id.matches(ULID)) throw new Fault(R.string.bad_url);
        String url = WEB + "/chapters/" + episode.id + "/images?is_prev=False&reading_style=long_strip";
        return pageLinks(http.text(url, "text/html", this::accepts));
    }

    List<String> pageLinks(String html) throws IOException {
        List<String> pages = new ArrayList<>();
        for (WebtoonMarkup.Node node : new WebtoonMarkup(html).nodes) {
            WebtoonMarkup.Node parent = node.parent;
            if (!"img".equals(node.tag) || parent == null || !"section".equals(parent.tag)
                || !parent.attr("x-data").contains("scroll")) continue;
            String address = CatalogHttp.resolve(WEB, node.attr("src"));
            CatalogHttp.check(address, this::imageHost);
            pages.add(address);
        }
        if (pages.isEmpty()) throw new Fault(R.string.access_required);
        return pages;
    }

    private String path(String href) {
        Uri url = Uri.parse(CatalogHttp.resolve(WEB, href));
        String host = url.getHost();
        return "https".equals(url.getScheme()) && host != null && accepts(host) && url.getEncodedPath() != null
            ? url.getEncodedPath() : "";
    }
}
