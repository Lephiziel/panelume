package dev.panelume.reader;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.time.ZoneId;
import java.util.function.BooleanSupplier;

final class ReadingClock {
    private static final long CHECKPOINT_MS = 2000;

    private final ShelfDb db;
    private final long chapter;
    private final BooleanSupplier eligible;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean running;
    private long lastUptime;
    private long lastWall;
    private ZoneId zone;
    private final Runnable checkpoint = this::tick;

    ReadingClock(ShelfDb db, long chapter, BooleanSupplier eligible) {
        this.db = db;
        this.chapter = chapter;
        this.eligible = eligible;
    }

    void update() {
        if (!eligible.getAsBoolean()) {
            stop();
            return;
        }
        if (running) return;
        running = true;
        reset(SystemClock.uptimeMillis());
        handler.postDelayed(checkpoint, CHECKPOINT_MS);
    }

    private void tick() {
        if (!running) return;
        if (!eligible.getAsBoolean()) {
            stop();
            return;
        }
        commit();
        handler.postDelayed(checkpoint, CHECKPOINT_MS);
    }

    void stop() {
        if (!running) return;
        running = false;
        handler.removeCallbacks(checkpoint);
        commit();
    }

    private void commit() {
        long now = SystemClock.uptimeMillis();
        long elapsed = now - lastUptime;
        if (elapsed > 0) db.addTime(chapter, ReadingDays.split(lastWall, elapsed, zone));
        reset(now);
    }

    private void reset(long now) {
        lastUptime = now;
        lastWall = System.currentTimeMillis();
        zone = ZoneId.systemDefault();
    }
}
