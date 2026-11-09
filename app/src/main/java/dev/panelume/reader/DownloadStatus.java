package dev.panelume.reader;

import android.content.Context;

final class DownloadStatus {
    private DownloadStatus() {}

    static String label(Context context, CatalogQueue.Job job) {
        if (job.state == CatalogQueue.FAILED) {
            if (CatalogQueue.ACCESS.equals(job.error)) return context.getString(R.string.access_required);
            if (CatalogQueue.LIMIT.equals(job.error)) return context.getString(R.string.source_limit);
            if (CatalogQueue.NETWORK.equals(job.error)) return context.getString(R.string.network_error);
            return context.getString(R.string.download_failed);
        }
        if (job.state == CatalogQueue.RUNNING && DownloadService.active) {
            return job.total > 0 ? context.getString(R.string.download_progress, job.current, job.total)
                : context.getString(R.string.download_opening);
        }
        if (job.bulk && !DownloadService.allowBulk(context)) return context.getString(R.string.waiting_network);
        return context.getString(DownloadService.active ? R.string.download_waiting : R.string.download_paused);
    }
}
