package dev.panelume.reader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

interface CatalogSource {
    String id();
    String name();
    default Title knownTitle(String query) { return null; }
    List<Title> search(String query) throws IOException;
    List<Episode> episodes(Title title) throws IOException;
    List<String> pages(Episode episode) throws IOException;
    boolean accepts(String host);
    boolean imageHost(String host);
    default String imageReferer(Episode episode) { return ""; }

    List<CatalogSource> SOURCES = Collections.unmodifiableList(Arrays.asList(
        new WebtoonSource(new CatalogHttp()), new WeebCentralSource(new CatalogHttp()),
        new MangaReadSource(new CatalogHttp()), new MangaDexSource(new CatalogHttp())
    ));

    static String names() {
        List<String> names = new ArrayList<>();
        for (CatalogSource source : SOURCES) names.add(source.name());
        return String.join(" · ", names);
    }

    static CatalogSource named(String id) {
        for (CatalogSource source : SOURCES) if (source.id().equals(id)) return source;
        return null;
    }

    static String label(String id) {
        CatalogSource source = named(id);
        return source == null ? id : source.name();
    }

    final class Title {
        final String provider;
        final String id;
        final String name;
        final String url;
        final String cover;
        final String description;

        Title(String provider, String id, String name, String url, String cover, String description) {
            this.provider = provider;
            this.id = id;
            this.name = name;
            this.url = url;
            this.cover = cover;
            this.description = description;
        }

        String key() { return provider + ":" + id; }
    }

    final class Episode {
        final String id;
        final String number;
        final String name;
        final String url;
        final String credit;
        final boolean external;

        Episode(String id, String number, String name, String url, String credit, boolean external) {
            this.id = id;
            this.number = number;
            this.name = name;
            this.url = url;
            this.credit = credit;
            this.external = external;
        }
    }
}
