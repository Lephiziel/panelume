package dev.panelume.reader;

import java.util.function.IntUnaryOperator;
import java.util.function.LongPredicate;

/** Select at most two fully offscreen tiles, including across page boundaries. */
final class PrefetchTiles {
    static final int LIMIT=2;
    private PrefetchTiles() {}

    static int firstUnseen(double bottomInPage,int tileHeight) {
        if (bottomInPage<=0) return 0;
        return (int)Math.ceil(bottomInPage/tileHeight)*tileHeight;
    }

    static long next(int page,double bottomInPage,int pages,int tileHeight,
                     IntUnaryOperator pageHeight,LongPredicate missing) {
        if (page<0 || page>=pages || tileHeight<=0) return -1;
        int top=firstUnseen(bottomInPage,tileHeight);
        int height=pageHeight.applyAsInt(page);
        for (int count=0;count<LIMIT;count++) {
            if (top>=height) {
                if (++page>=pages) return -1;
                top=0;
                height=pageHeight.applyAsInt(page);
            }
            long key=((long)page<<32)|(top&0xffffffffL);
            if (missing.test(key)) return key;
            top+=tileHeight;
        }
        return -1;
    }
}
