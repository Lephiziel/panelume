package dev.panelume.reader;

import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

public final class PreferencesActivity extends PanelumeActivity {
    static final String SETUP = "first_run";
    private static final int EXPORT_PROGRESS = 27;
    private boolean setup;
    private int step;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setup = getIntent().getBooleanExtra(SETUP, false) && !Prefs.ready(this);
        step = state == null ? 0 : state.getInt("step");
        draw(true);
    }

    private void draw(boolean animate) {
        LinearLayout root = Ui.screen(this);
        if (!setup) {
            Ui.add(root, Ui.toolbar(this, getString(R.string.settings), this::finish), Ui.FILL, Ui.WRAP);
            Ui.rule(root);
        }

        LinearLayout page = Ui.page(this);
        Ui.gap(page, setup ? 28 : 22);
        if (setup) {
            LinearLayout brand = Ui.row(this);
            Ui.add(brand, Ui.mark(this, 9), 42, 42);
            Ui.spacer(brand, 11);
            Ui.add(brand, Ui.title(this, "Panelume", 30), Ui.WRAP, Ui.WRAP);
            Ui.add(page, brand, Ui.FILL, Ui.WRAP);
            Ui.gap(page, 30);
            Ui.add(page, Ui.overline(this, getString(R.string.setup_step, step == 0 ? "01" : "02"), Ui.ACCENT), Ui.FILL, Ui.WRAP);
            Ui.gap(page, 9);
            Ui.add(page, Ui.title(this, getString(step == 0 ? R.string.setup_language : R.string.setup_downloads), 34),
                Ui.FILL, Ui.WRAP);
            Ui.gap(page, 10);
            Ui.add(page, Ui.label(this, getString(step == 0 ? R.string.setup_language_detail : R.string.setup_downloads_detail),
                15, Ui.MUTED), Ui.FILL, Ui.WRAP);
            Ui.gap(page, 23);
            if (step == 0) languageOptions(page);
            else {
                wifi(page);
                Ui.gap(page, 17);
                Ui.add(page, Ui.label(this, getString(R.string.source_notice), 13, Ui.MUTED), Ui.FILL, Ui.WRAP);
            }
        } else {
            Ui.add(page, Ui.title(this, getString(R.string.language), 26), Ui.FILL, Ui.WRAP);
            Ui.gap(page, 14);
            languageOptions(page);
            Ui.gap(page, 25);
            Ui.add(page, Ui.title(this, getString(R.string.downloads_settings), 26), Ui.FILL, Ui.WRAP);
            Ui.gap(page, 14);
            wifi(page);
            Ui.gap(page, 10);
            Ui.add(page, Ui.button(this, getString(R.string.downloads_controls), false,
                () -> startActivity(new Intent(this, DownloadsActivity.class))), Ui.FILL, Ui.WRAP);
            Ui.gap(page, 25);
            Ui.add(page, Ui.title(this, getString(R.string.progress_export_title), 26), Ui.FILL, Ui.WRAP);
            Ui.gap(page, 10);
            Ui.add(page, Ui.label(this, getString(R.string.progress_export_detail), 14, Ui.MUTED), Ui.FILL, Ui.WRAP);
            Ui.gap(page, 8);
            Ui.add(page, Ui.button(this, getString(R.string.progress_export), false, this::chooseExport), Ui.FILL, Ui.WRAP);
            Ui.gap(page, 25);
            Ui.add(page, Ui.title(this, getString(R.string.open_metrics_title), 26), Ui.FILL, Ui.WRAP);
            Ui.gap(page, 8);
            Ui.add(page, Ui.label(this, getString(R.string.open_metrics_detail), 13, Ui.MUTED), Ui.FILL, Ui.WRAP);
            for (int type = 0; type < 3; type++) {
                Ui.gap(page, 8);
                Ui.add(page, Ui.label(this, OpenMetrics.label(this, type), 14, Ui.TEXT), Ui.FILL, Ui.WRAP);
            }
            Ui.gap(page, 25);
            Ui.add(page, Ui.title(this, getString(R.string.available_sources), 26), Ui.FILL, Ui.WRAP);
            Ui.gap(page, 10);
            Ui.add(page, Ui.label(this, CatalogSource.names(), 15, Ui.TEXT), Ui.FILL, Ui.WRAP);
            Ui.gap(page, 8);
            Ui.add(page, Ui.label(this, getString(R.string.about_parser), 13, Ui.MUTED), Ui.FILL, Ui.WRAP);
            Ui.gap(page, 6);
            Ui.add(page, Ui.label(this, getString(R.string.source_notice), 13, Ui.MUTED), Ui.FILL, Ui.WRAP);
        }

        root.addView(Ui.scroller(this, page), new LinearLayout.LayoutParams(Ui.FILL, 0, 1));
        if (setup) {
            LinearLayout footer = Ui.column(this);
            Ui.pad(footer, 22, 10, 22, 14);
            Ui.add(footer, Ui.button(this, getString(step == 0 ? R.string.continue_button : R.string.start_reading),
                true, this::advance), Ui.FILL, Ui.WRAP);
            Ui.add(root, footer, Ui.FILL, Ui.WRAP);
        }
        if (animate) Ui.enter(page, 9, 0);
    }

    private void languageOptions(LinearLayout page) {
        language(page, "en", "English");
        Ui.gap(page, 8);
        language(page, "ru", "Русский");
    }

    private void language(LinearLayout page, String code, String name) {
        boolean selected = Prefs.language(this).equals(code);
        LinearLayout row = Ui.row(this);
        row.setMinimumHeight(Ui.dp(this, 52));
        Ui.pad(row, 14, 8, 14, 8);
        row.setBackground(Ui.card(this, selected ? Ui.RAISED : Ui.SURFACE, 8));
        Ui.weighted(row, Ui.strong(this, name, 16, Ui.TEXT));
        if (selected) Ui.add(row, new Ui.Icon(this, "done", Ui.ACCENT, 20), 28, 28);
        row.setSelected(selected);
        Ui.touchOverlay(row, 8, () -> {
            if (!selected) {
                Prefs.language(this, code);
                recreate();
            }
        });
        Ui.add(page, row, Ui.FILL, Ui.WRAP);
    }

    private void wifi(LinearLayout page) {
        LinearLayout setting = Ui.column(this);
        setting.setBackground(Ui.card(this, Ui.SURFACE, 8));
        Ui.pad(setting, 14, 10, 14, 13);
        Switch toggle = new Switch(this);
        toggle.setText(R.string.unmetered);
        toggle.setTextColor(Ui.TEXT);
        toggle.setTextSize(15);
        toggle.setMinHeight(Ui.dp(this, 44));
        toggle.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[0]},
            new int[]{Ui.ACCENT, Ui.MUTED}));
        toggle.setChecked(Prefs.unmetered(this));
        toggle.setOnCheckedChangeListener((button, checked) -> Prefs.unmetered(this, checked));
        Ui.add(setting, toggle, Ui.FILL, Ui.WRAP);
        Ui.add(setting, Ui.label(this, getString(R.string.unmetered_detail), 13, Ui.MUTED), Ui.FILL, Ui.WRAP);
        Ui.add(page, setting, Ui.FILL, Ui.WRAP);
    }

    private void chooseExport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json").putExtra(Intent.EXTRA_TITLE, "panelume-progress-"
                + java.time.LocalDate.now() + ".json");
        try {
            startActivityForResult(intent, EXPORT_PROGRESS);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_picker, Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != EXPORT_PROGRESS || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri target = data.getData();
        android.content.Context context = getApplicationContext();
        Toast.makeText(this, R.string.progress_export_working, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            int message = R.string.progress_export_success;
            try {
                ProgressExport.save(context, target);
            } catch (java.io.IOException | RuntimeException e) {
                message = R.string.progress_export_failed;
            }
            final int done = message;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                Toast.makeText(Prefs.localized(context), done, Toast.LENGTH_LONG).show());
        }, "panelume-progress-export").start();
    }

    private void advance() {
        if (step == 0) {
            step = 1;
            draw(true);
        } else {
            Prefs.finishSetup(this);
            setResult(RESULT_OK);
            finish();
        }
    }

    @Override public void onBackPressed() {
        if (setup && step > 0) {
            step = 0;
            draw(true);
        } else {
            super.onBackPressed();
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("step", step);
    }
}
