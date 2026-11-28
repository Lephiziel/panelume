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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ReaderActivity extends PanelumeActivity implements ReaderCanvas.Observer {
    private static final String CHAPTER = "chapter";
    private static final String RESTART = "restart";
    private static final String REQUESTED_AT = "requested_at";
    private static final String NETWORK_OPEN = "network_open";
    private long requestedAt;
    private boolean networkOpen;
    private boolean timingRecorded;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService renderer = Executors.newSingleThreadExecutor();
    private final ExecutorService preparation = Executors.newSingleThreadExecutor();
    private final Runnable completeAtEnd = this::completeIfStillAtEnd;
    private final Runnable retryPreparation = this::maybePrepare;

    private ShelfDb db;
    private ShelfDb.Chapter chapter;
    private CatalogSource.Title catalogTitle;
    private NextChapter.Successor successor;
    private boolean preparationStarted;
    private boolean lookupInFlight;
    private boolean currentViewportRendered;
    private long nextPreparationCheck;
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
        return intent(context, chapter, restart, android.os.SystemClock.elapsedRealtime(), false);
    }

    static Intent intent(Context context, long chapter, boolean restart, long requestedAt, boolean networkOpen) {
        ShelfDb shelf = ShelfDb.get(context);
        ShelfDb.Chapter stored = shelf.chapter(chapter);
        if (stored != null && !stored.available(shelf.root) && !stored.provider.isEmpty()) {
            CatalogSource.Title title = new CatalogQueue(shelf).title(stored.seriesId);
            if (title != null) return CatalogActivity.redownload(context,title,stored.catalogId,restart);
        }
        return new Intent(context, ReaderActivity.class).putExtra(CHAPTER, chapter).putExtra(RESTART, restart)
            .putExtra(REQUESTED_AT, requestedAt).putExtra(NETWORK_OPEN, networkOpen);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        db = ShelfDb.get(this);
        chapter = db.acquireReader(getIntent().getLongExtra(CHAPTER, -1));
        if (chapter == null) {
            finish();
            return;
        }
        requestedAt = getIntent().getLongExtra(REQUESTED_AT, android.os.SystemClock.elapsedRealtime());
        networkOpen = getIntent().getBooleanExtra(NETWORK_OPEN, false);
        // A recreated reader isn't a new opening sample.
        timingRecorded = state != null;
        if (!chapter.provider.isEmpty() && !chapter.catalogId.isEmpty())
            catalogTitle = new CatalogQueue(db).title(chapter.seriesId);
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
        if (catalogTitle!=null) {
            Ui.add(toolbar, Ui.iconButton(this,"chapters",getString(R.string.chapters),0xbf242522,Ui.TEXT,
                this::openChapterList),48,48);
        }
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
                document = MangaDocument.openRegistered(chapter.file(db.root),chapter.format,chapter.digest,chapter.pages);
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
        if (!timingRecorded && anyRendered && resumed && hasWindowFocus()) {
            timingRecorded = true;
            OpenMetrics.record(this, networkOpen ? OpenMetrics.NETWORK
                : chapter.provider.isEmpty() ? OpenMetrics.LOCAL : OpenMetrics.CACHED, requestedAt);
        }
        if (!opened && anyRendered) {
            db.opened(chapter.id);
            opened = true;
            updateTracking();
        } else {
            scheduleCompletion();
        }
        currentViewportRendered = anyRendered;
        maybePrepare();
    }

    private boolean wantsPreparation() {
        return Prefs.prepareNext(this) && NextChapter.readyToPrepare(currentPercent,currentViewportRendered);
    }

    private boolean wantsLookup() {
        return wantsPreparation() || (completed && atEnd);
    }

    private void maybePrepare() {
        if (destroyed || catalogTitle==null || lookupInFlight || !eligible() || !wantsLookup()) return;
        if (successor!=null && (preparationStarted || !wantsPreparation()
                || successor.episode==null || successor.episode.external)) return;
        long now=android.os.SystemClock.elapsedRealtime();
        if (now<nextPreparationCheck) {
            // A pause removes the timer; resuming during cooldown must re-arm it.
            main.removeCallbacks(retryPreparation);
            main.postDelayed(retryPreparation,nextPreparationCheck-now+50);
            return;
        }
        nextPreparationCheck=now+5000;
        // Never start a network operation from the canvas draw traversal.
        main.post(() -> {
            if (destroyed || lookupInFlight || !eligible() || !wantsLookup()) return;
            if (successor!=null) {
                if (!preparationStarted && wantsPreparation()) queueSuccessor(successor);
                return;
            }
            // Navigation can resolve the next catalogue entry even with auto-download
            // disabled or bulk downloads restricted to Wi-Fi.
            if (!catalogueNetworkAvailable()) {
                schedulePreparationRetry(15_000);
                return;
            }
            CatalogSource source=CatalogSource.named(catalogTitle.provider);
            if (source==null) return;
            lookupInFlight=true;
            CatalogSource.Title title=catalogTitle;
            String current=chapter.catalogId;
            List<String> preferred=new CatalogQueue(db).preferredEpisodeIds(chapter.seriesId,current);
            preparation.execute(() -> {
                NextChapter.Successor found;
                try { found=NextChapter.after(source.episodes(title,preferred),current); }
                catch (IOException | RuntimeException error) { found=null; }
                NextChapter.Successor result=found;
                main.post(() -> {
                    lookupInFlight=false;
                    if (destroyed) return;
                    if (result==null) {
                        schedulePreparationRetry(30_000);
                        return;
                    }
                    successor=result;
                    updateEndCard();
                    if (result.episode!=null && !result.episode.external && wantsPreparation())
                        queueSuccessor(result);
                });
            });
        });
    }

    private void queueSuccessor(NextChapter.Successor found) {
        if (found==null || found.episode==null || found.episode.external) return;
        if (!wantsPreparation() || !NextChapter.canPrepare(currentPercent,eligible(),Prefs.prepareNext(this),preparationNetworkAllowed())) {
            if (wantsPreparation())
                schedulePreparationRetry(15_000);
            else preparationStarted=false;
            return;
        }
        try {
            int result=new CatalogQueue(db).enqueuePreparation(chapter.id,catalogTitle,found.episode);
            if (result==CatalogQueue.PREPARATION_QUEUED) {
                preparationStarted=true;
                DownloadService.begin(this,false);
            } else if (result==CatalogQueue.PREPARATION_BUSY) {
                schedulePreparationRetry(15_000);
            } else {
                // Already downloaded, already queued/failed, or intentionally evicted:
                // do not silently retry or duplicate it.
                preparationStarted=true;
            }
        } catch (RuntimeException error) {
            Log.w("PanelumeReader","Next chapter preparation not queued");
            schedulePreparationRetry(30_000);
        }
    }

    private void schedulePreparationRetry(long delay) {
        if (destroyed) return;
        preparationStarted=false;
        nextPreparationCheck=android.os.SystemClock.elapsedRealtime()+delay;
        main.removeCallbacks(retryPreparation);
        main.postDelayed(retryPreparation,delay+250);
    }

    private boolean catalogueNetworkAvailable() {
        android.net.ConnectivityManager manager=getSystemService(android.net.ConnectivityManager.class);
        return manager!=null && manager.getActiveNetwork()!=null;
    }

    private boolean preparationNetworkAllowed() {
        return catalogueNetworkAvailable() && DownloadService.allowBulk(this);
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
        maybePrepare();
    }

    private void openChapterList() {
        if (catalogTitle==null) return;
        startActivity(CatalogActivity.chapterListIntent(this,catalogTitle));
        finish();
    }

    private void endAction() {
        if (!completed) {
            if (lastPanelRendered && eligible()) markRead();
            return;
        }
        ShelfDb.Chapter saved=nextSaved();
        if (saved!=null) startActivity(intent(this,saved.id,false));
        else if (catalogTitle!=null && (successor==null || successor.episode!=null)) {
            if (successor!=null && !successor.episode.external) {
                startActivity(CatalogActivity.redownload(this,catalogTitle,successor.episode.id,false));
            } else startActivity(CatalogActivity.intent(this,catalogTitle));
        }
        finish();
    }

    private ShelfDb.Chapter nextSaved() {
        if (successor==null) {
            ShelfDb.Chapter next=db.next(chapter);
            return next!=null && next.available(db.root) ? next : null;
        }
        if (successor.episode==null || successor.episode.external) return null;
        for (ShelfDb.Chapter saved:db.chapters(chapter.seriesId))
            if (successor.episode.id.equals(saved.catalogId) && saved.available(db.root)) return saved;
        return null;
    }

    private void updateEndCard() {
        endTitle.setText(completed ? R.string.read_end : R.string.chapter_end);
        int action=R.string.mark_read;
        if (completed) {
            ShelfDb.Chapter saved=nextSaved();
            if (saved!=null) action=catalogTitle!=null && successor==null
                ? R.string.next_saved_chapter : R.string.next_chapter;
            else if (catalogTitle!=null && successor==null) action=R.string.next_open_catalogue;
            else if (catalogTitle!=null && successor.episode!=null) action=successor.episode.external
                ? R.string.next_open_catalogue : R.string.next_chapter;
            else action=R.string.close_chapter;
        }
        endAction.setText(action);
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
        maybePrepare();
    }

    @Override
    protected void onPause() {
        resumed = false;
        main.removeCallbacks(retryPreparation);
        checkpoint();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) { updateTracking(); maybePrepare(); }
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
        main.removeCallbacks(retryPreparation);
        if (receiverRegistered) unregisterReceiver(screen);
        if (canvas != null) canvas.release();
        if (chapter != null) {
            // Release after queued rendering work and bookmark flush have completed.
            long id = chapter.id;
            renderer.execute(() -> db.releaseReader(id));
        }
        preparation.shutdownNow();
        renderer.shutdown();
        super.onDestroy();
    }
}
