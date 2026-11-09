package dev.panelume.reader;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class CatalogSearch extends LinearLayout {
    private final MainActivity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newFixedThreadPool(CatalogSource.SOURCES.size());
    private final List<Future<?>> tasks = new ArrayList<>();
    private final Map<String, List<CatalogSource.Title>> results = new LinkedHashMap<>();
    private final Map<String, String> failures = new LinkedHashMap<>();
    private final Set<String> pending = new HashSet<>();
    private final List<CatalogSource.Title> visible = new ArrayList<>();
    private final Runnable debounced = () -> fetch(false);
    private final EditText query;
    private final LinearLayout sourceTabs;
    private final FrameLayout body;
    private final LinearLayout notices;
    private final TextView status;
    private final ListView list;
    private final Results adapter;
    private String filter = "";
    private String lastQuery = "";
    private int revision;
    private boolean closed;

    CatalogSearch(MainActivity activity, String previous) {
        super(activity);
        this.activity = activity;
        setOrientation(VERTICAL);
        setFocusableInTouchMode(true);

        LinearLayout heading = Ui.page(activity);
        Ui.pad(heading, 22, 18, 22, 0);
        Ui.add(heading, Ui.title(activity, activity.getString(R.string.search), 36), Ui.FILL, Ui.WRAP);
        Ui.gap(heading, 6);
        Ui.add(heading, Ui.label(activity, activity.getString(R.string.source_language), 13, Ui.MUTED), Ui.FILL, Ui.WRAP);
        Ui.gap(heading, 16);

        LinearLayout field = Ui.row(activity);
        field.setBackground(Ui.card(activity, Ui.SURFACE, 8));
        Ui.add(field, new Ui.Icon(activity, "search", Ui.ACCENT, 21), 45, 48);
        query = new EditText(activity);
        query.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        query.setTextSize(16);
        query.setTextColor(Ui.TEXT);
        query.setHintTextColor(Ui.SOFT);
        query.setHint(R.string.search_hint);
        query.setSingleLine();
        query.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setFilters(new InputFilter[]{new InputFilter.LengthFilter(280)});
        Ui.pad(query, 0, 9, 8, 9);
        Ui.weighted(field, query);
        Ui.add(field, Ui.iconButton(activity, "next", activity.getString(R.string.search), Ui.SURFACE, Ui.ACCENT,
            () -> fetch(true)), 44, 48);
        Ui.add(heading, field, Ui.FILL, Ui.WRAP);
        Ui.gap(heading, 11);
        sourceTabs = Ui.row(activity);
        HorizontalScrollView sourceStrip = new HorizontalScrollView(activity);
        sourceStrip.setHorizontalScrollBarEnabled(false);
        sourceStrip.addView(sourceTabs, new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP));
        Ui.add(heading, sourceStrip, Ui.FILL, Ui.WRAP);
        sourceButtons();
        Ui.gap(heading, 10);
        status = Ui.label(activity, "", 12, Ui.MUTED);
        Ui.add(heading, status, Ui.FILL, Ui.WRAP);
        Ui.gap(heading, 6);
        addView(heading, new LayoutParams(Ui.FILL, Ui.WRAP));

        body = new FrameLayout(activity);
        list = new ListView(activity);
        list.setDivider(null);
        list.setVerticalScrollBarEnabled(false);
        adapter = new Results();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            hideKeyboard();
            activity.startActivity(CatalogActivity.intent(activity, visible.get(position)));
        });
        body.addView(list, new FrameLayout.LayoutParams(Ui.FILL, Ui.FILL));
        notices = Ui.page(activity);
        body.addView(Ui.scroller(activity, notices), new FrameLayout.LayoutParams(Ui.FILL, Ui.FILL));
        addView(body, new LayoutParams(Ui.FILL, 0, 1));

        query.setText(previous);
        query.setSelection(query.length());
        query.setOnEditorActionListener((view, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH
                || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP)) {
                fetch(true);
                return true;
            }
            return false;
        });
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                main.removeCallbacks(debounced);
                cancel();
                lastQuery = "";
                results.clear();
                failures.clear();
                render();
                if (query().trim().length() >= 2) main.postDelayed(debounced, 520);
            }
            @Override public void afterTextChanged(Editable editable) {}
        });
        requestFocus();
        render();
        if (previous.trim().length() >= 2) main.post(debounced);
    }

    String query() { return query.getText().toString(); }

    private void sourceButtons() {
        sourceTabs.removeAllViews();
        sourceButton("", activity.getString(R.string.all));
        for (CatalogSource source : CatalogSource.SOURCES) {
            Ui.spacer(sourceTabs, 7);
            sourceButton(source.id(), source.name());
        }
    }

    private void sourceButton(String id, String label) {
        boolean current = filter.equals(id);
        TextView button = Ui.chip(activity, label, Ui.SURFACE, current ? Ui.ON_ACCENT : Ui.MUTED);
        button.setMinHeight(Ui.dp(activity, 42));
        button.setSelected(current);
        Ui.clickable(button, current ? Ui.ACCENT : Ui.SURFACE, 6, () -> {
            filter = id;
            sourceButtons();
            render();
        });
        Ui.add(sourceTabs, button, Ui.WRAP, Ui.WRAP);
    }

    private void fetch(boolean explicit) {
        if (closed) return;
        main.removeCallbacks(debounced);
        if (explicit) hideKeyboard();
        String term = query().trim();
        if (term.length() < 2 || (term.equals(lastQuery) && (isSearching() || (!explicit && failures.isEmpty())))) return;
        cancel();
        int version = revision;
        lastQuery = term;
        results.clear();
        failures.clear();
        for (CatalogSource source : CatalogSource.SOURCES) {
            CatalogSource.Title known = source.knownTitle(term);
            if (known != null) {
                results.put(source.id(), Collections.singletonList(known));
                continue;
            }
            pending.add(source.id());
            tasks.add(worker.submit(() -> {
                List<CatalogSource.Title> hits = null;
                String problem = null;
                try {
                    hits = source.search(term);
                } catch (IOException | RuntimeException e) {
                    problem = Fault.describe(activity, e, R.string.source_error);
                }
                List<CatalogSource.Title> found = hits;
                String message = problem;
                main.post(() -> {
                    if (closed || version != revision) return;
                    pending.remove(source.id());
                    if (message != null) failures.put(source.id(), message);
                    else results.put(source.id(), found);
                    render();
                });
            }));
        }
        render();
    }

    private boolean isSearching() {
        return filter.isEmpty() ? !pending.isEmpty() : pending.contains(filter);
    }

    private void render() {
        visible.clear();
        for (CatalogSource source : CatalogSource.SOURCES) {
            if (!filter.isEmpty() && !filter.equals(source.id())) continue;
            List<CatalogSource.Title> hits = results.get(source.id());
            if (hits != null) visible.addAll(hits);
        }
        String notice = !visible.isEmpty() ? activity.getString(R.string.results_count, visible.size())
            : isSearching() ? activity.getString(R.string.searching) : "";
        status.setText(notice);
        status.setVisibility(notice.isEmpty() ? GONE : VISIBLE);
        adapter.notifyDataSetChanged();
        list.setVisibility(visible.isEmpty() ? GONE : VISIBLE);
        ((View) notices.getParent()).setVisibility(visible.isEmpty() ? VISIBLE : GONE);
        notices.removeAllViews();
        if (!visible.isEmpty()) return;
        Ui.gap(notices, 17);
        if (isSearching()) return;
        if (lastQuery.isEmpty()) {
            Ui.add(notices, new Ui.StripArt(activity), 115, 105);
            Ui.gap(notices, 12);
            Ui.add(notices, Ui.title(activity, activity.getString(R.string.search_intro), 25), Ui.FILL, Ui.WRAP);
            Ui.gap(notices, 7);
            Ui.add(notices, Ui.label(activity, activity.getString(R.string.search_intro_detail), 14, Ui.MUTED),
                Ui.FILL, Ui.WRAP);
            Ui.gap(notices, 16);
            Ui.add(notices, Ui.label(activity, activity.getString(R.string.search_examples), 13, Ui.ACCENT),
                Ui.FILL, Ui.WRAP);
            return;
        }
        if (failures.isEmpty() || (!filter.isEmpty() && !failures.containsKey(filter))) {
            Ui.add(notices, Ui.title(activity, activity.getString(R.string.no_results), 24), Ui.FILL, Ui.WRAP);
            Ui.gap(notices, 7);
            Ui.add(notices, Ui.label(activity, activity.getString(R.string.no_results_detail), 14, Ui.MUTED),
                Ui.FILL, Ui.WRAP);
        } else {
            for (Map.Entry<String, String> failure : failures.entrySet()) {
                if (!filter.isEmpty() && !filter.equals(failure.getKey())) continue;
                Ui.add(notices, Ui.label(activity, activity.getString(R.string.source_failed,
                    CatalogSource.label(failure.getKey()), failure.getValue()), 14, Ui.MUTED), Ui.FILL, Ui.WRAP);
                Ui.gap(notices, 8);
            }
            Ui.add(notices, Ui.button(activity, activity.getString(R.string.retry), false, () -> fetch(true)),
                Ui.FILL, Ui.WRAP);
        }
    }

    private void hideKeyboard() {
        InputMethodManager input = activity.getSystemService(InputMethodManager.class);
        if (input != null) input.hideSoftInputFromWindow(query.getWindowToken(), 0);
        query.clearFocus();
    }

    private void cancel() {
        revision++;
        pending.clear();
        for (Future<?> task : tasks) task.cancel(true);
        tasks.clear();
    }

    void close() {
        closed = true;
        main.removeCallbacks(debounced);
        cancel();
        worker.shutdownNow();
    }

    private final class Results extends BaseAdapter {
        @Override public int getCount() { return visible.size(); }
        @Override public CatalogSource.Title getItem(int position) { return visible.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            CatalogSource.Title hit = getItem(position);
            LinearLayout row = Ui.row(activity);
            Ui.pad(row, 22, 8, 18, 8);
            row.setMinimumHeight(Ui.dp(activity, 86));
            Ui.add(row, RemoteCovers.view(activity, hit, 5), 48, 64);
            Ui.spacer(row, 13);
            LinearLayout labels = Ui.column(activity);
            TextView name = Ui.strong(activity, hit.name, 16, Ui.TEXT);
            Ui.ellipsize(name, 2);
            Ui.add(labels, name, Ui.FILL, Ui.WRAP);
            Ui.gap(labels, 6);
            Ui.add(labels, Ui.label(activity, CatalogSource.label(hit.provider), 12, Ui.ACCENT), Ui.FILL, Ui.WRAP);
            Ui.weighted(row, labels);
            Ui.add(row, new Ui.Icon(activity, "next", Ui.SOFT, 17), 30, 36);
            LinearLayout cell = Ui.column(activity);
            Ui.add(cell, row, Ui.FILL, Ui.WRAP);
            Ui.rule(cell);
            return cell;
        }
    }
}
