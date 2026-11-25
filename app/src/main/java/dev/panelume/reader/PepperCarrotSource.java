package dev.panelume.reader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PepperCarrotSource implements CatalogSource {
    private static final String WEB = "https://www.peppercarrot.com";
    private static final String LIST = WEB + "/en/webcomics/peppercarrot.html";
    private static final Pattern EP = Pattern.compile("/en/webcomic/(ep([0-9]{2,3})_[A-Za-z0-9-]+)\\.html");
    private static final Pattern PAGE = Pattern.compile("en_Pepper-and-Carrot_by-David-Revoy_E([0-9]{2,3})P([0-9]{2,3})\\.(?:jpg|png)", Pattern.CASE_INSENSITIVE);
    private final CatalogHttp http;
    PepperCarrotSource(CatalogHttp http) { this.http = http; }
    @Override public String id() { return "peppercarrot"; }
    @Override public String name() { return "Pepper & Carrot"; }
    @Override public boolean accepts(String host) { return host.equals("peppercarrot.com") || host.equals("www.peppercarrot.com"); }
    @Override public boolean imageHost(String host) { return accepts(host); }
    @Override public Title knownTitle(String query) {
        String key = Names.key(query);
        if (key.equals("pepper carrot") || key.equals("pepper and carrot") || key.equals("peppercarrot") || query.equals(LIST))
            return new Title(id(), "peppercarrot", "Pepper & Carrot", LIST, "", "Fantasy webcomic by David Revoy (CC BY 4.0).");
        return null;
    }
    @Override public List<Title> search(String query) {
        Title title = knownTitle(query);
        return title == null ? Collections.emptyList() : Collections.singletonList(title);
    }
    @Override public List<Episode> episodes(Title title) throws IOException {
        if (!"peppercarrot".equals(title.id)) throw new Fault(R.string.bad_url);
        WebtoonMarkup markup = new WebtoonMarkup(http.text(LIST, "text/html", this::accepts));
        Map<Integer, Episode> found = new TreeMap<>();
        for (WebtoonMarkup.Node node : markup.nodes) {
            if (!"a".equals(node.tag)) continue;
            String url = CatalogHttp.resolve(LIST, node.attr("href"));
            Matcher m = EP.matcher(url);
            if (!m.find()) continue;
            CatalogHttp.check(url, this::accepts);
            int number = Integer.parseInt(m.group(2));
            String label = markup.text(node);
            if (label.isEmpty()) label = m.group(1).substring(m.group(1).indexOf('_') + 1).replace('-', ' ');
            found.put(number, new Episode(m.group(1), Integer.toString(number), label,
                WEB + "/en/webcomic/" + m.group(1) + ".html", "David Revoy · CC BY 4.0", false));
        }
        if (found.isEmpty()) throw new Fault(R.string.source_error);
        return new ArrayList<>(found.values());
    }
    @Override public List<String> pages(Episode episode) throws IOException {
        if (!episode.id.matches("ep[0-9]{2,3}_[A-Za-z0-9-]+")) throw new Fault(R.string.bad_url);
        String url = WEB + "/en/webcomic/" + episode.id + ".html";
        WebtoonMarkup markup = new WebtoonMarkup(http.text(url, "text/html", this::accepts));
        Map<Integer, String> found = new TreeMap<>();
        for (WebtoonMarkup.Node node : markup.nodes) {
            if (!"img".equals(node.tag) && !"a".equals(node.tag)) continue;
            for (String attr : new String[]{"src", "data-src", "href"}) {
                String image = CatalogHttp.resolve(url, node.attr(attr));
                Matcher m = PAGE.matcher(image);
                if (!m.find() || Integer.parseInt(m.group(1)) != Integer.parseInt(episode.number)) continue;
                CatalogHttp.check(image, this::imageHost);
                int number = Integer.parseInt(m.group(2));
                String previous = found.get(number);
                if (previous == null || image.contains("/hi-res/")) found.put(number, image);
            }
        }
        if (found.isEmpty()) throw new Fault(R.string.access_required);
        return new ArrayList<>(found.values());
    }
}
