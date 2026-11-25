package dev.panelume.reader;

import org.json.JSONObject;
import org.json.JSONException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class XkcdSource implements CatalogSource {
    private static final String WEB = "https://xkcd.com";
    private final CatalogHttp http;
    XkcdSource(CatalogHttp http) { this.http = http; }
    @Override public String id() { return "xkcd"; }
    @Override public String name() { return "xkcd"; }
    @Override public boolean accepts(String host) { return host.equals("xkcd.com") || host.equals("www.xkcd.com") || imageHost(host); }
    @Override public boolean imageHost(String host) { return host.equals("imgs.xkcd.com"); }
    @Override public Title knownTitle(String query) {
        if (!Names.key(query).equals("xkcd") && !query.equals(WEB) && !query.equals(WEB + "/")) return null;
        return new Title(id(), "xkcd", "xkcd", WEB + "/", "",
            "Free webcomic by Randall Munroe (CC BY-NC 2.5).");
    }
    @Override public List<Title> search(String query) {
        Title title = knownTitle(query);
        return title == null ? Collections.emptyList() : Collections.singletonList(title);
    }
    private JSONObject comic(String path) throws IOException {
        try {
            return new JSONObject(http.text(WEB + path, "application/json", this::accepts));
        } catch (JSONException e) { throw new Fault(R.string.source_error); }
    }
    @Override public List<Episode> episodes(Title title) throws IOException {
        if (!"xkcd".equals(title.id)) throw new Fault(R.string.bad_url);
        int latest = comic("/info.0.json").optInt("num", 0);
        if (latest < 1 || latest > 20000) throw new Fault(R.string.source_error);
        List<Episode> result = new ArrayList<>();
        for (int i = 1; i <= latest; i++) {
            if (i == 404) continue;
            String n = Integer.toString(i);
            result.add(new Episode(n, n, "Comic " + n, WEB + "/" + n + "/", "Randall Munroe · CC BY-NC 2.5", false));
        }
        return result;
    }
    @Override public List<String> pages(Episode episode) throws IOException {
        if (!episode.id.matches("[1-9][0-9]{0,4}") || "404".equals(episode.id)) throw new Fault(R.string.bad_url);
        JSONObject data = comic("/" + episode.id + "/info.0.json");
        String image = data.optString("img");
        if (image.isEmpty() || data.optInt("num") != Integer.parseInt(episode.id)) throw new Fault(R.string.source_error);
        CatalogHttp.check(image, this::imageHost);
        return Collections.singletonList(image);
    }
}
