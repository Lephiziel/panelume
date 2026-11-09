package dev.panelume.reader;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ReaderActivity extends PanelumeActivity implements ReaderCanvas.Observer {
    private static final String CHAPTER = "chapter";
    private static final String RESTART = "restart";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService renderer = Executors.newSingleThreadExecutor();
    private final Runnable completeAtEnd = this::completeIfStillAtEnd;

    private ShelfDb db;
    private ShelfDb.Chapter chapter;
    private ShelfDb.Chapter next;
    private ReaderCanvas canvas;
    private Bookmark bookmark;
    private ReadingClock clock;
    private FrameLayout root;
    private LinearLayout top;
    private LinearLayout bottom;
    private LinearLayout endCard;
    private FrameLayout loading;
    private TextView counter;
    private TextView endTitle;
    private TextView endAction;
    private ProgressBar meter;
    private boolean resumed;
    private boolean chrome = true;
    private boolean opened;
    private boolean atEnd;
    private boolean lastPanelRendered;
    private boolean completed;
    private boolean completionScheduled;
    private boolean receiverRegistered;
    private boolean destroyed;
    private double frontier;
    private int currentPage = -1;
    private int currentPercent = -1;

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                checkpoint();
            } else {
                updateTracking();
            }
        }
    };

    static Intent intent(Context context, long chapter, boolean restart) {
        return new Intent(context, ReaderActivity.class).putExtra(CHAPTER, chapter).putExtra(RESTART, restart);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        db = ShelfDb.get(this);
        chapter = db.chapter(getIntent().getLongExtra(CHAPTER, -1));
        if (chapter == null) {
            finish();
            return;
        }
        next = db.next(chapter);
        completed = chapter.done;
        if (state != null) chrome = state.getBoolean("chrome", true);
        clock = new ReadingClock(db, chapter.id, this::eligible);
        Ui.edgeWindow(this, ReaderCanvas.INK);
        build();
        load(state == null && getIntent().getBooleanExtra(RESTART, false));

        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screen, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screen, filter);
        receiverRegistered = true;
    }

    private void build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(ReaderCanvas.INK);
        setContentView(root);

        loading = new FrameLayout(this);
        loading.setBackgroundColor(ReaderCanvas.INK);
        LinearLayout message = Ui.column(this);
        message.setGravity(Gravity.CENTER);
        Ui.add(message, new ProgressBar(this), 34, 34);
        Ui.gap(message, 16);
        Ui.add(message, Ui.strong(this, getString(R.string.opening_chapter), 15, Ui.MUTED), Ui.WRAP, Ui.WRAP);
        loading.addView(message, new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.CENTER));
        root.addView(loading, new FrameLayout.LayoutParams(Ui.FILL, Ui.FILL));

        top = Ui.column(this);
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0xfa121310, 0xeb121310, 0x00121310}));
        LinearLayout toolbar = Ui.row(this);
        Ui.add(toolbar, Ui.iconButton(this, "back", getString(R.string.back), 0xbf242522, Ui.TEXT, this::finish), 48, 48);
        Ui.spacer(toolbar, 12);
        LinearLayout labels = Ui.column(this);
        TextView title = Ui.strong(this, chapter.title, 17, Ui.TEXT);
        Ui.ellipsize(title, 1);
        Ui.add(labels, title, Ui.FILL, Ui.WRAP);
        Ui.gap(labels, 4);
        TextView chapterName = Ui.label(this, chapter.label(this), 13, Ui.MUTED);
        Ui.ellipsize(chapterName, 1);
        Ui.add(labels, chapterName, Ui.FILL, Ui.WRAP);
        if (!chapter.provider.isEmpty()) {
            String source = CatalogSource.label(chapter.provider);
            if (!chapter.credit.isEmpty()) source += " · " + chapter.credit;
            Ui.gap(labels, 3);
            TextView credit = Ui.label(this, source, 11, Ui.MUTED);
            Ui.ellipsize(credit, 1);
            Ui.add(labels, credit, Ui.FILL, Ui.WRAP);
        }
        Ui.weighted(toolbar, labels);
        Ui.spacer(toolbar, 10);
        Ui.add(toolbar, Ui.iconButton(this, "restart", getString(R.string.jump_to_start), 0xbf242522, Ui.ACCENT, () -> {
            if (canvas != null) canvas.restart();
        }), 48, 48);
        Ui.add(top, toolbar, Ui.FILL, Ui.WRAP);
        root.addView(top, new FrameLayout.LayoutParams(Ui.FILL, Ui.WRAP, Gravity.TOP));

        bottom = Ui.column(this);
        bottom.setGravity(Gravity.CENTER);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0x00121310, 0xe9121310, 0xfa121310}));
        counter = Ui.strong(this, " ", 13, Ui.TEXT);
        counter.setGravity(Gravity.CENTER);
        Ui.add(bottom, counter, Ui.FILL, Ui.WRAP);
        Ui.gap(bottom, 8);
        meter = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        meter.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        meter.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Ui.BORDER));
        meter.setMax(100);
        meter.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        Ui.add(bottom, meter, Ui.FILL, 5);
        Ui.gap(bottom, 9);
        TextView hint = Ui.label(this, getString(R.string.reader_hint), 12, Ui.MUTED);
        hint.setGravity(Gravity.CENTER);
        Ui.add(bottom, hint, Ui.FILL, Ui.WRAP);
        root.addView(bottom, new FrameLayout.LayoutParams(Ui.FILL, Ui.WRAP, Gravity.BOTTOM));

        endCard = Ui.column(this);
        endCard.setBackground(Ui.card(this, Ui.SURFACE, 20));
        Ui.pad(endCard, 14, 11, 14, 11);
        endTitle = Ui.strong(this, "", 13, Ui.MINT);
        endTitle.setGravity(Gravity.CENTER);
        Ui.add(endCard, endTitle, Ui.FILL, Ui.WRAP);
        Ui.gap(endCard, 8);
        endAction = Ui.button(this, "", true, this::endAction);
        Ui.add(endCard, endAction, Ui.FILL, Ui.WRAP);
        endCard.setVisibility(View.GONE);
        root.addView(endCard, new FrameLayout.LayoutParams(Ui.FILL, Ui.WRAP, Gravity.BOTTOM));
        updateEndCard();

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int left;
            int right;
            int topSpace;
            int bottomInset;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets area = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = area.left;
                right = area.right;
                topSpace = area.top;
                bottomInset = area.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                right = insets.getSystemWindowInsetRight();
                topSpace = insets.getSystemWindowInsetTop();
                bottomInset = insets.getSystemWindowInsetBottom();
            }
            int edge = Ui.dp(this, 12);
            top.setPadding(left + edge, topSpace + edge, right + edge, Ui.dp(this, 22));
            bottom.setPadding(left + edge * 2, Ui.dp(this, 22), right + edge * 2, bottomInset + edge);
            FrameLayout.LayoutParams footer = (FrameLayout.LayoutParams) endCard.getLayoutParams();
            footer.setMargins(left + edge, 0, right + edge, bottomInset + edge);
            endCard.setLayoutParams(footer);
            if (canvas != null) applyHorizontalInsets(left, right);
            return insets;
        });
        root.post(() -> applyChrome(false));
    }

    private void applyHorizontalInsets(int left, int right) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) canvas.getLayoutParams();
        if (params.leftMargin == left && params.rightMargin == right) return;
        params.setMargins(left, 0, right, 0);
        canvas.setLayoutParams(params);
    }

    private void load(boolean restart) {
        renderer.execute(() -> {
            MangaDocument document = null;
            try {
                document = MangaDocument.open(chapter.file(db.root), chapter.format);
                Bookmark saved = new Bookmark(chapter.bookmark(db.root));
                MangaDocument loaded = document;
                main.post(() -> {
                    if (destroyed) {
                        close(loaded);
                        return;
                    }
                    bookmark = saved;
                    frontier = Math.min(loaded.pages.length, saved.get().frontier);
                    Bookmark.Position position = restart ? Bookmark.Position.EMPTY : saved.get();
                    canvas = new ReaderCanvas(this, loaded, renderer, position, this);
                    root.addView(canvas, 0, new FrameLayout.LayoutParams(Ui.FILL, Ui.FILL));
                    loading.setVisibility(View.GONE);
                    root.requestApplyInsets();
                    Ui.enter(canvas, 8, 0);
                });
            } catch (IOException | RuntimeException e) {
                if (document != null) close(document);
                main.post(() -> showError(e));
            }
        });
    }

    private static void close(MangaDocument document) {
        try {
            document.close();
        } catch (IOException | RuntimeException e) {
            Log.w("PanelumeReader", "Could not close chapter", e);
        }
    }

    private void showError(Exception e) {
        if (destroyed) return;
        loading.removeAllViews();
        LinearLayout message = Ui.column(this);
        Ui.pad(message, 28, 28, 28, 28);
        TextView title = Ui.title(this, getString(R.string.open_error), 25);
        Ui.add(message, title, Ui.FILL, Ui.WRAP);
        Ui.gap(message, 12);
        Ui.add(message, Ui.label(this, Fault.describe(this, e, R.string.file_error), 15, Ui.MUTED),
            Ui.FILL, Ui.WRAP);
        Ui.gap(message, 20);
        Ui.add(message, Ui.button(this, getString(R.string.back), true, this::finish), Ui.FILL, Ui.WRAP);
        loading.addView(message, new FrameLayout.LayoutParams(Ui.FILL, Ui.WRAP, Gravity.CENTER));
        bottom.setVisibility(View.INVISIBLE);
    }

    @Override
    public void viewport(int page, double fraction, int percent, double bottomAnchor,
                         boolean anyRendered, boolean allRendered, boolean atEnd) {
        if (destroyed || bookmark == null) return;
        page = Math.min(chapter.pages - 1, page);
        if (allRendered && page + fraction <= frontier + .00001) {
            frontier = Math.max(frontier, bottomAnchor);
        }
        bookmark.save(page, fraction, percent, frontier);

        if (page != currentPage || percent != currentPercent) {
            currentPage = page;
            currentPercent = percent;
            counter.setText((page + 1) + " / " + chapter.pages + "   ·   " + (completed ? 100 : percent) + "%");
            meter.setProgress(completed ? 100 : percent);
        }

        boolean visibleEnd = atEnd && (anyRendered || this.atEnd);
        if (visibleEnd != this.atEnd) {
            this.atEnd = visibleEnd;
            // Defer changing layout while the canvas is in the draw traversal.
            main.post(this::applyEndVisibility);
        }
        lastPanelRendered = atEnd && allRendered;
        if (!opened && anyRendered) {
            db.opened(chapter.id);
            opened = true;
            updateTracking();
        } else {
            scheduleCompletion();
        }
    }

    private void applyEndVisibility() {
        if (destroyed) return;
        endCard.setVisibility(atEnd ? View.VISIBLE : View.GONE);
        if (atEnd) Ui.enter(endCard, 10, 0);
        applyChrome(false);
    }

    @Override
    public void tapped() {
        chrome = !chrome;
        applyChrome(true);
    }

    private void applyChrome(boolean animate) {
        if (root == null || destroyed) return;
        boolean showBottom = chrome && !atEnd;
        if (animate) {
            Ui.animateChrome(top, chrome, true);
            Ui.animateChrome(bottom, showBottom, false);
        } else {
            setChromeVisibility(top, chrome);
            setChromeVisibility(bottom, showBottom);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if (chrome) controller.show(WindowInsets.Type.systemBars());
                else controller.hide(WindowInsets.Type.systemBars());
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (!chrome) flags |= View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    private void setChromeVisibility(View view, boolean visible) {
        view.animate().cancel();
        view.setAlpha(visible ? 1f : 0f);
        view.setTranslationY(0);
        view.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
    }

    private boolean eligible() {
        if (!resumed || !opened || !hasWindowFocus()) return false;
        PowerManager power = getSystemService(PowerManager.class);
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        Display display = getWindow().getDecorView().getDisplay();
        return power.isInteractive() && !keyguard.isKeyguardLocked()
            && display != null && display.getState() == Display.STATE_ON;
    }

    private void updateTracking() {
        if (clock == null || destroyed) return;
        clock.update();
        scheduleCompletion();
    }

    private void scheduleCompletion() {
        boolean canFinish = !completed && lastPanelRendered && currentPercent == 100
            && frontier >= chapter.pages && eligible();
        if (!canFinish && completionScheduled) {
            main.removeCallbacks(completeAtEnd);
            completionScheduled = false;
        } else if (canFinish && !completionScheduled) {
            completionScheduled = true;
            main.postDelayed(completeAtEnd, 1100);
        }
    }

    private void completeIfStillAtEnd() {
        completionScheduled = false;
        if (!completed && lastPanelRendered && currentPercent == 100 && frontier >= chapter.pages && eligible()) {
            markRead();
        }
    }

    private void markRead() {
        db.finish(chapter.id);
        completed = true;
        meter.setProgress(100);
        if (currentPage >= 0) counter.setText((currentPage + 1) + " / " + chapter.pages + "   ·   100%");
        updateEndCard();
    }

    private void endAction() {
        if (!completed) {
            if (lastPanelRendered && eligible()) markRead();
            return;
        }
        if (next != null) startActivity(intent(this, next.id, false));
        finish();
    }

    private void updateEndCard() {
        endTitle.setText(completed ? R.string.read_end : R.string.chapter_end);
        endAction.setText(completed
            ? (next == null ? getString(R.string.close_chapter) : getString(R.string.next_chapter))
            : getString(R.string.mark_read));
    }

    private void checkpoint() {
        if (canvas != null) canvas.freeze();
        if (clock != null) clock.stop();
        if (bookmark != null) bookmark.flush();
        main.removeCallbacks(completeAtEnd);
        completionScheduled = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        applyChrome(false);
        updateTracking();
    }

    @Override
    protected void onPause() {
        resumed = false;
        checkpoint();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) updateTracking();
        else checkpoint();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("chrome", chrome);
    }

    @Override
    protected void onDestroy() {
        checkpoint();
        destroyed = true;
        if (receiverRegistered) unregisterReceiver(screen);
        if (canvas != null) canvas.release();
        renderer.shutdown();
        super.onDestroy();
    }
}
