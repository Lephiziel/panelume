package dev.panelume.reader;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class ImportActivity extends PanelumeActivity {
    static final String SERIES = "series";
    static final String RESULT_SERIES = "resultSeries";
    private static final int PICK_FILES = 11;
    private static final int MAX_BATCH = 36;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Draft> drafts = new ArrayList<>();
    private final List<String> issues = new ArrayList<>();
    private final Set<Long> importedSeries = new LinkedHashSet<>();
    private ShelfDb db;
    private LinearLayout root;
    private LinearLayout body;
    private TextView progressLabel;
    private Future<?> job;
    private String preset = "";
    private volatile boolean destroyed;
    private boolean saving;
    private int imported;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        db = ShelfDb.get(this);
        String title = getIntent().getStringExtra(SERIES);
        if (title != null) preset = title;
        Ui.edgeWindow(this, Ui.BG);
        choose();
    }

    private void screen(String title, String detail, boolean animated) {
        root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        Ui.fitInsets(root);
        setContentView(root);
        LinearLayout toolbar = Ui.row(this);
        Ui.pad(toolbar, 12, 5, 12, 5);
        Ui.add(toolbar, Ui.iconButton(this, "back", getString(R.string.back), Ui.RAISED, Ui.TEXT, this::onBackPressed), 48, 48);
        Ui.spacer(toolbar, 12);
        Ui.weighted(toolbar, Ui.strong(this, getString(R.string.import_label), 17, Ui.TEXT));
        Ui.add(root, toolbar, Ui.FILL, Ui.WRAP);

        body = Ui.page(this);
        body.setFocusableInTouchMode(true);
        body.requestFocus();
        Ui.gap(body, 15);
        Ui.add(body, Ui.title(this, title, 30), Ui.FILL, Ui.WRAP);
        Ui.gap(body, 10);
        Ui.add(body, Ui.label(this, detail, 15, Ui.MUTED), Ui.FILL, Ui.WRAP);
        Ui.gap(body, 20);
        root.addView(Ui.scroller(this, body), new LinearLayout.LayoutParams(Ui.FILL, 0, 1));
        if (animated) Ui.enter(body, 10, 0);
    }

    private void choose() {
        screen(getString(R.string.import_chapters), preset.isEmpty()
            ? getString(R.string.import_description)
            : getString(R.string.import_preset, preset), true);
        Ui.add(body, option("file", getString(R.string.choose_files),
            getString(R.string.formats), this::pickFiles), Ui.FILL, Ui.WRAP);
        Ui.gap(body, 11);
        Ui.add(body, option("link", getString(R.string.paste_link),
            getString(R.string.direct_link), this::linkDialog), Ui.FILL, Ui.WRAP);
        Ui.gap(body, 19);
        Ui.add(body, Ui.label(this, getString(R.string.batch_hint, MAX_BATCH), 13, Ui.SOFT), Ui.FILL, Ui.WRAP);
    }

    private View option(String glyph, String title, String detail, Runnable action) {
        LinearLayout row = Ui.row(this);
        row.setBackground(Ui.card(this, Ui.SURFACE, 8));
        Ui.pad(row, 14, 15, 12, 15);
        Ui.Icon icon = new Ui.Icon(this, glyph, Ui.ACCENT, 24);
        icon.setBackground(Ui.shape(this, Ui.RAISED, 13));
        Ui.add(row, icon, 46, 46);
        Ui.spacer(row, 12);
        LinearLayout text = Ui.column(this);
        Ui.add(text, Ui.strong(this, title, 17, Ui.TEXT), Ui.FILL, Ui.WRAP);
        Ui.gap(text, 5);
        Ui.add(text, Ui.label(this, detail, 13, Ui.MUTED), Ui.FILL, Ui.WRAP);
        Ui.weighted(row, text);
        Ui.add(row, new Ui.Icon(this, "next", Ui.SOFT, 17), 24, 28);
        Ui.touchOverlay(row, 8, action);
        return row;
    }

    private void pickFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, PICK_FILES);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, getString(R.string.no_picker), Toast.LENGTH_LONG).show();
        }
    }

    private void linkDialog() {
        LinearLayout box = Ui.page(this);
        Ui.gap(box, 12);
        EditText link = field(this, "https://…/chapter-4.pdf", "",
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        link.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4096)});
        Ui.add(box, link, Ui.FILL, Ui.WRAP);
        Ui.gap(box, 10);
        Ui.add(box, Ui.label(this, getString(R.string.chapter_link_help), 13, Ui.MUTED),
            Ui.FILL, Ui.WRAP);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(getString(R.string.chapter_link))
            .setView(box).setNegativeButton(getString(R.string.cancel), null).setPositiveButton(getString(R.string.download), null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            String value = link.getText().toString().trim();
            try {
                ImportFiles.validateLink(value);
            } catch (IOException e) {
                link.setError(Fault.describe(this, e, R.string.bad_url));
                return;
            }
            dialog.dismiss();
            prepare(java.util.Collections.singletonList(() -> ImportFiles.fromLink(getApplicationContext(), value)));
        }));
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        link.requestFocus();
        dialog.show();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_FILES || result != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        ClipData clips = data.getClipData();
        if (clips != null) {
            for (int i = 0; i < clips.getItemCount(); i++) {
                Uri uri = clips.getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.size() > MAX_BATCH) {
            Toast.makeText(this, getString(R.string.batch_limit, MAX_BATCH), Toast.LENGTH_LONG).show();
            return;
        }
        List<Source> sources = new ArrayList<>();
        for (Uri uri : new LinkedHashSet<>(uris)) {
            sources.add(() -> ImportFiles.fromUri(getApplicationContext(), uri));
        }
        if (!sources.isEmpty()) prepare(sources);
    }

    private interface Source {
        ImportFiles.Staged read() throws IOException;
    }

    private void prepare(List<Source> sources) {
        issues.clear();
        showProgress(getString(R.string.checking_files), getString(R.string.preparing_covers), "0 / " + sources.size(), true);
        job = worker.submit(() -> {
            List<Draft> ready = new ArrayList<>();
            List<String> failed = new ArrayList<>();
            int processed = 0;
            for (Source source : sources) {
                if (destroyed || Thread.currentThread().isInterrupted()) break;
                ImportFiles.Staged file = null;
                try {
                    file = source.read();
                    String guess = preset.isEmpty() ? db.suggestedTitle(file.guess) : preset;
                    ready.add(new Draft(file, guess));
                } catch (IOException | RuntimeException e) {
                    if (file != null) file.file.delete();
                    failed.add(getString(R.string.file_issue, processed + 1, Fault.describe(this, e, R.string.file_error)));
                }
                String progress = (++processed) + " / " + sources.size();
                runOnUiThread(() -> {
                    if (!destroyed && progressLabel != null) progressLabel.setText(progress);
                });
            }
            runOnUiThread(() -> {
                if (destroyed) {
                    discard(ready);
                    return;
                }
                job = null;
                drafts.addAll(ready);
                issues.addAll(failed);
                review();
            });
        });
    }

    private void showProgress(String title, String detail, String progress, boolean cancel) {
        screen(title, detail, false);
        LinearLayout card = Ui.row(this);
        card.setBackground(Ui.card(this, Ui.SURFACE, 8));
        Ui.pad(card, 16, 16, 16, 16);
        Ui.add(card, new ProgressBar(this), 30, 30);
        Ui.spacer(card, 14);
        progressLabel = Ui.strong(this, progress, 16, Ui.ACCENT);
        Ui.weighted(card, progressLabel);
        Ui.add(body, card, Ui.FILL, Ui.WRAP);
        if (cancel) {
            Ui.gap(body, 14);
            Ui.add(body, Ui.button(this, getString(R.string.cancel), false, this::finish), Ui.FILL, Ui.WRAP);
        }
    }

    private void review() {
        if (drafts.isEmpty()) {
            summary();
            return;
        }
        screen(getString(R.string.review_chapters), getString(R.string.review_help),
            true);
        addIssues();
        List<DraftFields> fields = new ArrayList<>();
        List<String> titles = titleSuggestions(db);
        for (Draft draft : drafts) {
            DraftFields controls = new DraftFields(draft);
            fields.add(controls);
            Ui.add(body, draftCard(draft, controls, titles), Ui.FILL, Ui.WRAP);
            Ui.gap(body, 12);
        }
        LinearLayout actions = Ui.column(this);
        actions.setBackgroundColor(Ui.SURFACE);
        Ui.pad(actions, 22, 10, 22, 10);
        Ui.add(actions, Ui.button(this, getString(R.string.import_count, drafts.size()), true, () -> save(fields)), Ui.FILL, Ui.WRAP);
        if (imported > 0) {
            Ui.gap(actions, 8);
            Ui.add(actions, Ui.button(this, getString(R.string.done_count, Ui.chapters(this, imported)), false, this::done), Ui.FILL, Ui.WRAP);
        }
        Ui.add(root, actions, Ui.FILL, Ui.WRAP);
    }

    private View draftCard(Draft draft, DraftFields fields, List<String> titles) {
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.card(this, Ui.SURFACE, 8));
        Ui.pad(card, 13, 13, 13, 14);
        LinearLayout header = Ui.row(this);
        ImageView cover = new ImageView(this);
        cover.setBackground(Ui.shape(this, Ui.RAISED, 8));
        cover.setClipToOutline(true);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        cover.setImageBitmap(BitmapFactory.decodeByteArray(draft.file.thumbnail, 0, draft.file.thumbnail.length));
        Ui.add(header, cover, 39, 49);
        Ui.spacer(header, 10);
        LinearLayout labels = Ui.column(this);
        TextView filename = Ui.strong(this, draft.file.name, 14, Ui.TEXT);
        Ui.ellipsize(filename, 2);
        Ui.add(labels, filename, Ui.FILL, Ui.WRAP);
        Ui.gap(labels, 5);
        Ui.add(labels, Ui.label(this, draft.file.format.toUpperCase(java.util.Locale.ROOT)
            + "  ·  " + getString(R.string.pages_count, draft.file.pages), 12, Ui.MUTED), Ui.FILL, Ui.WRAP);
        Ui.weighted(header, labels);
        Ui.add(card, header, Ui.FILL, Ui.WRAP);
        Ui.gap(card, 12);
        Ui.add(card, Ui.label(this, getString(R.string.series), 12, Ui.MUTED), Ui.FILL, Ui.WRAP);
        Ui.gap(card, 5);
        fields.title = titleField(this, titles, draft.title);
        Ui.add(card, fields.title, Ui.FILL, Ui.WRAP);
        Ui.gap(card, 9);
        Ui.add(card, Ui.label(this, getString(R.string.chapter_number), 12, Ui.MUTED), Ui.FILL, Ui.WRAP);
        Ui.gap(card, 5);
        fields.number = field(this, getString(R.string.unnumbered), draft.number,
            InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        fields.number.setFilters(new InputFilter[]{new InputFilter.LengthFilter(12)});
        Ui.add(card, fields.number, Ui.FILL, Ui.WRAP);
        if (draft.error != null) {
            Ui.gap(card, 9);
            Ui.add(card, Ui.label(this, draft.error, 13, Ui.ERROR), Ui.FILL, Ui.WRAP);
        }
        return card;
    }

    private void save(List<DraftFields> fields) {
        boolean valid = true;
        for (DraftFields field : fields) {
            field.draft.title = field.title.getText().toString().trim();
            field.draft.number = field.number.getText().toString().trim();
            if (Names.key(field.draft.title).isEmpty()) {
                field.title.setError(getString(R.string.required_series));
                valid = false;
            }
            try {
                field.draft.number = Names.normalizeNumber(field.draft.number);
            } catch (IllegalArgumentException e) {
                field.number.setError(getString(R.string.bad_number));
                valid = false;
            }
        }
        if (!valid) {
            Toast.makeText(this, getString(R.string.check_fields), Toast.LENGTH_SHORT).show();
            return;
        }
        saving = true;
        List<Draft> pending = new ArrayList<>(drafts);
        showProgress(getString(R.string.saving_chapters), getString(R.string.adding_to_library), "0 / " + pending.size(), false);
        job = worker.submit(() -> {
            List<Draft> remaining = new ArrayList<>();
            Set<Long> series = new LinkedHashSet<>();
            int processed = 0;
            int success = 0;
            for (Draft draft : pending) {
                if (destroyed) {
                    draft.file.file.delete();
                    continue;
                }
                try {
                    series.add(ImportFiles.store(db, draft.file, draft.title, draft.number));
                    success++;
                } catch (IOException | RuntimeException e) {
                    draft.error = Fault.describe(this, e, R.string.file_error);
                    remaining.add(draft);
                }
                String progress = (++processed) + " / " + pending.size();
                runOnUiThread(() -> {
                    if (!destroyed && progressLabel != null) progressLabel.setText(progress);
                });
            }
            int added = success;
            runOnUiThread(() -> {
                if (destroyed) {
                    discard(remaining);
                    return;
                }
                saving = false;
                job = null;
                imported += added;
                importedSeries.addAll(series);
                drafts.clear();
                drafts.addAll(remaining);
                if (added > 0) setImportResult();
                review();
            });
        });
    }

    private void summary() {
        boolean success = imported > 0;
        screen(success ? getString(R.string.chapters_added) : getString(R.string.import_failed), success
            ? Ui.chapters(this, imported) + " · " + getString(R.string.offline_available)
            : getString(R.string.import_retry_help), true);
        addIssues();
        Ui.add(body, Ui.button(this, success
            ? (importedSeries.size() == 1 ? getString(R.string.open_series) : getString(R.string.open_library)) : getString(R.string.retry),
            true, success ? this::done : this::choose), Ui.FILL, Ui.WRAP);
    }

    private void addIssues() {
        if (issues.isEmpty()) return;
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.card(this, Ui.SURFACE, 16));
        Ui.pad(card, 12, 12, 12, 12);
        Ui.add(card, Ui.strong(this, getString(R.string.skipped_files, issues.size()), 14, Ui.ERROR), Ui.FILL, Ui.WRAP);
        for (int i = 0; i < Math.min(5, issues.size()); i++) {
            Ui.gap(card, 5);
            Ui.add(card, Ui.label(this, issues.get(i), 12, Ui.MUTED), Ui.FILL, Ui.WRAP);
        }
        Ui.add(body, card, Ui.FILL, Ui.WRAP);
        Ui.gap(body, 12);
    }

    private void setImportResult() {
        long id = importedSeries.size() == 1 ? importedSeries.iterator().next() : -1;
        setResult(RESULT_OK, new Intent().putExtra(RESULT_SERIES, id));
    }

    private void done() {
        setImportResult();
        finish();
    }

    static EditText titleField(Context c, ShelfDb db, String value) {
        return titleField(c, titleSuggestions(db), value);
    }

    private static List<String> titleSuggestions(ShelfDb db) {
        List<String> titles = new ArrayList<>();
        for (ShelfDb.Series series : db.series()) titles.add(series.title);
        return titles;
    }

    private static EditText titleField(Context c, List<String> titles, String value) {
        AutoCompleteTextView field = new AutoCompleteTextView(c);
        configureField(field, c.getString(R.string.series_name), value, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(120)});
        field.setAdapter(new ArrayAdapter<>(c, android.R.layout.simple_dropdown_item_1line, titles));
        field.setThreshold(1);
        return field;
    }

    static EditText field(Context context, String hint, String value, int type) {
        EditText field = new EditText(context);
        configureField(field, hint, value, type);
        return field;
    }

    private static void configureField(EditText field, String hint, String value, int type) {
        field.setSingleLine(true);
        field.setInputType(type);
        field.setTextColor(Ui.TEXT);
        field.setHintTextColor(Ui.SOFT);
        field.setTextSize(15);
        field.setHint(hint);
        field.setText(value);
        field.setBackground(Ui.card(field.getContext(), Ui.BG, 12));
        field.setMinHeight(Ui.dp(field.getContext(), 44));
        field.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(field, 12, 8, 12, 8);
    }

    @Override
    public void onBackPressed() {
        if (saving) Toast.makeText(this, getString(R.string.wait_save), Toast.LENGTH_SHORT).show();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (job != null && !saving) job.cancel(true);
        if (!saving) discard(drafts);
        worker.shutdown();
        super.onDestroy();
    }

    private static void discard(List<Draft> drafts) {
        for (Draft draft : drafts) draft.file.file.delete();
    }

    private static final class Draft {
        final ImportFiles.Staged file;
        String title;
        String number;
        String error;

        Draft(ImportFiles.Staged file, String title) {
            this.file = file;
            this.title = title;
            number = file.number;
        }
    }

    private static final class DraftFields {
        final Draft draft;
        EditText title;
        EditText number;

        DraftFields(Draft draft) { this.draft = draft; }
    }
}
