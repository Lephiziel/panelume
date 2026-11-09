package dev.panelume.reader;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public final class DownloadsActivity extends PanelumeActivity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable heartbeat = this::tick;
    private final List<CatalogQueue.Job> jobs = new ArrayList<>();
    private CatalogQueue queue;
    private Rows rows;
    private TextView note;
    private TextView resume;
    private String last = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        queue = new CatalogQueue(ShelfDb.get(this));
        LinearLayout root = Ui.screen(this);
        Ui.add(root, Ui.toolbar(this, getString(R.string.downloads), this::finish), Ui.FILL, Ui.WRAP);
        Ui.rule(root);

        LinearLayout heading = Ui.page(this);
        Ui.pad(heading, 22, 17, 22, 11);
        Ui.add(heading, Ui.title(this, getString(R.string.download_activity), 31), Ui.FILL, Ui.WRAP);
        Ui.gap(heading, 8);
        note = Ui.label(this, "", 14, Ui.MUTED);
        Ui.add(heading, note, Ui.FILL, Ui.WRAP);
        Ui.gap(heading, 12);
        resume = Ui.button(this, getString(R.string.resume_downloads), true, this::resumeAll);
        Ui.add(heading, resume, Ui.FILL, Ui.WRAP);
        Ui.add(root, heading, Ui.FILL, Ui.WRAP);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setVerticalScrollBarEnabled(false);
        rows = new Rows();
        list.setAdapter(rows);
        root.addView(list, new LinearLayout.LayoutParams(Ui.FILL, 0, 1));
        Ui.enter(heading, 8, 0);
    }

    private void resumeAll() {
        for (CatalogQueue.Job job : jobs) if (job.state == CatalogQueue.FAILED) queue.retry(job.id);
        DownloadService.begin(this, true);
        refresh();
    }

    private void tick() {
        refresh();
        main.postDelayed(heartbeat, 750);
    }

    private void refresh() {
        List<CatalogQueue.Job> current = queue.jobs(-1);
        StringBuilder state = new StringBuilder().append(DownloadService.active).append(DownloadService.allowBulk(this));
        int resumable = 0;
        for (CatalogQueue.Job job : current) {
            state.append('|').append(job.id).append('/').append(DownloadStatus.label(this, job));
            if (!CatalogQueue.ACCESS.equals(job.error)) resumable++;
        }
        String key = state.toString();
        if (key.equals(last)) return;
        last = key;
        jobs.clear();
        jobs.addAll(current);
        note.setText(jobs.isEmpty() ? getString(R.string.no_downloads) + "\n" + getString(R.string.no_downloads_detail)
            : getString(R.string.series_download_count, jobs.size()));
        resume.setVisibility(resumable > 0 && !DownloadService.active ? View.VISIBLE : View.GONE);
        rows.notifyDataSetChanged();
    }

    private void menu(View anchor, CatalogQueue.Job job) {
        PopupMenu menu = new PopupMenu(this, anchor);
        if (job.state == CatalogQueue.FAILED) {
            if (CatalogQueue.ACCESS.equals(job.error)) {
                CatalogSource source = CatalogSource.named(job.title.provider);
                if (source != null) menu.getMenu().add(getString(R.string.external_open, source.name()))
                    .setOnMenuItemClickListener(v -> {
                        CatalogActivity.external(this, source, job.episode.url);
                        return true;
                    });
            } else {
                menu.getMenu().add(R.string.retry_download).setOnMenuItemClickListener(v -> {
                    queue.retry(job.id);
                    DownloadService.begin(this, false);
                    refresh();
                    return true;
                });
            }
        }
        menu.getMenu().add(R.string.cancel_download).setOnMenuItemClickListener(v -> {
            queue.cancel(job.id);
            refresh();
            return true;
        });
        menu.show();
    }

    @Override protected void onResume() {
        super.onResume();
        tick();
    }

    @Override protected void onPause() {
        main.removeCallbacks(heartbeat);
        super.onPause();
    }

    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return jobs.size(); }
        @Override public CatalogQueue.Job getItem(int position) { return jobs.get(position); }
        @Override public long getItemId(int position) { return getItem(position).id; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            CatalogQueue.Job job = getItem(position);
            LinearLayout row = Ui.row(DownloadsActivity.this);
            Ui.pad(row, 22, 7, 13, 7);
            LinearLayout labels = Ui.column(DownloadsActivity.this);
            TextView title = Ui.strong(DownloadsActivity.this, job.title.name, 15, Ui.TEXT);
            Ui.ellipsize(title, 1);
            Ui.add(labels, title, Ui.FILL, Ui.WRAP);
            Ui.gap(labels, 4);
            String subtitle = CatalogActivity.label(DownloadsActivity.this, job.episode);
            TextView chapter = Ui.label(DownloadsActivity.this, subtitle + " · " + CatalogSource.label(job.title.provider),
                12, Ui.MUTED);
            Ui.ellipsize(chapter, 1);
            Ui.add(labels, chapter, Ui.FILL, Ui.WRAP);
            Ui.gap(labels, 5);
            Ui.add(labels, Ui.label(DownloadsActivity.this, DownloadStatus.label(DownloadsActivity.this, job), 12,
                job.state == CatalogQueue.FAILED ? Ui.ERROR : Ui.ACCENT), Ui.FILL, Ui.WRAP);
            Ui.weighted(row, labels);
            View options = Ui.iconButton(DownloadsActivity.this, "more", getString(R.string.chapter_actions),
                Ui.BG, Ui.MUTED, () -> {});
            options.setOnClickListener(v -> menu(options, job));
            Ui.add(row, options, 48, 48);
            LinearLayout cell = Ui.column(DownloadsActivity.this);
            Ui.add(cell, row, Ui.FILL, Ui.WRAP);
            Ui.rule(cell);
            return cell;
        }
    }
}
