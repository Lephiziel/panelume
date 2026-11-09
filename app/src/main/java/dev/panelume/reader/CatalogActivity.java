package dev.panelume.reader;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CatalogActivity extends PanelumeActivity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<CatalogSource.Episode> episodes = new ArrayList<>();
    private final Map<String, ShelfDb.Chapter> saved = new LinkedHashMap<>();
    private final Map<String, CatalogQueue.Job> jobs = new LinkedHashMap<>();
    private final Runnable heartbeat = this::tick;
    private ShelfDb shelf;
    private CatalogQueue queue;
    private CatalogSource.Title title;
    private CatalogSource source;
    private Rows rows;
    private TextView progress;
    private TextView download;
    private TextView notice;
    private TextView cancel;
    private TextView retry;
    private String awaiting = "";
    private String stateKey = "";
    private long localId;
    private boolean loading;
    private boolean feedLoaded;
    private boolean resumed;
    private boolean closed;
    private String problem = "";

    static Intent intent(Context context, CatalogSource.Title title) {
        return new Intent(context, CatalogActivity.class)
            .putExtra("provider", title.provider).putExtra("id", title.id).putExtra("name", title.name)
            .putExtra("url", title.url).putExtra("cover", title.cover).putExtra("description", title.description);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = getIntent();
        title = new CatalogSource.Title(value(intent, "provider"), value(intent, "id"), value(intent, "name"),
            value(intent, "url"), value(intent, "cover"), value(intent, "description"));
        source = CatalogSource.named(title.provider);
        if (source == null || title.id.isEmpty()) {
            finish();
            return;
        }
        shelf = ShelfDb.get(this);
        queue = new CatalogQueue(shelf);
        localId = queue.seriesId(title);
        if (state != null) awaiting = state.getString("awaiting", "");
        build();
        load();
    }

    private static String value(Intent intent, String key) {
        String value = intent.getStringExtra(key);
        return value == null ? "" : value;
    }

    private void build() {
        LinearLayout root = Ui.screen(this);
        LinearLayout toolbar = Ui.toolbar(this, source.name(), this::finish);
        Ui.add(toolbar, Ui.iconButton(this, "download", getString(R.string.downloads), Ui.BG, Ui.ACCENT,
            () -> startActivity(new Intent(this, DownloadsActivity.class))), 48, 48);
        Ui.add(toolbar, Ui.iconButton(this, "external", getString(R.string.external_open, source.name()), Ui.BG, Ui.MUTED,
            () -> external(this, source, title.url)), 48, 48);
        Ui.add(root, toolbar, Ui.FILL, Ui.WRAP);
        Ui.rule(root);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setVerticalScrollBarEnabled(false);
        list.addHeaderView(header(), null, false);
        rows = new Rows();
        list.setAdapter(rows);
        list.setOnItemClickListener((parent, view, position, id) -> {
            int index = position - list.getHeaderViewsCount();
            if (index >= 0 && index < episodes.size()) open(episodes.get(index));
        });
        root.addView(list, new LinearLayout.LayoutParams(Ui.FILL, 0, 1));
        Ui.enter(list, 8, 0);
    }

    private View header() {
        LinearLayout page = Ui.page(this);
        Ui.pad(page, 22, 18, 22, 8);
        LinearLayout hero = Ui.row(this);
        hero.setGravity(Gravity.TOP);
        Ui.add(hero, RemoteCovers.poster(this, title, 6), 90, 123);
        Ui.spacer(hero, 15);
        LinearLayout text = Ui.column(this);
        Ui.add(text, Ui.overline(this, source.name(), Ui.ACCENT), Ui.FILL, Ui.WRAP);
        Ui.gap(text, 8);
        TextView name = Ui.title(this, title.name, 30);
        Ui.ellipsize(name, 4);
        Ui.add(text, name, Ui.FILL, Ui.WRAP);
        Ui.gap(text, 8);
        Ui.add(text, Ui.label(this, getString(R.string.source_language), 13, Ui.MUTED), Ui.FILL, Ui.WRAP);
        Ui.weighted(hero, text);
        Ui.add(page, hero, Ui.FILL, Ui.WRAP);
        if (!title.description.isEmpty()) {
            Ui.gap(page, 14);
            TextView synopsis = Ui.label(this, title.description, 14, Ui.MUTED);
            Ui.ellipsize(synopsis, 3);
            synopsis.setOnClickListener(v -> synopsis.setMaxLines(synopsis.getMaxLines() == 3 ? 16 : 3));
            Ui.add(page, synopsis, Ui.FILL, Ui.WRAP);
        }
        Ui.gap(page, 17);
        download = Ui.button(this, getString(R.string.download_all), true, this::downloadAll);
        Ui.add(page, download, Ui.FILL, Ui.WRAP);
        Ui.gap(page, 8);
        progress = Ui.label(this, "", 12, Ui.MUTED);
        Ui.add(page, progress, Ui.FILL, Ui.WRAP);
        cancel = Ui.button(this, getString(R.string.cancel_remaining), false, () -> {
            queue.cancelSeries(localId);
            refresh(true);
        });
        Ui.add(page, cancel, Ui.FILL, Ui.WRAP);
        Ui.gap(page, 20);
        Ui.rule(page);
        Ui.gap(page, 13);
        Ui.add(page, Ui.title(this, getString(R.string.chapters), 25), Ui.FILL, Ui.WRAP);
        Ui.gap(page, 8);
        notice = Ui.label(this, "", 14, Ui.MUTED);
        Ui.add(page, notice, Ui.FILL, Ui.WRAP);
        retry = Ui.button(this, getString(R.string.retry), false, this::load);
        Ui.add(page, retry, Ui.FILL, Ui.WRAP);
        return page;
    }

    private void load() {
        if (loading || closed) return;
        loading = true;
        problem = "";
        refresh(true);
        worker.execute(() -> {
            List<CatalogSource.Episode> found;
            String error = "";
            try {
                found = source.episodes(title);
            } catch (IOException | RuntimeException e) {
                error = Fault.describe(this, e, R.string.source_error);
                found = Collections.emptyList();
            }
            List<CatalogSource.Episode> feed = found;
            String failure = error;
            main.post(() -> {
                if (closed) return;
                loading = false;
                feedLoaded = failure.isEmpty();
                problem = failure;
                episodes.clear();
                episodes.addAll(feed);
                refresh(true);
            });
        });
    }

    private void tick() {
        refresh(false);
        if (resumed && !closed) main.postDelayed(heartbeat, 750);
    }

    private void refresh(boolean force) {
        if (source == null || closed) return;
        saved.clear();
        jobs.clear();
        if (localId > 0) {
            for (CatalogQueue.Job job : queue.jobs(localId)) jobs.put(job.episode.id, job);
            for (ShelfDb.Chapter chapter : shelf.chapters(localId)) saved.put(chapter.catalogId, chapter);
        }
        if (!feedLoaded && !loading) {
            episodes.clear();
            for (ShelfDb.Chapter chapter : saved.values()) {
                episodes.add(new CatalogSource.Episode(chapter.catalogId, chapter.number, chapter.caption,
                    title.url, chapter.credit, false));
            }
        }
        StringBuilder snapshot = new StringBuilder();
        for (ShelfDb.Chapter chapter : saved.values()) snapshot.append(chapter.id).append('/').append(chapter.opened)
            .append('/').append(chapter.done).append(';');
        for (CatalogQueue.Job job : jobs.values()) snapshot.append(job.id).append('/').append(job.state).append('/')
            .append(job.current).append('/').append(job.total).append('/').append(job.error).append(';');
        snapshot.append(DownloadService.active).append(DownloadService.allowBulk(this));
        String key = snapshot.toString();
        if (force || !key.equals(stateKey)) {
            stateKey = key;
            updateHeader();
            rows.notifyDataSetChanged();
        }
        if (resumed && !awaiting.isEmpty()) {
            ShelfDb.Chapter chapter = saved.get(awaiting);
            CatalogQueue.Job job = jobs.get(awaiting);
            if (chapter != null) {
                awaiting = "";
                startActivity(ReaderActivity.intent(this, chapter.id, false));
            } else if (job == null || job.state == CatalogQueue.FAILED) {
                awaiting = "";
            }
        }
    }

    private void updateHeader() {
        int eligible = 0;
        int count = 0;
        int queued = 0;
        int missing = 0;
        for (CatalogSource.Episode episode : episodes) {
            CatalogQueue.Job job = jobs.get(episode.id);
            if (saved.containsKey(episode.id)) {
                count++;
                eligible++;
                continue;
            }
            if (episode.external || (job != null && CatalogQueue.ACCESS.equals(job.error))) continue;
            eligible++;
            if (job != null && job.state != CatalogQueue.FAILED) queued++;
            else missing++;
        }
        boolean canDownload = feedLoaded && missing > 0;
        download.setEnabled(canDownload);
        download.setAlpha(canDownload ? 1f : .55f);
        download.setVisibility(feedLoaded && eligible > 0 ? View.VISIBLE : View.GONE);
        download.setText(eligible > 0 && eligible == count ? R.string.all_offline
            : count > 0 ? R.string.download_missing : R.string.download_all);
        String summary = eligible > 0 ? getString(R.string.downloaded_count, count, eligible) : "";
        if (queued > 0) summary += " · " + getString(R.string.series_download_count, queued);
        progress.setText(summary);
        progress.setVisibility(summary.isEmpty() ? View.GONE : View.VISIBLE);
        cancel.setVisibility(queued > 0 ? View.VISIBLE : View.GONE);

        String state = loading ? getString(R.string.loading_chapters) : problem;
        if (feedLoaded && episodes.isEmpty()) state = getString(R.string.no_online_chapters);
        notice.setText(state);
        notice.setVisibility(state.isEmpty() ? View.GONE : View.VISIBLE);
        retry.setVisibility(problem.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void open(CatalogSource.Episode episode) {
        ShelfDb.Chapter stored = saved.get(episode.id);
        if (stored != null) {
            startActivity(ReaderActivity.intent(this, stored.id, false));
            return;
        }
        CatalogQueue.Job job = jobs.get(episode.id);
        if (episode.external || (job != null && CatalogQueue.ACCESS.equals(job.error))) {
            external(this, source, episode.url);
            return;
        }
        awaiting = episode.id;
        localId = queue.enqueue(title, Collections.singletonList(episode), false);
        DownloadService.begin(this, false);
        refresh(true);
    }

    private void downloadAll() {
        localId = queue.enqueue(title, episodes, true);
        DownloadService.begin(this, true);
        refresh(true);
    }

    static void external(Context context, CatalogSource source, String address) {
        try {
            CatalogHttp.check(address, source::accepts);
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(address)));
        } catch (IOException | android.content.ActivityNotFoundException e) {
            Toast.makeText(context, R.string.source_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    static String label(Context context, CatalogSource.Episode episode) {
        if (!episode.name.isEmpty()) return episode.name;
        String number = episode.number.isEmpty() ? episode.id : episode.number;
        return context.getString(R.string.chapter_label, number);
    }

    @Override protected void onResume() {
        super.onResume();
        if (source == null || isFinishing()) return;
        resumed = true;
        tick();
    }

    @Override protected void onPause() {
        resumed = false;
        main.removeCallbacks(heartbeat);
        super.onPause();
    }

    @Override protected void onDestroy() {
        closed = true;
        worker.shutdownNow();
        main.removeCallbacks(heartbeat);
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("awaiting", awaiting);
    }

    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return episodes.size(); }
        @Override public CatalogSource.Episode getItem(int position) { return episodes.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            CatalogSource.Episode episode = getItem(position);
            ShelfDb.Chapter stored = saved.get(episode.id);
            CatalogQueue.Job job = jobs.get(episode.id);
            boolean external = stored == null && (episode.external || (job != null && CatalogQueue.ACCESS.equals(job.error)));
            LinearLayout row = Ui.row(CatalogActivity.this);
            Ui.pad(row, 22, 11, 18, 11);
            row.setMinimumHeight(Ui.dp(CatalogActivity.this, 69));
            TextView number = Ui.strong(CatalogActivity.this, episode.number.isEmpty() ? "·" : episode.number,
                14, Ui.ACCENT);
            Ui.ellipsize(number, 1);
            number.setMinWidth(Ui.dp(CatalogActivity.this, 36));
            Ui.add(row, number, Ui.WRAP, Ui.WRAP);
            Ui.spacer(row, 10);
            LinearLayout text = Ui.column(CatalogActivity.this);
            TextView name = Ui.strong(CatalogActivity.this, label(CatalogActivity.this, episode), 15, Ui.TEXT);
            Ui.ellipsize(name, 2);
            Ui.add(text, name, Ui.FILL, Ui.WRAP);
            Ui.gap(text, 4);
            String status = external ? getString(R.string.external_open, source.name())
                : stored != null ? getString(stored.done ? R.string.read : stored.opened > 0 ? R.string.in_progress
                    : R.string.offline_available) : job != null ? DownloadStatus.label(CatalogActivity.this, job) : "";
            if (!episode.credit.isEmpty()) status += (status.isEmpty() ? "" : " · ") + episode.credit;
            if (!status.isEmpty()) Ui.add(text, Ui.label(CatalogActivity.this, status, 12,
                stored != null && stored.done ? Ui.MINT : Ui.MUTED), Ui.FILL, Ui.WRAP);
            Ui.weighted(row, text);
            Ui.add(row, new Ui.Icon(CatalogActivity.this, external ? "external" : stored != null ? "done" : "next",
                stored != null ? Ui.MINT : Ui.SOFT, 18), 30, 36);
            LinearLayout cell = Ui.column(CatalogActivity.this);
            Ui.add(cell, row, Ui.FILL, Ui.WRAP);
            Ui.rule(cell);
            return cell;
        }
    }
}
