package dev.panelume.reader;

public final class ZoomTilesTest {
    public static void main(String[] args) {
        int checks = 0;
        for (int base : new int[]{1, 319, 320, 321, 641, 1777, 120000, 3072000}) {
            for (int detail : new int[]{base, base * 2 - 1, base * 2, base * 2 + 1}) {
                int last = 0;
                for (int top = 0; top < base; top += 320) {
                    int bottom = Math.min(base, top + 320);
                    int first = ZoomTiles.boundary(top, base, detail);
                    int next = ZoomTiles.boundary(bottom, base, detail);
                    if (first != last || next <= first || next > detail) {
                        throw new AssertionError("Gap, overlap or empty tile");
                    }
                    last = next;
                    checks++;
                }
                if (last != detail) throw new AssertionError("Missing last row");
            }
        }
        System.out.println("PASS: " + checks + " tile intervals");
    }
}
