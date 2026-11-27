package dev.panelume.reader;
import java.io.*;
import java.nio.file.Files;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PageIndexTest {
    private static final String HASH="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static File fixture() throws IOException {
        File folder=Files.createTempDirectory("page-index-test").toFile();
        File file=new File(folder,"chapter.cbz");
        try (FileOutputStream out=new FileOutputStream(file)) {out.write(new byte[]{1,2,3});}
        return file;
    }
    private static void cleanup(File file) { File[] children=file.getParentFile().listFiles(); if(children!=null) for(File child:children) child.delete(); file.getParentFile().delete(); }
    private static MangaDocument.PageSize[] sizes() throws IOException {
        return new MangaDocument.PageSize[]{new MangaDocument.PageSize(800,1200),new MangaDocument.PageSize(900,20000)};
    }
    @Test public void roundTripAndCatalogueHashSuffix() throws Exception {
        File file=fixture();
        try {
            PageIndex.Stamp stamp=PageIndex.stamp(file);
            assertNull(PageIndex.read(file,"cbz",HASH,2,stamp));
            PageIndex.write(file,"cbz",HASH,2,stamp,sizes());
            MangaDocument.PageSize[] result=PageIndex.read(file,"cbz",HASH+":episode",2,stamp);
            assertNotNull(result); assertEquals(800,result[0].width); assertEquals(20000,result[1].height);
            assertNull(PageIndex.read(file,"pdf",HASH,2,stamp));
            assertNull(PageIndex.read(file,"cbz",HASH,1,stamp));
            assertNull(PageIndex.read(file,"cbz",HASH.replace('0','1'),2,stamp));
        } finally { cleanup(file); }
    }
    @Test public void staleAndDamagedIndexIsCacheMiss() throws Exception {
        File file=fixture();
        try {
            PageIndex.Stamp stamp=PageIndex.stamp(file);
            PageIndex.write(file,"cbz",HASH,2,stamp,sizes());
            try (RandomAccessFile index=new RandomAccessFile(PageIndex.sidecar(file),"rw")) {
                index.seek(index.length()-1); int b=index.read(); index.seek(index.length()-1); index.write(b^1);
            }
            assertNull(PageIndex.read(file,"cbz",HASH,2,stamp));
            PageIndex.write(file,"cbz",HASH,2,stamp,sizes());
            assertNotNull(PageIndex.read(file,"cbz",HASH,2,stamp));
            try (FileOutputStream out=new FileOutputStream(file,true)) {out.write(9);}
            assertNull(PageIndex.read(file,"cbz",HASH,2,stamp));
            assertNull(PageIndex.read(file,"cbz",HASH,2,PageIndex.stamp(file)));
        } finally { cleanup(file); }
    }
    @Test public void truncatedAndOversizedIndexAreIgnored() throws Exception {
        File file=fixture();
        try {
            PageIndex.Stamp stamp=PageIndex.stamp(file);
            PageIndex.write(file,"cbz",HASH,2,stamp,sizes());
            try (RandomAccessFile index=new RandomAccessFile(PageIndex.sidecar(file),"rw")) { index.setLength(9); }
            assertNull(PageIndex.read(file,"cbz",HASH,2,stamp));
            try (RandomAccessFile index=new RandomAccessFile(PageIndex.sidecar(file),"rw")) { index.setLength(50000); }
            assertNull(PageIndex.read(file,"cbz",HASH,2,stamp));
            PageIndex.write(file,"cbz",HASH,2,stamp,sizes());
            assertNotNull(PageIndex.read(file,"cbz",HASH,2,stamp));
        } finally {cleanup(file);}
    }
}
