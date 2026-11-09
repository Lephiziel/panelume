package dev.panelume.reader;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

abstract class MangaDocument implements Closeable {
    private static final int MAX_PAGES = 6000;
    private static final long MAX_IMAGE_BYTES = 48L * 1024 * 1024;
    PageSize[] pages;
    String series = "";
    String number = "";

    static MangaDocument open(File file, String format) throws IOException {
        if ("pdf".equals(format)) return new Pdf(file);
        if ("cbz".equals(format)) return new Archive(file);
        throw new Fault(R.string.format_error);
    }

    abstract Bitmap tile(int page, int width, int top, int height) throws IOException;

    int heightAtWidth(int page, int width) {
        PageSize size = pages[page];
        return Math.max(1, (int) Math.ceil((double) width * size.height / size.width));
    }

    private static void checkCount(int size) throws IOException {
        if (size == 0) throw new Fault(R.string.empty_chapter);
        if (size > MAX_PAGES) throw new Fault(R.string.page_limit, MAX_PAGES);
    }

    static final class PageSize {
        final int width;
        final int height;

        PageSize(int width, int height) throws IOException {
            if (width < 1 || height < 1 || width > 60000 || height > 300000 || (double) height / width > 2400) {
                throw new Fault(R.string.page_size_error);
            }
            this.width = width;
            this.height = height;
        }
    }

    private static final class Pdf extends MangaDocument {
        private final PdfRenderer renderer;

        Pdf(File file) throws IOException {
            ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            try {
                renderer = new PdfRenderer(descriptor);
            } catch (SecurityException e) {
                descriptor.close();
                throw new Fault(R.string.password_pdf);
            } catch (IOException | RuntimeException e) {
                descriptor.close();
                throw e;
            }
            try {
                int count = renderer.getPageCount();
                checkCount(count);
                pages = new PageSize[count];
                for (int i = 0; i < count; i++) {
                    if (Thread.currentThread().isInterrupted()) throw new Fault(R.string.operation_cancelled);
                    try (PdfRenderer.Page page = renderer.openPage(i)) {
                        pages[i] = new PageSize(page.getWidth(), page.getHeight());
                    }
                }
            } catch (IOException | RuntimeException e) {
                close();
                throw e;
            }
        }

        @Override
        Bitmap tile(int index, int width, int top, int height) {
            try (PdfRenderer.Page page = renderer.openPage(index)) {
                Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                result.eraseColor(Color.WHITE);
                Matrix transform = new Matrix();
                transform.setScale((float) width / page.getWidth(), (float) heightAtWidth(index, width) / page.getHeight());
                transform.postTranslate(0f, -top);
                try {
                    page.render(result, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    return result;
                } catch (RuntimeException e) {
                    result.recycle();
                    throw e;
                }
            }
        }

        @Override
        public void close() {
            renderer.close();
        }
    }

    private static final class Archive extends MangaDocument {
        private final ZipFile zip;
        private final List<ZipEntry> images = new ArrayList<>();
        private BitmapRegionDecoder decoder;
        private int decoderPage = -1;

        Archive(File file) throws IOException {
            zip = new ZipFile(file);
            try {
                if (zip.size() > MAX_PAGES * 2) throw new Fault(R.string.archive_limit);
                Enumeration<? extends ZipEntry> entries = zip.entries();
                ZipEntry metadata = null;
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    String name = entry.getName().toLowerCase(Locale.ROOT);
                    if (name.startsWith("__macosx/") || name.matches("(^|.*/)\\._[^/]+$")) continue;
                    if (name.equals("comicinfo.xml") || name.endsWith("/comicinfo.xml")) {
                        if (metadata == null || name.equals("comicinfo.xml")) metadata = entry;
                    } else if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
                        || name.endsWith(".webp")) {
                        if (entry.getSize() < 1 || entry.getSize() > MAX_IMAGE_BYTES) {
                            throw new Fault(R.string.image_limit);
                        }
                        images.add(entry);
                    }
                }
                checkCount(images.size());
                images.sort((left, right) -> Names.naturalCompare(left.getName(), right.getName()));
                pages = new PageSize[images.size()];
                for (int i = 0; i < pages.length; i++) {
                    if (Thread.currentThread().isInterrupted()) throw new Fault(R.string.operation_cancelled);
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    try (InputStream in = stream(images.get(i))) {
                        BitmapFactory.decodeStream(in, null, options);
                    }
                    pages[i] = new PageSize(options.outWidth, options.outHeight);
                }
                if (metadata != null && metadata.getSize() > 0 && metadata.getSize() <= 65536) {
                    readMetadata(metadata);
                }
            } catch (IOException | RuntimeException e) {
                close();
                throw e;
            }
        }

        private InputStream stream(ZipEntry entry) throws IOException {
            return new BufferedInputStream(zip.getInputStream(entry));
        }

        private void readMetadata(ZipEntry entry) {
            try (InputStream input = stream(entry)) {
                XmlPullParser parser = Xml.newPullParser();
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
                parser.setInput(input, null);
                for (int event = parser.next(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                    if (event != XmlPullParser.START_TAG || parser.getDepth() != 2) continue;
                    if ("Series".equals(parser.getName())) series = parser.nextText().trim();
                    else if ("Number".equals(parser.getName())) number = Names.normalizeNumber(parser.nextText());
                }
            } catch (IOException | XmlPullParserException | IllegalArgumentException ignored) {
                // Optional catalog metadata. The file name remains available.
            }
        }

        @Override
        Bitmap tile(int page, int width, int top, int height) throws IOException {
            if (decoderPage != page) {
                if (decoder != null) decoder.recycle();
                decoder = null;
                decoderPage = -1;
                try (InputStream in = stream(images.get(page))) {
                    decoder = BitmapRegionDecoder.newInstance(in, false);
                }
                if (decoder == null) throw new Fault(R.string.bad_image);
                decoderPage = page;
            }
            PageSize size = pages[page];
            // Fill the same rounded height used by the reader's layout, including the last row.
            double scale = (double) heightAtWidth(page, width) / size.height;
            int sourceTop = Math.min(size.height - 1, (int) Math.floor(top / scale));
            int sourceBottom = Math.min(size.height, Math.max(sourceTop + 1, (int) Math.ceil((top + height) / scale)));
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inSampleSize = Math.max(1, Integer.highestOneBit(size.width / width));
            Bitmap region = decoder.decodeRegion(new Rect(0, sourceTop, size.width, sourceBottom), options);
            if (region == null) throw new Fault(R.string.bad_image);
            try {
                Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(result);
                canvas.drawColor(Color.WHITE);
                RectF destination = new RectF(0, (float) (sourceTop * scale - top), width,
                    (float) (sourceBottom * scale - top));
                canvas.drawBitmap(region, null, destination, new Paint(Paint.FILTER_BITMAP_FLAG));
                return result;
            } finally {
                region.recycle();
            }
        }

        @Override
        public void close() throws IOException {
            if (decoder != null) decoder.recycle();
            zip.close();
        }
    }
}
