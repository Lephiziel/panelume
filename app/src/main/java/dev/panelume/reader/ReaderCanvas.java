package dev.panelume.reader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.OverScroller;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;

final class ReaderCanvas extends View {
    static final int INK = 0xff121310;
    private static final int TILE_HEIGHT = 320;
    private static final int CACHE_BYTES = 24 * 1024 * 1024;
    private static final int DETAIL_BYTES = 32 * 1024 * 1024;

    interface Observer {
        void viewport(int page, double fraction, int percent, double bottom,
                      boolean anyRendered, boolean allRendered, boolean atEnd);
        void tapped();
    }

    private final MangaDocument document;
    private final ExecutorService worker;
    private final Observer observer;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint placeholder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect target = new Rect();
    private final OverScroller scroller;
    private final GestureDetector gestures;
    private final ScaleGestureDetector pinch;
    private final LruCache<Long, Bitmap> cache = new LruCache<Long, Bitmap>(CACHE_BYTES) {
        @Override
        protected int sizeOf(Long key, Bitmap value) {
            return value.getAllocationByteCount();
        }
    };
    private final LruCache<Long, Bitmap> detailCache = new LruCache<Long, Bitmap>(DETAIL_BYTES) {
        @Override protected int sizeOf(Long key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private final Set<Long> failed = new HashSet<>();
    private final Set<Long> detailFailed = new HashSet<>();
    private final double[] starts;
    private final Bookmark.Position initial;

    private int bitmapWidth;
    private int panelWidth;
    private double zoom = 1.0;
    private double left;
    private float lastFocusX, lastFocusY;
    private boolean multiTouch;
    private double scale;
    private double y;
    private double flingOrigin;
    private boolean tilePending;
    private boolean laidOut;
    private boolean released;

    ReaderCanvas(Context context, MangaDocument document, ExecutorService worker,
                 Bookmark.Position initial, Observer observer) {
        super(context);
        this.document = document;
        this.worker = worker;
        this.initial = initial;
        this.observer = observer;
        starts = new double[document.pages.length + 1];
        scroller = new OverScroller(context);
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                freeze();
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent event) {
                return performClick();
            }

            @Override
            public boolean onDoubleTap(MotionEvent event) {
                if (laidOut) changeZoom(zoom > 1.01 ? 1.0 : 2.5,
                    event.getX(), event.getY(), event.getX(), event.getY());
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent down, MotionEvent move, float dx, float dy) {
                if (zoom > 1.0) {
                    left = clampLeft(left - dx);
                    postInvalidateOnAnimation();
                }
                moveTo(y + dy);
                return true;
            }

            @Override
            public boolean onFling(MotionEvent down, MotionEvent up, float vx, float vy) {
                flingOrigin = y;
                scroller.fling(0, 0, 0, Math.round(-vy), 0, 0,
                    -relativeLimit(y), relativeLimit(maxY() - y));
                postInvalidateOnAnimation();
                return true;
            }
        });
        gestures.setIsLongpressEnabled(false);
        pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                freeze();
                lastFocusX = detector.getFocusX();
                lastFocusY = detector.getFocusY();
                return laidOut;
            }
            @Override public boolean onScale(ScaleGestureDetector detector) {
                float x = detector.getFocusX(), yy = detector.getFocusY();
                double factor = detector.getScaleFactor();
                if (Double.isFinite(factor) && factor > 0) {
                    changeZoom(zoom * factor, lastFocusX, lastFocusY, x, yy);
                }
                lastFocusX = x;
                lastFocusY = yy;
                return true;
            }
        });
        pinch.setQuickScaleEnabled(false);
        pinch.setStylusScaleEnabled(false);
        setFocusable(true);
        setClickable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription(getContext().getString(R.string.reader_accessibility));
        setBackgroundColor(INK);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        if (width <= 0 || height <= 0) return;
        double anchor = laidOut ? anchorAt(y) : initial.page + initial.fraction;
        freeze();
        panelWidth = Math.min(width, Ui.dp(getContext(), 820));
        if (width != oldWidth) zoom = 1.0;
        rebuild();
        left = clampLeft((width - contentWidth()) / 2);
        y = clamp(positionOf(anchor));
        laidOut = true;
    }

    private double contentWidth() { return panelWidth * zoom; }

    private void rebuild() {
        // The base tile grid is independent of zoom. A gesture must never evict visible art.
        int wanted = Math.min(panelWidth, 1280);
        if (wanted != bitmapWidth) {
            bitmapWidth = wanted;
            cache.evictAll();
            detailCache.evictAll();
            failed.clear();
            detailFailed.clear();
        }
        scale = contentWidth() / bitmapWidth;
        for (int i = 0; i < document.pages.length; i++) {
            starts[i + 1] = starts[i] + scale * document.heightAtWidth(i, bitmapWidth);
        }
    }

    private double positionOf(double anchor) {
        int page = Math.max(0, Math.min(document.pages.length - 1, (int) anchor));
        double fraction = Math.max(0, Math.min(1, anchor - page));
        return starts[page] + (starts[page + 1] - starts[page]) * fraction;
    }

    private double clampLeft(double proposed) {
        double width = contentWidth();
        return width <= getWidth() ? (getWidth() - width) / 2
            : Math.max(getWidth() - width, Math.min(0, proposed));
    }

    private void changeZoom(double requested, float oldX, float oldY, float x, float yy) {
        if (!laidOut || released) return;
        freeze();
        double anchor = anchorAt(y + oldY);
        double across = Math.max(0, Math.min(1, (oldX - left) / contentWidth()));
        zoom = Math.max(1.0, Math.min(4.0, requested));
        if (zoom < 1.01) zoom = 1.0;
        rebuild();
        left = clampLeft(x - across * contentWidth());
        y = clamp(positionOf(anchor) - yy);
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!laidOut || released) return;
        double bottomY = y + getHeight();
        int first = pageAt(y);
        boolean any = false;
        boolean all = true;
        int missingPage = -1;
        int missingTile = 0;
        int detailPage = -1;
        int detailTile = 0;
        long visibleDetailBytes = 0;
        boolean wantDetail = zoom >= 1.5;

        for (int page = first; page < document.pages.length && starts[page] < bottomY; page++) {
            int height = document.heightAtWidth(page, bitmapWidth);
            int firstTile = Math.max(0, (int) ((y - starts[page]) / scale) / TILE_HEIGHT);
            for (int top = firstTile * TILE_HEIGHT; top < height; top += TILE_HEIGHT) {
                int screenTop = (int) Math.round(starts[page] + top * scale - y);
                if (screenTop >= getHeight()) break;
                int tileHeight = Math.min(TILE_HEIGHT, height - top);
                long key = key(page, top);
                // Adjacent tiles share one pixel boundary even at fractional scroll positions.
                int screenBottom = (int) Math.round(starts[page] + (top + tileHeight) * scale - y);
                target.set((int) Math.round(left), screenTop, (int) Math.round(left + contentWidth()), screenBottom);
                Bitmap bitmap = cache.get(key);
                Bitmap detail = wantDetail ? detailCache.get(key) : null;
                if (wantDetail) {
                    visibleDetailBytes += (long) detailWidth() * detailHeight(page, top, tileHeight) * 4;
                    if (detail == null && !detailFailed.contains(key) && detailPage == -1) {
                        detailPage = page;
                        detailTile = top;
                    }
                }
                if (bitmap != null || detail != null) {
                    canvas.drawBitmap(detail != null ? detail : bitmap, null, target, bitmapPaint);
                    any = true;
                    // Keep the base tile available even if the detail tile arrived earlier.
                    if (bitmap == null && !failed.contains(key) && missingPage == -1) {
                        missingPage = page;
                        missingTile = top;
                    }
                } else {
                    all = false;
                    boolean broken = failed.contains(key);
                    placeholder.setColor(broken ? 0xff302521 : 0xff22231f);
                    canvas.drawRect(target, placeholder);
                    if (broken) {
                        placeholder.setColor(Ui.ERROR);
                        placeholder.setTextSize(Ui.dp(getContext(), 14));
                        canvas.drawText(getContext().getString(R.string.page_error), Math.max(0, (int) left) + Ui.dp(getContext(), 18),
                            Math.max(screenTop + Ui.dp(getContext(), 30), Ui.dp(getContext(), 38)), placeholder);
                    } else if (missingPage == -1) {
                        missingPage = page;
                        missingTile = top;
                    }
                }
            }
        }
        if (missingPage != -1) queue(missingPage, missingTile, false);
        // First render cached base art during the gesture. Upgrade visible tiles only after
        // fingers lift; never chase a detail working set bigger than the detail cache.
        else if (wantDetail && !multiTouch && detailPage != -1
            && visibleDetailBytes <= DETAIL_BYTES) queue(detailPage, detailTile, true);

        double anchor = anchorAt(y);
        double maxContentScroll = Math.max(1, storyHeight() - getHeight());
        int percent = Math.min(100, (int) Math.floor(y / maxContentScroll * 100));
        // At the end, leave room below the art for the controls so the last panel remains visible.
        boolean end = storyHeight() <= bottomY - Math.min(Ui.dp(getContext(), 118), footerHeight() * .75);
        // A cropped viewport is not proof that the entire panel was read.
        observer.viewport((int) anchor, anchor - (int) anchor, percent, anchorAt(bottomY),
            any, all && any && zoom == 1.0, end && zoom == 1.0);
    }

    private long key(int page, int top) {
        return ((long) page << 32) | (top & 0xffffffffL);
    }

    private int detailWidth() { return Math.min(2560, bitmapWidth * 2); }

    private int detailBoundary(int page, int baseRow) {
        return ZoomTiles.boundary(baseRow, document.heightAtWidth(page, bitmapWidth),
            document.heightAtWidth(page, detailWidth()));
    }

    private int detailHeight(int page, int top, int height) {
        return detailBoundary(page, top + height) - detailBoundary(page, top);
    }

    private void queue(int page, int top, boolean detail) {
        if (tilePending || released) return;
        final int baseWidth = bitmapWidth;
        final int baseHeight = Math.min(TILE_HEIGHT, document.heightAtWidth(page, baseWidth) - top);
        final int width = detail ? detailWidth() : baseWidth;
        final int renderTop = detail ? detailBoundary(page, top) : top;
        final int height = detail ? detailHeight(page, top, baseHeight) : baseHeight;
        final long key = key(page, top);
        tilePending = true;
        worker.execute(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = document.tile(page, width, renderTop, height);
            } catch (IOException | RuntimeException e) {
                Log.w("PanelumeReader", "Tile unavailable at page " + (page + 1), e);
            }
            Bitmap result = bitmap;
            main.post(() -> {
                tilePending = false;
                if (!released && baseWidth == bitmapWidth) {
                    if (detail) {
                        if (result == null) detailFailed.add(key);
                        else detailCache.put(key, result);
                    } else {
                        if (result == null) failed.add(key);
                        else cache.put(key, result);
                    }
                } else if (result != null) {
                    result.recycle();
                }
                if (!released) invalidate();
            });
        });
    }

    private double storyHeight() {
        return starts[document.pages.length];
    }

    private double maxY() {
        return Math.min(Math.max(0, storyHeight() + footerHeight() - getHeight()), Math.max(0, storyHeight() - 1));
    }

    private double footerHeight() {
        return Math.min(Ui.dp(getContext(), 170), getHeight() * .40);
    }

    private double clamp(double value) {
        return Math.max(0, Math.min(maxY(), value));
    }

    private int pageAt(double position) {
        int index = Arrays.binarySearch(starts, position);
        if (index < 0) index = -index - 2;
        return Math.max(0, Math.min(document.pages.length - 1, index));
    }

    private double anchorAt(double position) {
        int page = pageAt(position);
        double within = (position - starts[page]) / (starts[page + 1] - starts[page]);
        return page + Math.max(0, Math.min(1, within));
    }

    private int relativeLimit(double available) {
        return (int) Math.min(Integer.MAX_VALUE / 2, Math.max(0, Math.ceil(available)));
    }

    private void moveTo(double next) {
        double target = clamp(next);
        if (target == y) return;
        y = target;
        postInvalidateOnAnimation();
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            moveTo(flingOrigin + scroller.getCurrY());
            if (!scroller.isFinished()) postInvalidateOnAnimation();
        }
    }

    void freeze() {
        scroller.forceFinished(true);
    }

    void restart() {
        freeze();
        if (laidOut && zoom != 1.0) {
            zoom = 1.0;
            rebuild();
            left = clampLeft(0);
        }
        moveTo(0);
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (released) return false;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) multiTouch = false;
        if (action == MotionEvent.ACTION_POINTER_DOWN && !multiTouch) {
            multiTouch = true;
            MotionEvent cancel = MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            gestures.onTouchEvent(cancel);
            cancel.recycle();
        }
        pinch.onTouchEvent(event);
        if (!multiTouch) gestures.onTouchEvent(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            multiTouch = false;
            // Start detail decoding even when the final pinch event did not change zoom.
            postInvalidateOnAnimation();
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        observer.tapped();
        return true;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_SCROLL) {
            freeze();
            moveTo(y - event.getAxisValue(MotionEvent.AXIS_VSCROLL) * Ui.dp(getContext(), 64));
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent event) {
        if (code == KeyEvent.KEYCODE_PAGE_DOWN || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            moveTo(y + getHeight() * .75);
            return true;
        }
        if (code == KeyEvent.KEYCODE_PAGE_UP || code == KeyEvent.KEYCODE_DPAD_UP) {
            moveTo(y - getHeight() * .75);
            return true;
        }
        return super.onKeyDown(code, event);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.ScrollView");
        info.setScrollable(true);
        if (y < maxY()) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        if (y > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle args) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            freeze();
            moveTo(y + getHeight() * (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? .76 : -.76));
            return true;
        }
        return super.performAccessibilityAction(action, args);
    }

    void release() {
        released = true;
        freeze();
        cache.evictAll();
        detailCache.evictAll();
        worker.execute(() -> {
            try {
                document.close();
            } catch (IOException e) {
                Log.w("PanelumeReader", "Could not close chapter", e);
            }
        });
    }
}
