package dev.panelume.reader;
import java.util.Arrays;
public final class SearchAliasesTest {
    public static void main(String[] args) {
        check(Names.isMyLifeAsALoser("Loser's Life"), "apostrophe alias");
        check(Arrays.asList(Names.searchAliases("Viral Hit")).contains("How to Fight"), "Viral Hit alias");
        check(Arrays.asList(Names.searchAliases("Loser Life")).contains("My Life as a Loser"), "Loser alias");
        check(Names.searchAliases("https://example.com").length == 0, "URL unchanged");
        check(Names.searchAliases("Other title").length == 0, "unrelated title unchanged");
        System.out.println("PASS: 5 search alias checks");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
