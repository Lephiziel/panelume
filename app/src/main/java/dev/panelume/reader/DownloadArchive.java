package dev.panelume.reader;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.zip.Deflater;
import java.util.zip.ZipOutputStream;

/** Hash exactly the bytes written, including the ZIP central directory. */
final class DownloadArchive implements Closeable {
    private final MessageDigest sha=ImportFiles.sha256();
    private final FileOutputStream file;
    final ZipOutputStream zip;
    private boolean finished;
    DownloadArchive(File target) throws IOException {
        file=new FileOutputStream(target);
        zip=new ZipOutputStream(new DigestOutputStream(new BufferedOutputStream(file,65536),sha));
        zip.setLevel(Deflater.NO_COMPRESSION);
    }
    byte[] finish() throws IOException {
        if (finished) throw new IOException("Archive already finished");
        zip.finish();
        zip.flush();
        file.getFD().sync();
        finished=true;
        return sha.digest();
    }
    @Override public void close() throws IOException { zip.close(); }
}
