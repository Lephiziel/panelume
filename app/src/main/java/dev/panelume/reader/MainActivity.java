package dev.panelume.reader;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MainActivity extends PanelumeActivity {
    private static final int HOME = 0;
    private static final int LIBRARY = 1;
    private static final int STATS = 2;
    private static final int SEARCH = 3;
    private static final int SETUP = 13;
    private static final int IMPORT = 12;

    private ShelfDb db;
    private CatalogQueue queue;
    private LinearLayout root;
    private int tab = HOME;
    private long seriesId = -1;
    private int filter;
    private LocalDate statsDay;
    private String search = "";
    private boolean firstDraw = true;
    private boolean setupLaunched;
    private String onlineQuery = "";
    private CatalogSearch catalog;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.edgeWindow(this, Ui.BG);
        db = ShelfDb.get(this);
        queue = new CatalogQueue(db);
        if (state != null) {
            tab = state.getInt("tab", HOME);
            seriesId = state.getLong("series", -1);
            filter = state.getInt("filter", 0);
            search = state.getString("search", "");
            onlineQuery = state.getString("online", "");
        }
        Thread cleanup = new Thread(() -> ImportFiles.cleanup(getApplicationContext(), db), "panelume-cleanup");
        cleanup.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!Prefs.ready(this)) {
            if (!setupLaunched) {
                setupLaunched = true;
                startActivityForResult(new Intent(this, PreferencesActivity.class)
                    .putExtra(PreferencesActivity.SETUP, true), SETUP);
            }
            return;
        }
        if (languageChanged()) {
            recreate();
            return;
        }
        if (tab == SEARCH && catalog != null) return;
        draw(firstDraw);
        firstDraw = false;
    }

    private void draw(boolean animate) {
        if (catalog != null) {
            onlineQuery = catalog.query();
            catalog.close();
            catalog = null;
        }
        List<ShelfDb.Series> all = db.series();
        root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        Ui.fitInsets(root);
        setContentView(root);
        if (seriesId != -1) {
            for (ShelfDb.Series series : all) {
                if (series.id == seriesId) {
                    details(series, animate);
                    return;
                }
            }
            seriesId = -1;
        }
        View screen;
        if (tab == LIBRARY) screen = library(all);
        else if (tab == STATS) screen = stats();
        else if (tab == SEARCH) {
            catalog = new CatalogSearch(this, onlineQuery);
            screen = catalog;
        } else screen = home(all);
        root.addView(screen, new LinearLayout.LayoutParams(Ui.FILL, 0, 1));
        Ui.add(root, navigation(), Ui.FILL, Ui.WRAP);
        if (animate) Ui.enter(screen, 10, 0);
    }

    private LinearLayout navigation() {
        LinearLayout shell = Ui.column(this);
        View border = new View(this);
        border.setBackgroundColor(Ui.BORDER);
        Ui.add(shell, border, Ui.FILL, 1);

        LinearLayout row = Ui.row(this);
        row.setBackgroundColor(Ui.BG);
        Ui.pad(row, 6, 5, 6, 5);
        navItem(row, HOME, "home", getString(R.string.home));
        navItem(row, SEARCH, "search", getString(R.string.search));
        navItem(row, LIBRARY, "shelf", getString(R.string.library));
        navItem(row, STATS, "stats", getString(R.string.stats));
        Ui.add(shell, row, Ui.FILL, Ui.WRAP);
        return shell;
    }

    private void navItem(LinearLayout parent, int id, String iconName, String text) {
        boolean selected = tab == id;
        LinearLayout item = Ui.column(this);
        item.setGravity(Gravity.CENTER);
        Ui.pad(item, 3, 5, 3, 5);
        Ui.Icon icon = new Ui.Icon(this, iconName, selected ? Ui.ACCENT : Ui.MUTED, 22);
        Ui.add(item, icon, 34, 27);
        Ui.gap(item, 4);
        Ui.add(item, Ui.strong(this, text, 11, selected ? Ui.ACCENT : Ui.MUTED), Ui.WRAP, Ui.WRAP);
        item.setContentDescription(text);
        item.setSelected(selected);
        Ui.clickable(item, Ui.BG, 8, () -> showTab(id));
        Ui.weighted(parent, item);
    }

    private void showTab(int id) {
        if (tab == id && seriesId == -1) return;
        tab = id;
        seriesId = -1;
        if (id == STATS) statsDay = LocalDate.now();
        draw(true);
    }

    private View home(List<ShelfDb.Series> series) {
        LinearLayout page = Ui.page(this);
        Ui.gap(page, 12);
        Ui.add(page, brandHeader(), Ui.FILL, Ui.WRAP);
        Ui.gap(page, 16);
        Ui.rule(page);
        Ui.gap(page, 20);
        LinearLayout desk = Ui.row(this);
        Ui.weighted(desk, Ui.title(this, getString(R.string.reading_desk), 31));
        TextView date = Ui.label(this, longDate().format(LocalDate.now()), 13, Ui.SOFT);
        Ui.add(desk, date, Ui.WRAP, Ui.WRAP);
        Ui.add(page, desk, Ui.FILL, Ui.WRAP);
        Ui.gap(page, 16);

        ShelfDb.Chapter current = db.lastOpened();
        Ui.add(page, current == null ? welcome(series.isEmpty()) : resumeCard(current), Ui.FILL, Ui.WRAP);
        Ui.gap(page, 18);
        Ui.add(page, todayCard(), Ui.FILL, Ui.WRAP);

        if (!series.isEmpty()) {
            Ui.gap(page, 27);
            LinearLayout heading = Ui.row(this);
            Ui.weighted(heading, Ui.title(this, getString(R.string.my_shelf), 22));
            TextView more = Ui.strong(this, getString(R.string.all_series), 13, Ui.ACCENT);
            Ui.pad(more, 8, 10, 2, 10);
            Ui.clickable(more, Ui.BG, 10, () -> showTab(LIBRARY));
            Ui.add(heading, more, Ui.WRAP, Ui.WRAP);
            Ui.add(page, heading, Ui.FILL, Ui.WRAP);
            Ui.gap(page, 11);
            Ui.add(page, grid(series.subList(0, Math.min(2, series.size()))), Ui.FILL, Ui.WRAP);
        }
        return Ui.scroller(this, page);
    }

    private LinearLayout brandHeader() {
        LinearLayout header = Ui.row(this);
        Ui.add(header, Ui.mark(this, 8), 38, 38);
        Ui.spacer(header, 10);
        Ui.weighted(header, Ui.title(this, "Panelume", 29));
        Ui.add(header, Ui.iconButton(this, "download", getString(R.string.downloads), Ui.BG, Ui.MUTED,
            () -> startActivity(new Intent(this, DownloadsActivity.class))), 48, 48);
        Ui.add(header, Ui.iconButton(this, "settings", getString(R.string.settings), Ui.BG, Ui.MUTED,
            () -> startActivity(new Intent(this, PreferencesActivity.class))), 48, 48);
        return header;
    }

    private View welcome(boolean empty) {
        LinearLayout content = Ui.column(this);
        Ui.pad(content, 17, 18, 17, 16);
        LinearLayout row = Ui.row(this);
        LinearLayout words = Ui.column(this);
        Ui.add(words, Ui.title(this, getString(empty ? R.string.empty_home : R.string.ready_home), 29),
            Ui.FILL, Ui.WRAP);
        Ui.gap(words, 8);
        Ui.add(words, Ui.label(this, getString(empty ? R.string.empty_home_detail : R.string.ready_home_detail),
            14, Ui.MUTED), Ui.FILL, Ui.WRAP);
        Ui.weighted(row, words);
        Ui.spacer(row, 9);
        Ui.add(row, new Ui.StripArt(this), 99, 94);
        Ui.add(content, row, Ui.FILL, Ui.WRAP);
        Ui.gap(content, 16);
        Ui.add(content, Ui.button(this, getString(empty ? R.string.search : R.string.open_library), true,
            () -> showTab(empty ? SEARCH : LIBRARY)), Ui.FILL, Ui.WRAP);
        Ui.gap(content, 8);
        Ui.add(content, Ui.button(this, getString(R.string.import_chapters), false, () -> importFiles("")),
            Ui.FILL, Ui.WRAP);
        return hero(content);
    }

    private View resumeCard(ShelfDb.Chapter chapter) {
        Bookmark.Position position = Bookmark.read(chapter.bookmark(db.root));
        ShelfDb.Chapter next = chapter.done ? db.next(chapter) : null;
        LinearLayout content = Ui.column(this);
        Ui.pad(content, 19, 20, 19, 18);
        Ui.add(content, Ui.overline(this, getString(chapter.done ? R.string.recent_chapter : R.string.resume_heading)
            .toUpperCase(Prefs.locale(this)), Ui.ACCENT),
            Ui.FILL, Ui.WRAP);
        Ui.gap(content, 17);
        LinearLayout row = Ui.row(this);
        Ui.add(row, seriesCover(chapter.seriesId, chapter.provider, chapter.directory, 5), 82, 105);
        Ui.spacer(row, 14);
        LinearLayout names = Ui.column(this);
        TextView title = Ui.title(this, chapter.title, 27);
        Ui.ellipsize(title, 2);
        Ui.add(names, title, Ui.FILL, Ui.WRAP);
        Ui.gap(names, 7);
        TextView subtitle = Ui.label(this, chapter.label(this), 14, Ui.TEXT);
        Ui.ellipsize(subtitle, 1);
        Ui.add(names, subtitle, Ui.FILL, Ui.WRAP);
        Ui.gap(names, 8);
        Ui.add(names, Ui.strong(this, chapter.done ? getString(R.string.read) : getString(R.string.reading_progress, position.percent),
            12, chapter.done ? Ui.MINT : Ui.ACCENT), Ui.FILL, Ui.WRAP);
        Ui.weighted(row, names);
        Ui.add(content, row, Ui.FILL, Ui.WRAP);
        Ui.gap(content, 17);
        Ui.add(content, Ui.progress(this, chapter.done ? 1f : position.percent / 100f, chapter.done ? Ui.MINT : Ui.ACCENT),
            Ui.FILL, 5);
        Ui.gap(content, 15);
        String action = chapter.done ? (next == null ? getString(R.string.read_again) : getString(R.string.next_chapter)) : getString(R.string.resume);
        Ui.add(content, Ui.button(this, action, true, () -> {
            if (next != null) open(next.id, false);
            else open(chapter.id, chapter.done);
        }), Ui.FILL, Ui.WRAP);
        return hero(content);
    }

    private View hero(LinearLayout content) {
        FrameLayout card = new FrameLayout(this);
        card.setBackground(Ui.card(this, Ui.SURFACE, 10));
        card.setClipToOutline(true);
        card.addView(content, new FrameLayout.LayoutParams(Ui.FILL, Ui.WRAP));
        return card;
    }

    private View todayCard() {
        LocalDate today = LocalDate.now();
        long time = db.dailyTotals(today, today).getOrDefault(today, 0L);
        LinearLayout card = Ui.row(this);
        card.setBackground(Ui.card(this, Ui.SURFACE, 9));
        Ui.pad(card, 14, 14, 14, 14);
        Ui.Icon icon = new Ui.Icon(this, "clock", Ui.ACCENT, 22);
        icon.setBackground(Ui.shape(this, Ui.RAISED, 14));
        Ui.add(card, icon, 44, 44);
        Ui.spacer(card, 12);
        LinearLayout labels = Ui.column(this);
        Ui.add(labels, Ui.strong(this, getString(R.string.today), 16, Ui.TEXT), Ui.FILL, Ui.WRAP);
        Ui.gap(labels, 5);
        Ui.add(labels, Ui.label(this, getString(R.string.reading_time), 12, Ui.MUTED), Ui.FILL, Ui.WRAP);
        Ui.weighted(card, labels);
        Ui.add(card, Ui.strong(this, Ui.duration(this, time), 16, Ui.ACCENT), Ui.WRAP, Ui.WRAP);
        Ui.add(card, new Ui.Icon(this, "next", Ui.SOFT, 17), 24, 24);
        Ui.touchOverlay(card, 9, () -> showTab(STATS));
        return card;
    }

    private View library(List<ShelfDb.Series> series) {
        LinearLayout page = Ui.page(this);
        Ui.gap(page, 13);
        LinearLayout heading = Ui.row(this);
        LinearLayout labels = Ui.column(this);
        Ui.add(labels, Ui.overline(this, getString(R.string.offline_available).toUpperCase(Prefs.locale(this)), Ui.SOFT), Ui.FILL, Ui.WRAP);
        Ui.gap(labels, 8);
        Ui.add(labels, Ui.title(this, getString(R.string.library), 32), Ui.FILL, Ui.WRAP);
        Ui.weighted(heading, labels);
        Ui.add(heading, Ui.iconButton(this, "add", getString(R.string.import_chapters), Ui.RAISED, Ui.ACCENT, () -> importFiles("")),
            48, 48);
        Ui.add(page, heading, Ui.FILL, Ui.WRAP);
        Ui.gap(page, 18);
        if (series.isEmpty()) {
            Ui.add(page, emptyCard("shelf", getString(R.string.empty_library),
                getString(R.string.empty_library_detail),
                getString(R.string.import_chapters), () -> importFiles("")), Ui.FILL, Ui.WRAP);
            return Ui.scroller(this, page);
        }

        LinearLayout searchBox = Ui.row(this);
        searchBox.setBackground(Ui.card(this, Ui.SURFACE, 15));
        Ui.add(searchBox, new Ui.Icon(this, "search", Ui.MUTED, 19), 42, 46);
        EditText field = new EditText(this);
        field.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        field.setTextColor(Ui.TEXT);
        field.setHintTextColor(Ui.SOFT);
        field.setHint(getString(R.string.shelf_search));
        field.setTextSize(15);
        field.setSingleLine();
        field.setText(search);
        Ui.pad(field, 0, 9, 11, 9);
        Ui.weighted(searchBox, field);
        Ui.add(page, searchBox, Ui.FILL, Ui.WRAP);
        Ui.gap(page, 18);
        LinearLayout cards = Ui.column(this);
        Ui.add(page, cards, Ui.FILL, Ui.WRAP);
        fillSearch(cards, series);
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                search = s.toString();
                fillSearch(cards, series);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        page.setFocusableInTouchMode(true);
        page.requestFocus();
        return Ui.scroller(this, page);
    }

    private void fillSearch(LinearLayout cards, List<ShelfDb.Series> series) {
        cards.removeAllViews();
        String query = Names.key(search);
        List<ShelfDb.Series> matches = new ArrayList<>();
        for (ShelfDb.Series title : series) if (Names.key(title.title).contains(query)) matches.add(title);
        if (matches.isEmpty()) {
            Ui.add(cards, emptyCard("search", getString(R.string.no_results), getString(R.string.no_results_detail), null, null),
                Ui.FILL, Ui.WRAP);
        } else {
            Ui.add(cards, grid(matches), Ui.FILL, Ui.WRAP);
        }
    }

    private View grid(List<ShelfDb.Series> series) {
        LinearLayout list = Ui.column(this);
        for (int i = 0; i < series.size(); i += 2) {
            if (i > 0) Ui.gap(list, 12);
            LinearLayout row = Ui.row(this);
            row.setGravity(Gravity.TOP);
            Ui.weighted(row, seriesCard(series.get(i)));
            Ui.spacer(row, 12);
            Ui.weighted(row, i + 1 < series.size() ? seriesCard(series.get(i + 1)) : new View(this));
            Ui.add(list, row, Ui.FILL, Ui.WRAP);
        }
        return list;
    }

    private View seriesCard(ShelfDb.Series series) {
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.card(this, Ui.SURFACE, 9));
        card.setClipToOutline(true);
        Ui.add(card, seriesCover(series.id, series.provider, series.coverDirectory, 0), Ui.FILL, 144);
        LinearLayout info = Ui.column(this);
        Ui.pad(info, 12, 11, 12, 13);
        TextView title = Ui.strong(this, series.title, 15, Ui.TEXT);
        Ui.ellipsize(title, 2);
        Ui.add(info, title, Ui.FILL, Ui.WRAP);
        Ui.gap(info, 6);
        Ui.add(info, Ui.label(this, Ui.chapters(this, series.count)
            + (series.provider.isEmpty() ? "" : " · " + CatalogSource.label(series.provider)), 12, Ui.MUTED), Ui.FILL, Ui.WRAP);
        Ui.gap(info, 9);
        Ui.add(info, Ui.progress(this, (float) series.done / series.count, Ui.MINT), Ui.FILL, 3);
        Ui.add(card, info, Ui.FILL, Ui.WRAP);
        Ui.touchOverlay(card, 9, () -> {
            seriesId = series.id;
            filter = 0;
            draw(true);
        });
        return card;
    }

    private void details(ShelfDb.Series series, boolean animate) {
        LinearLayout toolbar = Ui.row(this);
        Ui.pad(toolbar, 12, 5, 12, 5);
        Ui.add(toolbar, Ui.iconButton(this, "back", getString(R.string.back), Ui.RAISED, Ui.TEXT, this::leaveDetails), 48, 48);
        Ui.spacer(toolbar, 12);
        TextView title = Ui.strong(this, series.title, 17, Ui.TEXT);
        Ui.ellipsize(title, 1);
        Ui.weighted(toolbar, title);
        if (series.provider.isEmpty()) {
            Ui.add(toolbar, Ui.iconButton(this, "add", getString(R.string.import_into_series), Ui.BG, Ui.ACCENT,
                () -> importFiles(series.title)), 48, 48);
        } else {
            Ui.add(toolbar, Ui.iconButton(this, "external", getString(R.string.online_chapters), Ui.BG, Ui.ACCENT, () -> {
                CatalogSource.Title remote = queue.title(series.id);
                if (remote != null) startActivity(CatalogActivity.intent(this, remote));
            }), 48, 48);
        }
        Ui.add(root, toolbar, Ui.FILL, Ui.WRAP);

        List<ShelfDb.Chapter> chapters = db.chapters(series.id);
        List<ShelfDb.Chapter> visible = new ArrayList<>();
        ShelfDb.Chapter inProgress = null;
        ShelfDb.Chapter unread = null;
        for (ShelfDb.Chapter chapter : chapters) {
            if (!chapter.done && unread == null) unread = chapter;
            if (!chapter.done && chapter.opened > 0 && (inProgress == null || chapter.opened > inProgress.opened)) {
                inProgress = chapter;
            }
            if (filter == 0 || (filter == 1 && chapter.opened > 0 && !chapter.done) || (filter == 2 && chapter.done)) {
                visible.add(chapter);
            }
        }

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setClipToPadding(false);
        list.setVerticalScrollBarEnabled(false);
        Ui.pad(list, 0, 0, 0, 16);
        LinearLayout header = Ui.page(this);
        Ui.pad(header, 22, 9, 22, 9);
        ShelfDb.Chapter first = inProgress != null ? inProgress : unread;
        Ui.add(header, seriesHero(series, first), Ui.FILL, Ui.WRAP);
        Ui.gap(header, 23);
        Ui.add(header, Ui.title(this, getString(R.string.chapters), 23), Ui.FILL, Ui.WRAP);
        Ui.gap(header, 11);
        Ui.add(header, filters(), Ui.FILL, Ui.WRAP);
        Ui.gap(header, 6);
        if (visible.isEmpty()) {
            Ui.add(header, Ui.label(this, getString(R.string.empty_filter), 14, Ui.MUTED), Ui.FILL, 46);
        }
        list.addHeaderView(header, null, false);
        list.setAdapter(new ChapterRows(visible));
        root.addView(list, new LinearLayout.LayoutParams(Ui.FILL, 0, 1));
        if (animate) Ui.enter(list, 10, 0);
    }

    private View seriesHero(ShelfDb.Series series, ShelfDb.Chapter chapter) {
        LinearLayout content = Ui.column(this);
        Ui.pad(content, 17, 17, 17, 17);
        LinearLayout row = Ui.row(this);
        Ui.add(row, seriesCover(series.id, series.provider, series.coverDirectory, 13), 78, 104);
        Ui.spacer(row, 15);
        LinearLayout titles = Ui.column(this);
        Ui.add(titles, Ui.overline(this, series.provider.isEmpty()
            ? getString(R.string.series) : CatalogSource.label(series.provider), Ui.ACCENT), Ui.FILL, Ui.WRAP);
        Ui.gap(titles, 8);
        TextView title = Ui.title(this, series.title, 27);
        Ui.ellipsize(title, 3);
        Ui.add(titles, title, Ui.FILL, Ui.WRAP);
        Ui.gap(titles, 8);
        Ui.add(titles, Ui.label(this, getString(R.string.read_count, series.done, series.count), 13, Ui.MUTED), Ui.FILL, Ui.WRAP);
        Ui.weighted(row, titles);
        Ui.add(content, row, Ui.FILL, Ui.WRAP);
        if (chapter != null) {
            Ui.gap(content, 14);
            String action = chapter.opened > 0 ? getString(R.string.resume_heading) : getString(R.string.read_chapter, chapter.number);
            if (chapter.number.isEmpty() && chapter.opened == 0) action = getString(R.string.start_reading);
            Ui.add(content, Ui.button(this, action, true, () -> open(chapter.id, false)), Ui.FILL, Ui.WRAP);
        }
        return hero(content);
    }

    private View seriesCover(long id, String provider, String chapterDirectory, int radius) {
        if (provider.isEmpty()) return Ui.cover(this, db.cover(chapterDirectory), radius);
        return RemoteCovers.poster(this, queue.title(id), radius);
    }

    private LinearLayout filters() {
        LinearLayout row = Ui.row(this);
        filterButton(row, 0, getString(R.string.all));
        Ui.spacer(row, 7);
        filterButton(row, 1, getString(R.string.in_progress));
        Ui.spacer(row, 7);
        filterButton(row, 2, getString(R.string.read));
        return row;
    }

    private void filterButton(LinearLayout row, int id, String text) {
        boolean active = id == filter;
        TextView view = Ui.chip(this, text, active ? Ui.ACCENT : Ui.RAISED, active ? Ui.ON_ACCENT : Ui.TEXT);
        view.setMinHeight(Ui.dp(this, 42));
        view.setSelected(active);
        Ui.clickable(view, active ? Ui.ACCENT : Ui.RAISED, 14, () -> {
            if (filter != id) {
                filter = id;
                draw(false);
            }
        });
        Ui.weighted(row, view);
    }

    private final class ChapterRows extends BaseAdapter {
        private final List<ShelfDb.Chapter> chapters;

        ChapterRows(List<ShelfDb.Chapter> chapters) { this.chapters = chapters; }
        @Override public int getCount() { return chapters.size(); }
        @Override public ShelfDb.Chapter getItem(int position) { return chapters.get(position); }
        @Override public long getItemId(int position) { return getItem(position).id; }

        @Override
        public View getView(int position, View recycled, ViewGroup parent) {
            ChapterLine line = recycled == null ? new ChapterLine() : (ChapterLine) recycled;
            line.bind(getItem(position));
            return line;
        }
    }

    private final class ChapterLine extends LinearLayout {
        private final LinearLayout card;
        private final TextView label;
        private final TextView meta;
        private final View dot;
        private final View more;

        ChapterLine() {
            super(MainActivity.this);
            setOrientation(VERTICAL);
            Ui.pad(this, 22, 4, 22, 4);
            card = Ui.row(MainActivity.this);
            card.setBackground(Ui.card(MainActivity.this, Ui.SURFACE, 8));
            Ui.pad(card, 12, 6, 5, 6);
            dot = new View(MainActivity.this);
            Ui.add(card, dot, 8, 8);
            Ui.spacer(card, 11);
            LinearLayout labels = Ui.column(MainActivity.this);
            label = Ui.strong(MainActivity.this, "", 16, Ui.TEXT);
            Ui.ellipsize(label, 2);
            Ui.add(labels, label, Ui.FILL, Ui.WRAP);
            Ui.gap(labels, 5);
            meta = Ui.label(MainActivity.this, "", 12, Ui.MUTED);
            Ui.add(labels, meta, Ui.FILL, Ui.WRAP);
            Ui.weighted(card, labels);
            more = Ui.iconButton(MainActivity.this, "more", getString(R.string.chapter_actions), Ui.SURFACE, Ui.MUTED, () -> {});
            Ui.add(card, more, 46, 48);
            Ui.add(this, card, Ui.FILL, Ui.WRAP);
        }

        void bind(ShelfDb.Chapter chapter) {
            label.setText(chapter.label(MainActivity.this));
            String state = chapter.done ? getString(R.string.read) : (chapter.opened > 0 ? getString(R.string.in_progress) : getString(R.string.unread));
            int ink = chapter.done ? Ui.MINT : (chapter.opened > 0 ? Ui.ACCENT : Ui.SOFT);
            meta.setText(state + (chapter.ms > 0 ? "  ·  " + Ui.duration(MainActivity.this, chapter.ms) : ""));
            meta.setTextColor(ink);
            dot.setBackground(Ui.shape(MainActivity.this, ink, 12));
            more.setOnClickListener(v -> chapterMenu(v, chapter));
            Ui.touchOverlay(card, 16, () -> open(chapter.id, false));
            card.setOnLongClickListener(v -> {
                chapterMenu(more, chapter);
                return true;
            });
        }
    }

    private void chapterMenu(View anchor, ShelfDb.Chapter chapter) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(getString(R.string.restart_chapter)).setOnMenuItemClickListener(item -> {
            open(chapter.id, true);
            return true;
        });
        if (chapter.provider.isEmpty()) {
            menu.getMenu().add(getString(R.string.edit_chapter)).setOnMenuItemClickListener(item -> {
                edit(chapter);
                return true;
            });
        }
        menu.getMenu().add(getString(R.string.delete_chapter)).setOnMenuItemClickListener(item -> {
            new AlertDialog.Builder(this).setTitle(getString(R.string.delete_chapter_question))
                .setMessage(getString(R.string.delete_chapter_detail))
                .setNegativeButton(getString(R.string.cancel), null)
                .setPositiveButton(getString(R.string.delete), (dialog, which) -> {
                    db.remove(chapter);
                    draw(false);
                }).show();
            return true;
        });
        menu.show();
    }

    private void edit(ShelfDb.Chapter chapter) {
        LinearLayout inputs = Ui.page(this);
        Ui.gap(inputs, 14);
        Ui.add(inputs, Ui.label(this, getString(R.string.series), 12, Ui.MUTED), Ui.FILL, Ui.WRAP);
        EditText title = ImportActivity.titleField(this, db, chapter.title);
        Ui.add(inputs, title, Ui.FILL, Ui.WRAP);
        Ui.gap(inputs, 10);
        Ui.add(inputs, Ui.label(this, getString(R.string.chapter_number), 12, Ui.MUTED), Ui.FILL, Ui.WRAP);
        EditText number = ImportActivity.field(this, getString(R.string.unnumbered), chapter.number,
            InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        Ui.add(inputs, number, Ui.FILL, Ui.WRAP);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.chapter_details).setView(inputs)
            .setNegativeButton(getString(R.string.cancel), null).setPositiveButton(getString(R.string.save), null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String titleText = title.getText().toString().trim();
                if (Names.key(titleText).isEmpty()) {
                    title.setError(getString(R.string.required_series));
                    return;
                }
                db.editChapter(chapter, titleText, Names.normalizeNumber(number.getText().toString()));
                dialog.dismiss();
                draw(false);
            } catch (IllegalArgumentException e) {
                number.setError(getString(R.string.bad_number));
            } catch (IOException e) {
                Toast.makeText(this, Fault.describe(this, e, R.string.file_error), Toast.LENGTH_LONG).show();
            }
        }));
        dialog.show();
    }

    private DateTimeFormatter longDate() {
        Locale locale = Prefs.locale(this);
        return DateTimeFormatter.ofPattern("ru".equals(locale.getLanguage()) ? "d MMMM" : "MMMM d", locale);
    }

    private View stats() {
        LocalDate today = LocalDate.now();
        LocalDate first = today.minusDays(6);
        LocalDate selected = statsDay == null || statsDay.isBefore(first) || statsDay.isAfter(today) ? today : statsDay;
        Map<LocalDate, Long> week = db.dailyTotals(first, today);
        List<ShelfDb.DailyEntry> entries = db.day(this, selected);
        LinearLayout page = Ui.page(this);
        Ui.gap(page, 19);
        Ui.add(page, Ui.overline(this, getString(R.string.reading_log), Ui.ACCENT), Ui.FILL, Ui.WRAP);
        Ui.gap(page, 8);
        Ui.add(page, Ui.title(this, getString(R.string.stats), 32), Ui.FILL, Ui.WRAP);
        Ui.gap(page, 18);

        LinearLayout content = Ui.column(this);
        Ui.pad(content, 19, 19, 19, 19);
        Ui.add(content, Ui.overline(this, selected.equals(today) ? getString(R.string.today) : longDate().format(selected).toUpperCase(Prefs.locale(this)),
            Ui.ACCENT), Ui.FILL, Ui.WRAP);
        Ui.gap(content, 8);
        Ui.add(content, Ui.title(this, Ui.duration(this, week.getOrDefault(selected, 0L)), 40), Ui.FILL, Ui.WRAP);
        Ui.gap(content, 7);
        Ui.add(content, Ui.label(this, getString(R.string.reading_time) + "  ·  " + Ui.chapters(this, entries.size()), 14, Ui.MUTED), Ui.FILL, Ui.WRAP);
        Ui.add(page, hero(content), Ui.FILL, Ui.WRAP);
        Ui.gap(page, 16);

        Ui.add(page, weekCard(first, selected, week), Ui.FILL, Ui.WRAP);
        Ui.gap(page, 23);
        Ui.add(page, Ui.title(this, getString(R.string.by_chapter), 23), Ui.FILL, Ui.WRAP);
        Ui.gap(page, 12);
        if (entries.isEmpty()) {
            Ui.add(page, emptyCard("clock", getString(R.string.no_sessions), getString(R.string.no_sessions_detail),
                null, null), Ui.FILL, Ui.WRAP);
        } else {
            for (ShelfDb.DailyEntry entry : entries) {
                Ui.add(page, timeRow(entry), Ui.FILL, Ui.WRAP);
                Ui.gap(page, 8);
            }
        }
        return Ui.scroller(this, page);
    }

    private View weekCard(LocalDate first, LocalDate selected, Map<LocalDate, Long> week) {
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.card(this, Ui.SURFACE, 9));
        Ui.pad(card, 13, 15, 13, 12);
        LinearLayout heading = Ui.row(this);
        Ui.weighted(heading, Ui.strong(this, getString(R.string.seven_days), 16, Ui.TEXT));
        Ui.add(heading, Ui.label(this, longDate().format(selected), 12, Ui.MUTED), Ui.WRAP, Ui.WRAP);
        Ui.add(card, heading, Ui.FILL, Ui.WRAP);
        Ui.gap(card, 10);
        LinearLayout chart = Ui.row(this);
        long max = 1;
        for (long ms : week.values()) max = Math.max(max, ms);
        for (int index = 0; index < 7; index++) {
            LocalDate day = first.plusDays(index);
            long ms = week.getOrDefault(day, 0L);
            boolean active = day.equals(selected);
            LinearLayout column = Ui.column(this);
            column.setGravity(Gravity.CENTER_HORIZONTAL);
            Ui.pad(column, 0, 7, 0, 8);
            FrameLayout barFrame = new FrameLayout(this);
            View bar = new View(this);
            bar.setBackground(Ui.shape(this, active ? Ui.ACCENT : 0xff87735e, 6));
            int barHeight = Math.max(4, (int) Math.round(58d * ms / max));
            barFrame.addView(bar, new FrameLayout.LayoutParams(Ui.dp(this, 16), Ui.dp(this, barHeight),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
            Ui.add(column, barFrame, Ui.FILL, 60);
            Ui.gap(column, 8);
            Ui.add(column, Ui.strong(this, DateTimeFormatter.ofPattern("EE", Prefs.locale(this)).format(day), 11, active ? Ui.ACCENT : Ui.MUTED), Ui.WRAP, Ui.WRAP);
            column.setContentDescription(longDate().format(day) + ", " + Ui.duration(this, ms));
            column.setSelected(active);
            Ui.clickable(column, active ? Ui.RAISED : Ui.SURFACE, 11, () -> {
                statsDay = day;
                draw(false);
            });
            Ui.weighted(chart, column);
        }
        Ui.add(card, chart, Ui.FILL, Ui.WRAP);
        return card;
    }

    private View timeRow(ShelfDb.DailyEntry entry) {
        LinearLayout row = Ui.row(this);
        row.setBackground(Ui.card(this, Ui.SURFACE, 8));
        Ui.pad(row, 13, 13, 13, 13);
        Ui.Icon icon = new Ui.Icon(this, "file", Ui.ACCENT, 19);
        icon.setBackground(Ui.shape(this, Ui.RAISED, 11));
        Ui.add(row, icon, 36, 36);
        Ui.spacer(row, 11);
        LinearLayout labels = Ui.column(this);
        TextView title = Ui.strong(this, entry.series, 15, Ui.TEXT);
        Ui.ellipsize(title, 1);
        Ui.add(labels, title, Ui.FILL, Ui.WRAP);
        Ui.gap(labels, 5);
        TextView subtitle = Ui.label(this, entry.label, 12, Ui.MUTED);
        Ui.ellipsize(subtitle, 1);
        Ui.add(labels, subtitle, Ui.FILL, Ui.WRAP);
        Ui.weighted(row, labels);
        Ui.spacer(row, 8);
        Ui.add(row, Ui.strong(this, Ui.duration(this, entry.ms), 14, Ui.ACCENT), Ui.WRAP, Ui.WRAP);
        Ui.touchOverlay(row, 16, () -> open(entry.chapterId, false));
        return row;
    }

    private View emptyCard(String iconName, String title, String detail, String action, Runnable click) {
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.card(this, Ui.SURFACE, 9));
        Ui.pad(card, 17, 17, 17, 17);
        Ui.Icon icon = new Ui.Icon(this, iconName, Ui.ACCENT, 22);
        icon.setBackground(Ui.shape(this, Ui.RAISED, 13));
        Ui.add(card, icon, 42, 42);
        Ui.gap(card, 12);
        Ui.add(card, Ui.strong(this, title, 17, Ui.TEXT), Ui.FILL, Ui.WRAP);
        Ui.gap(card, 8);
        Ui.add(card, Ui.label(this, detail, 14, Ui.MUTED), Ui.FILL, Ui.WRAP);
        if (action != null) {
            Ui.gap(card, 14);
            Ui.add(card, Ui.button(this, action, true, click), Ui.FILL, Ui.WRAP);
        }
        return card;
    }

    private void open(long chapter, boolean restart) {
        startActivity(ReaderActivity.intent(this, chapter, restart));
    }

    private void importFiles(String title) {
        startActivityForResult(new Intent(this, ImportActivity.class).putExtra(ImportActivity.SERIES, title), IMPORT);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == SETUP) {
            setupLaunched = false;
            if (!Prefs.ready(this)) finish();
        }
        if (request == IMPORT && result == RESULT_OK && data != null) {
            tab = LIBRARY;
            seriesId = data.getLongExtra(ImportActivity.RESULT_SERIES, -1);
            filter = 0;
        }
    }

    private void leaveDetails() {
        seriesId = -1;
        draw(false);
    }

    @Override
    public void onBackPressed() {
        if (seriesId != -1) leaveDetails();
        else if (tab != HOME) showTab(HOME);
        else super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("tab", tab);
        state.putLong("series", seriesId);
        state.putInt("filter", filter);
        state.putString("search", search);
        state.putString("online", catalog == null ? onlineQuery : catalog.query());
    }
    @Override
    protected void onDestroy() {
        if (catalog != null) catalog.close();
        super.onDestroy();
    }

}
