package dev.panelume.reader;

import android.text.Html;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class WebtoonMarkup {
    private static final Pattern TAG = Pattern.compile(
        "(?is)<!--.*?-->|<![^>]*>|<(script|style)\\b[^>]*>.*?</\\1\\s*>|"
            + "<(/?)([a-z][\\w:-]*)(?=\\s|/?>)((?:[^\"'<>]++|\"[^\"]*\"|'[^']*')*)>");
    private static final Pattern ATTRIBUTE = Pattern.compile(
        "([^\\s=/]+)(?:\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+)))?");
    private static final Set<String> VOID = new HashSet<>(Arrays.asList(
        "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr"));

    final List<Node> nodes = new ArrayList<>();
    private final String html;

    WebtoonMarkup(String html) {
        this.html = html;
        Matcher tags = TAG.matcher(html);
        List<Node> open = new ArrayList<>();
        while (tags.find()) {
            if (tags.group(3) == null) continue;
            String tag = tags.group(3).toLowerCase(Locale.ROOT);
            if ("/".equals(tags.group(2))) {
                for (int index = open.size() - 1; index >= 0; index--) {
                    if (open.get(index).tag.equals(tag)) {
                        for (int j = open.size() - 1; j >= index; j--) open.remove(j).end = tags.start();
                        break;
                    }
                }
                continue;
            }
            Node parent = open.isEmpty() ? null : open.get(open.size() - 1);
            Node node = new Node(tag, tags.end(), parent);
            Matcher attributes = ATTRIBUTE.matcher(tags.group(4));
            while (attributes.find()) {
                String value = attributes.group(2);
                if (value == null) value = attributes.group(3);
                if (value == null) value = attributes.group(4);
                node.attributes.put(attributes.group(1).toLowerCase(Locale.ROOT), value == null ? "" : value);
            }
            nodes.add(node);
            if (!VOID.contains(tag) && !tags.group(4).endsWith("/")) open.add(node);
        }
    }

    String text(Node node) {
        if (node == null || node.end <= node.start) return "";
        return entities(html.substring(node.start, node.end)).replaceAll("\\s+", " ").trim();
    }

    static String entities(String text) {
        return Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString();
    }

    Node firstClass(String name) {
        for (Node node : nodes) if (node.hasClass(name)) return node;
        return null;
    }

    Node firstId(String id) {
        for (Node node : nodes) if (id.equals(node.attr("id"))) return node;
        return null;
    }

    Node firstMeta(String property) {
        for (Node node : nodes) {
            if ("meta".equals(node.tag) && property.equals(node.attr("property"))) return node;
        }
        return null;
    }

    static final class Node {
        final String tag;
        final int start;
        final Node parent;
        final Map<String, String> attributes = new HashMap<>();
        int end;

        Node(String tag, int start, Node parent) {
            this.tag = tag;
            this.start = start;
            this.parent = parent;
        }

        String attr(String name) {
            String value = attributes.get(name);
            if (value == null) return "";
            return value.indexOf('&') < 0 ? value.trim() : entities(value).trim();
        }

        boolean hasClass(String value) {
            for (String name : attr("class").split("\\s+")) if (value.equals(name)) return true;
            return false;
        }

        boolean within(Node ancestor) {
            for (Node node = parent; node != null; node = node.parent) if (node == ancestor) return true;
            return false;
        }
    }
}
