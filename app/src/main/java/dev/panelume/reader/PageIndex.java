package dev.panelume.reader;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.CRC32;

/** Optional derived metadata, never a source of reading progress. */
final class PageIndex {
    static final int MAX_PAGES=6000;
    private static final int MAGIC=0x50474931;
    private static final int MAX_BYTES=MAX_PAGES*8+128;
    private PageIndex() {}

    static final class Stamp {
        final long length,modified;
        Stamp(long length,long modified) { this.length=length; this.modified=modified; }
        boolean matches(File file) {
            return length>0 && file.isFile() && file.length()==length && file.lastModified()==modified;
        }
    }

    static Stamp stamp(File file) {
        try { return new Stamp(file.length(),file.lastModified()); }
        catch (RuntimeException error) { return new Stamp(0,0); }
    }

    static File sidecar(File file) { return new File(file.getParentFile(),"page-index-v1.bin"); }

    private static String hashPart(String digest) {
        if (digest==null || digest.length()<64) return "";
        String hash=digest.substring(0,64);
        if (!hash.matches("[a-f0-9]{64}") || (digest.length()>64 && digest.charAt(64)!=':')) return "";
        return hash;
    }

    static MangaDocument.PageSize[] read(File file,String format,String digest,int count,Stamp stamp) {
        String hash=hashPart(digest);
        try {
            if (hash.isEmpty() || count<1 || count>MAX_PAGES || !stamp.matches(file)) return null;
            File index=sidecar(file);
            if (!index.isFile() || index.length()>MAX_BYTES || index.length()<16) return null;
            byte[] data;
            try (InputStream input=new FileInputStream(index)) {
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                byte[] buffer=new byte[4096]; int n;
                while ((n=input.read(buffer))!=-1) {
                    if (n>MAX_BYTES-bytes.size()) return null;
                    bytes.write(buffer,0,n);
                }
                data=bytes.toByteArray();
            }
            if (data.length<8) return null;
            CRC32 crc=new CRC32(); crc.update(data,0,data.length-4);
            try (DataInputStream in=new DataInputStream(new ByteArrayInputStream(data))) {
                if (in.readInt()!=MAGIC || !format.equals(in.readUTF()) || !hash.equals(in.readUTF())
                    || in.readLong()!=stamp.length || in.readLong()!=stamp.modified || in.readInt()!=count) return null;
                MangaDocument.PageSize[] pages=new MangaDocument.PageSize[count];
                for (int i=0;i<count;i++) pages[i]=new MangaDocument.PageSize(in.readInt(),in.readInt());
                if (in.readInt()!=(int)crc.getValue() || in.read()!=-1 || !stamp.matches(file)) return null;
                return pages;
            }
        } catch (IOException | RuntimeException error) { return null; }
    }

    static void write(File file,String format,String digest,int count,Stamp stamp,MangaDocument.PageSize[] pages) {
        File temporary=null;
        try {
            String hash=hashPart(digest);
            if (hash.isEmpty() || !("cbz".equals(format)||"pdf".equals(format)) || count<1 || count>MAX_PAGES
                || pages==null || pages.length!=count || !stamp.matches(file)) return;
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            DataOutputStream out=new DataOutputStream(bytes);
            out.writeInt(MAGIC); out.writeUTF(format); out.writeUTF(hash);
            out.writeLong(stamp.length); out.writeLong(stamp.modified); out.writeInt(count);
            for (MangaDocument.PageSize page:pages) { out.writeInt(page.width); out.writeInt(page.height); }
            out.flush();
            CRC32 crc=new CRC32(); crc.update(bytes.toByteArray());
            out.writeInt((int)crc.getValue()); out.flush();
            temporary=File.createTempFile("page-index-",".tmp",file.getParentFile());
            try (FileOutputStream stream=new FileOutputStream(temporary)) { bytes.writeTo(stream); }
            if (stamp.matches(file))
                Files.move(temporary.toPath(),sidecar(file).toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException ignored) {
            // Cache write failure must never fail an otherwise valid import/open.
        } finally { if (temporary!=null) temporary.delete(); }
    }
}
