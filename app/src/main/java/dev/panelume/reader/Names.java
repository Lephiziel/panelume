package dev.panelume.reader;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Names {
    private static final Pattern EXTENSION = Pattern.compile("(?i)\\.(?:pdf|cbz|zip)$");
    private static final Pattern NUMBER = Pattern.compile(
        "(?iu)(?:^|[^\\p{L}\\p{N}])(?:chapter|chap|ch|episode|ep|глава|эпизод|выпуск)\\s*(?:(?:no\\.?|[.#№-])\\s*)?(\\d{1,7}(?:[.,]\\d{1,4})?)(?![\\p{L}\\p{N}])"
    );
    private static final Pattern TRAILING_NUMBER = Pattern.compile("(?:^|[ _–—-])(\\d{1,7}(?:[.,]\\d{1,4})?)\\s*$");
    private static final Pattern NOISE = Pattern.compile(
        "(?iu)\\b(?:read (?:online|for free)|читать (?:онлайн|бесплатно)|manhwa|webtoon|manga|манхва|манга|вебтун|full chapter|pdf)\\b"
    );
    private static final Pattern SPACE = Pattern.compile("\\s+");

    static Guess guess(String filename) {
        String name = Normalizer.normalize(EXTENSION.matcher(filename).replaceFirst(""), Normalizer.Form.NFKC);
        Matcher tagged = NUMBER.matcher(name);
        String number = "";
        String title = name;
        if (tagged.find()) {
            number = normalizeNumber(tagged.group(1));
            title = name.substring(0, tagged.start());
        } else {
            Matcher trailing = TRAILING_NUMBER.matcher(name);
            if (trailing.find()) {
                number = normalizeNumber(trailing.group(1));
                title = name.substring(0, trailing.start());
            }
        }
        title = SPACE.matcher(title.replace('_', ' ').replaceAll("[\\[\\](){}|—–]+", " ")).replaceAll(" ").trim();
        title = NOISE.matcher(title).replaceAll(" ").replaceAll("^[\\s.\\-]+|[\\s.\\-]+$", "");
        title = SPACE.matcher(title).replaceAll(" ").trim();
        return new Guess(title, number);
    }

    static String key(String title) {
        return Normalizer.normalize(title, Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}\\p{M}]+", " ").trim();
    }

    static boolean isViralHit(String title) {
        String key = key(title);
        return key.equals("viral hit") || key.equals("how to fight");
    }

    static String normalizeNumber(String value) {
        String input = value.trim().replace(',', '.');
        if (input.isEmpty()) return "";
        if (!input.matches("\\d{1,7}(?:\\.\\d{1,4})?")) {
            throw new IllegalArgumentException("Invalid chapter number");
        }
        return new BigDecimal(input).stripTrailingZeros().toPlainString();
    }

    static int naturalCompare(String left, String right) {
        int li = 0;
        int ri = 0;
        while (li < left.length() && ri < right.length()) {
            char l = left.charAt(li);
            char r = right.charAt(ri);
            if (asciiDigit(l) && asciiDigit(r)) {
                int le = endOfDigits(left, li);
                int re = endOfDigits(right, ri);
                int ls = li;
                int rs = ri;
                while (ls + 1 < le && left.charAt(ls) == '0') ls++;
                while (rs + 1 < re && right.charAt(rs) == '0') rs++;
                int byLength = Integer.compare(le - ls, re - rs);
                if (byLength != 0) return byLength;
                while (ls < le) {
                    int comparison = Character.compare(left.charAt(ls++), right.charAt(rs++));
                    if (comparison != 0) return comparison;
                }
                li = le;
                ri = re;
                continue;
            }
            int comparison = Character.compare(Character.toLowerCase(l), Character.toLowerCase(r));
            if (comparison != 0) return comparison;
            li++;
            ri++;
        }
        int byRest = Integer.compare(left.length() - li, right.length() - ri);
        return byRest == 0 ? left.compareTo(right) : byRest;
    }

    private static boolean asciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static int endOfDigits(String text, int offset) {
        while (offset < text.length() && asciiDigit(text.charAt(offset))) offset++;
        return offset;
    }

    static final class Guess {
        final String title;
        final String number;

        Guess(String title, String number) {
            this.title = title;
            this.number = number;
        }
    }
}
