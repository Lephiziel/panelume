package dev.panelume.reader;
import java.util.List;

/** Catalogue order is authoritative; never invent or skip a successor. */
final class NextChapter {
    static final int THRESHOLD=75;
    private NextChapter() {}
    static boolean canPrepare(int percent,boolean eligible,boolean enabled,boolean networkAllowed) {
        return percent>=THRESHOLD && eligible && enabled && networkAllowed;
    }
    static boolean readyToPrepare(int percent,double frontier,int pages,boolean allRendered) {
        return allRendered && pages>0 && percent>=THRESHOLD && frontier>=pages*(THRESHOLD/100.0);
    }
    static Successor after(List<CatalogSource.Episode> feed,String current) {
        if (current==null || current.isEmpty()) return null;
        int found=-1;
        for (int i=0;i<feed.size();i++) {
            if (current.equals(feed.get(i).id)) {
                if (found>=0) return null;
                found=i;
            }
        }
        if (found<0) return null;
        if (found+1==feed.size()) return new Successor(null);
        CatalogSource.Episode next=feed.get(found+1);
        if (next.id.isEmpty()) return null;
        int matches=0;
        for (CatalogSource.Episode episode:feed) if (next.id.equals(episode.id)) matches++;
        return matches==1?new Successor(next):null;
    }
    static final class Successor {
        final CatalogSource.Episode episode;
        Successor(CatalogSource.Episode episode) { this.episode=episode; }
    }
}
