package dev.panelume.reader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** Copy the original bookmark records before swapping the registered archive directory. */
final class FilePair {
    private FilePair() {}
    static void copyPosition(File root, ShelfDb.Chapter old, String destination) throws IOException {
        File from = old.bookmark(root);
        File to = new File(new File(root,destination),"position");
        if (!from.getCanonicalFile().equals(new File(root.getCanonicalFile(),old.directory+"/position"))
            || !to.getCanonicalFile().equals(new File(root.getCanonicalFile(),destination+"/position")))
            throw new IOException("Unexpected bookmark path");
        if (!from.exists()) return;
        if (!from.isFile() || from.length()!=80 || to.exists()) throw new IOException("Invalid bookmark file");
        byte[] bytes = new byte[80];
        try (FileInputStream in = new FileInputStream(from)) {
            int offset=0;
            while (offset<bytes.length) {
                int n=in.read(bytes,offset,bytes.length-offset);
                if (n<0) throw new IOException("Incomplete bookmark");
                offset+=n;
            }
            if (in.read()!=-1) throw new IOException("Unexpected bookmark length");
        }
        try (FileOutputStream out = new FileOutputStream(to)) {
            out.write(bytes);
            out.getFD().sync();
        }
    }
}
