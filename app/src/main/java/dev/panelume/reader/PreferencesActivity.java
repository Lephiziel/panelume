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
    private static final int IMPORT_PROGRESS = 28;
    private static final int EXPORT_LIBRARY = 29, IMPORT_LIBRARY = 30;
    private boolean importing;
    private boolean libraryBusy;
    private boolean storageLoading;
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
            Ui.gap(page, 8);
            Ui.add(page, Ui.button(this, getString(R.string.offline_storage_title), false, this::showStorage), Ui.FILL, Ui.WRAP);
            Ui.gap(page, 25);
            Ui.add(page, Ui.title(this, getString(R.string.progress_export_title), 26), Ui.FILL, Ui.WRAP);
            Ui.gap(page, 10);
            Ui.add(page, Ui.label(this, getString(R.string.progress_export_detail), 14, Ui.MUTED), Ui.FILL, Ui.WRAP);
            Ui.gap(page, 8);
            Ui.add(page, Ui.button(this, getString(R.string.progress_export), false, this::chooseExport), Ui.FILL, Ui.WRAP);
            Ui.gap(page, 8);
            Ui.add(page, Ui.button(this, getString(R.string.progress_import), false, this::chooseImport), Ui.FILL, Ui.WRAP);
            Ui.gap(page, 14);
            Ui.add(page, Ui.label(this, getString(R.string.library_backup_detail), 13, Ui.MUTED), Ui.FILL, Ui.WRAP);
            Ui.gap(page, 8);
            Ui.add(page, Ui.button(this, getString(R.string.library_backup_export), false, () -> chooseLibrary(false)), Ui.FILL, Ui.WRAP);
            Ui.gap(page, 8);
            Ui.add(page, Ui.button(this, getString(R.string.library_backup_import), false, () -> chooseLibrary(true)), Ui.FILL, Ui.WRAP);
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

    private void showStorage() {
        if (importing || storageLoading) return;
        storageLoading = true;
        Toast.makeText(this, R.string.offline_storage_loading, Toast.LENGTH_SHORT).show();
        android.content.Context context = getApplicationContext();
        new Thread(() -> {
            try {
                OfflineStorage.Report report = OfflineStorage.scan(ShelfDb.get(context));
                runOnUiThread(() -> {
                    storageLoading = false;
                    if (isFinishing() || isDestroyed()) return;
                    StringBuilder message = new StringBuilder(getString(R.string.offline_storage_summary,
                        report.ready, report.missing, android.text.format.Formatter.formatFileSize(this, report.bytes)));
                    message.append("\n\n").append(getString(R.string.offline_storage_explanation));
                    for (OfflineStorage.Entry entry : report.series) {
                        message.append("\n\n").append(entry.title);
                        if (!entry.provider.isEmpty()) message.append(" · ").append(CatalogSource.label(entry.provider));
                        message.append("\n").append(getString(R.string.offline_storage_summary,
                            entry.ready, entry.missing, android.text.format.Formatter.formatFileSize(this, entry.bytes)));
                    }
                    new android.app.AlertDialog.Builder(this).setTitle(R.string.offline_storage_title)
                        .setMessage(message.toString()).setPositiveButton(android.R.string.ok, null).show();
                });
            } catch (RuntimeException error) {
                runOnUiThread(() -> {
                    storageLoading = false;
                    if (!isFinishing() && !isDestroyed()) Toast.makeText(this, R.string.offline_storage_failed, Toast.LENGTH_LONG).show();
                });
            }
        }, "panelume-storage-report").start();
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
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request == EXPORT_LIBRARY || request == IMPORT_LIBRARY) {
            if (request == EXPORT_LIBRARY) exportLibrary(data.getData());
            else previewLibrary(data.getData());
            return;
        }
        if (request == IMPORT_PROGRESS) {
            previewImport(data.getData());
            return;
        }
        if (request != EXPORT_PROGRESS) return;
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

    private void chooseLibrary(boolean restore) {
        if (libraryBusy || importing) return;
        Intent intent = new Intent(restore ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip");
        if (!restore) intent.putExtra(Intent.EXTRA_TITLE, "panelume-library-" + java.time.LocalDate.now() + ".zip");
        try {
            startActivityForResult(intent, restore ? IMPORT_LIBRARY : EXPORT_LIBRARY);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_picker, Toast.LENGTH_LONG).show();
        }
    }

    private void exportLibrary(Uri uri) {
        if (libraryBusy || importing) return;
        libraryBusy = true;
        android.content.Context context = getApplicationContext();
        Toast.makeText(this, R.string.library_backup_working, Toast.LENGTH_LONG).show();
        new Thread(() -> {
            int message = R.string.library_backup_saved;
            try (java.io.OutputStream out = context.getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new java.io.IOException("No destination");
                LibraryBackup.write(context, ShelfDb.get(context), out);
            } catch (java.io.IOException | RuntimeException error) {
                message = R.string.library_backup_failed;
            }
            final int done = message;
            runOnUiThread(() -> {
                libraryBusy = false;
                if (!isFinishing() && !isDestroyed()) Toast.makeText(this, done, Toast.LENGTH_LONG).show();
            });
        }, "panelume-library-export").start();
    }

    private void previewLibrary(Uri uri) {
        if (libraryBusy || importing) return;
        libraryBusy = true;
        android.content.Context context = getApplicationContext();
        new Thread(() -> {
            try {
                if (!LibraryBackup.empty(ShelfDb.get(context))) throw new java.io.IOException("Library not empty");
                LibraryBackup.Preview preview;
                try (java.io.InputStream in = context.getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new java.io.IOException("No input");
                    preview = LibraryBackup.preview(in);
                }
                runOnUiThread(() -> {
                    libraryBusy = false;
                    if (isFinishing() || isDestroyed()) return;
                    showLibraryPreview(uri, preview);
                });
            } catch (java.io.IOException | RuntimeException error) {
                runOnUiThread(() -> {
                    libraryBusy = false;
                    if (!isFinishing() && !isDestroyed()) Toast.makeText(this, R.string.library_backup_rejected, Toast.LENGTH_LONG).show();
                });
            }
        }, "panelume-library-preview").start();
    }

    private void showLibraryPreview(Uri uri, LibraryBackup.Preview preview) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        Ui.pad(content, 22, 12, 22, 8);
        TextView summary = new TextView(this);
        summary.setText(getString(R.string.library_backup_preview, preview.series, preview.chapters));
        content.addView(summary);
        BackupSettings settings = preview.settings;
        int[] selected = {0};
        if (settings.available() != 0) {
            TextView hint = new TextView(this);
            hint.setText(R.string.library_settings_optional);
            Ui.pad(hint, 0, 14, 0, 4);
            content.addView(hint);
        }
        if (settings.language != null) backupOption(content, selected, BackupSettings.LANGUAGE,
            getString(R.string.library_settings_language, "ru".equals(settings.language) ? "Русский" : "English"));
        if (settings.unmetered != null) backupOption(content, selected, BackupSettings.NETWORK,
            getString(R.string.library_settings_network, getString(settings.unmetered
                ? R.string.library_settings_wifi : R.string.library_settings_mobile)));
        if (settings.lastSearch != null) backupOption(content, selected, BackupSettings.SEARCH,
            getString(R.string.library_settings_search, settings.lastSearch.isEmpty()
                ? getString(R.string.library_settings_empty_search) : settings.lastSearch));
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(content);
        new android.app.AlertDialog.Builder(this).setTitle(R.string.library_backup_import)
            .setView(scroll).setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.library_backup_restore, (dialog, which) ->
                restoreLibrary(uri, preview, selected[0])).show();
    }

    private void backupOption(LinearLayout content, int[] selected, int bit, String label) {
        android.widget.CheckBox box = new android.widget.CheckBox(this);
        box.setText(label);
        box.setChecked(false);
        box.setOnCheckedChangeListener((button, checked) -> {
            if (checked) selected[0] |= bit;
            else selected[0] &= ~bit;
        });
        content.addView(box);
    }

    private void restoreLibrary(Uri uri, LibraryBackup.Preview preview, int selected) {
        if (libraryBusy || importing) return;
        libraryBusy = true;
        android.content.Context context = getApplicationContext();
        Toast.makeText(this, R.string.library_backup_working, Toast.LENGTH_LONG).show();
        new Thread(() -> {
            int message = R.string.library_backup_restored;
            try (java.io.InputStream in = context.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new java.io.IOException("No input");
                if (!LibraryBackup.restoreWithSettings(context, ShelfDb.get(context), in, preview, selected)) {
                    message = R.string.library_settings_unconfirmed;
                }
            } catch (java.io.IOException | RuntimeException error) {
                message = R.string.library_backup_rejected;
            }
            final int done = message;
            runOnUiThread(() -> {
                libraryBusy = false;
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(Prefs.localized(context), done, Toast.LENGTH_LONG).show();
                if (done != R.string.library_backup_rejected) {
                    setResult(RESULT_OK);
                    finish();
                }
            });
        }, "panelume-library-restore").start();
    }

    private void chooseImport() {
        if (importing || libraryBusy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json");
        try {
            startActivityForResult(intent, IMPORT_PROGRESS);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_picker, Toast.LENGTH_LONG).show();
        }
    }

    private void previewImport(Uri uri) {
        if (importing || libraryBusy) return;
        importing = true;
        android.content.Context context = getApplicationContext();
        new Thread(() -> {
            try {
                ProgressRestore.Backup backup = ProgressRestore.read(context, uri);
                ProgressRestore.Summary summary = ProgressRestore.preview(ShelfDb.get(context), backup);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    importing = false;
                    new android.app.AlertDialog.Builder(this)
                        .setTitle(R.string.progress_import)
                        .setMessage(getString(R.string.progress_import_preview, summary.matched, summary.skipped))
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.progress_merge, (dialog, which) -> applyImport(backup))
                        .show();
                });
            } catch (java.io.IOException | RuntimeException error) {
                runOnUiThread(() -> {
                    importing = false;
                    if (!isFinishing() && !isDestroyed()) Toast.makeText(this, R.string.progress_import_invalid, Toast.LENGTH_LONG).show();
                });
            }
        }, "panelume-import-preview").start();
    }

    private void applyImport(ProgressRestore.Backup backup) {
        if (importing || libraryBusy) return;
        importing = true;
        android.content.Context context = getApplicationContext();
        new Thread(() -> {
            try {
                ProgressRestore.Summary summary = ProgressRestore.apply(ShelfDb.get(context), backup);
                runOnUiThread(() -> {
                    importing = false;
                    if (!isFinishing() && !isDestroyed()) Toast.makeText(this,
                        getString(R.string.progress_import_done, summary.matched, summary.skipped, summary.positionFailures), Toast.LENGTH_LONG).show();
                });
            } catch (java.io.IOException | RuntimeException error) {
                runOnUiThread(() -> {
                    importing = false;
                    if (!isFinishing() && !isDestroyed()) Toast.makeText(this, R.string.progress_import_failed, Toast.LENGTH_LONG).show();
                });
            }
        }, "panelume-import-merge").start();
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
