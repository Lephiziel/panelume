package dev.panelume.reader;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Ui {
    static final int BG = 0xff171817;
    static final int SURFACE = 0xff20211f;
    static final int RAISED = 0xff2a2b28;
    static final int BORDER = 0xff40413b;
    static final int TEXT = 0xfff4f1e9;
    static final int MUTED = 0xffc1bdb1;
    static final int SOFT = 0xffa6a498;
    static final int ACCENT = 0xfff7ae72;
    static final int ON_ACCENT = 0xff26180d;
    static final int MINT = 0xffb2cfaf;
    static final int ERROR = 0xffffb4ab;
    static final int FILL = LinearLayout.LayoutParams.MATCH_PARENT;
    static final int WRAP = LinearLayout.LayoutParams.WRAP_CONTENT;

    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private static final Typeface DISPLAY = Typeface.create("serif", Typeface.NORMAL);
    private static final PathInterpolator EASE = new PathInterpolator(.2f, .8f, .2f, 1f);
    private static final ExecutorService COVER_WORKER = Executors.newSingleThreadExecutor();
    private static final LruCache<String, Bitmap> COVERS = new LruCache<String, Bitmap>(10 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };

    private Ui() {}

    static int dp(Context context, float value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }

    static void edgeWindow(Activity activity, int color) {
        Window window = activity.getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            window.setNavigationBarContrastEnforced(false);
            window.setStatusBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
        } else {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        window.getDecorView().setBackgroundColor(color);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attributes);
        }
    }

    static void fitInsets(View root) {
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets area = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                    | WindowInsets.Type.ime());
                view.setPadding(area.left, area.top, area.right, area.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
    }

    static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    static LinearLayout row(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    static LinearLayout page(Context context) {
        LinearLayout layout = column(context);
        pad(layout, 22, 0, 22, 24);
        return layout;
    }

    static ScrollView scroller(Context context, View content) {
        ScrollView view = new ScrollView(context);
        view.setFillViewport(true);
        view.setClipToPadding(false);
        view.setVerticalScrollBarEnabled(false);
        view.addView(content, new ScrollView.LayoutParams(FILL, WRAP));
        return view;
    }

    static void pad(View view, int left, int top, int right, int bottom) {
        Context c = view.getContext();
        view.setPadding(dp(c, left), dp(c, top), dp(c, right), dp(c, bottom));
    }

    static void add(LinearLayout parent, View child, int width, int height) {
        Context c = parent.getContext();
        parent.addView(child, new LinearLayout.LayoutParams(width < 0 ? width : dp(c, width),
            height < 0 ? height : dp(c, height)));
    }

    static void gap(LinearLayout parent, int dp) {
        add(parent, new View(parent.getContext()), FILL, dp);
    }

    static void spacer(LinearLayout row, int dp) {
        add(row, new View(row.getContext()), dp, 1);
    }

    static void weighted(LinearLayout row, View view) {
        row.addView(view, new LinearLayout.LayoutParams(0, WRAP, 1));
    }

    static TextView label(Context c, String text, float size, int color) {
        TextView view = new TextView(c);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        view.setLineSpacing(dp(c, 3), 1f);
        return view;
    }

    static TextView strong(Context c, String text, float size, int color) {
        TextView view = label(c, text, size, color);
        view.setTypeface(MEDIUM);
        return view;
    }

    static TextView title(Context c, String text, int size) {
        TextView view = label(c, text, size, TEXT);
        view.setTypeface(size >= 24 ? DISPLAY : MEDIUM);
        return view;
    }

    static TextView overline(Context c, String text, int color) {
        TextView view = strong(c, text, 11, color);
        view.setLetterSpacing(.10f);
        return view;
    }

    static void ellipsize(TextView view, int lines) {
        view.setMaxLines(lines);
        view.setEllipsize(TextUtils.TruncateAt.END);
    }

    static GradientDrawable shape(Context c, int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(c, radius));
        return drawable;
    }

    static GradientDrawable card(Context c, int color, int radius) {
        GradientDrawable drawable = shape(c, color, radius);
        drawable.setStroke(dp(c, 1), BORDER);
        return drawable;
    }

    static void clickable(View v, int color, int radius, Runnable action) {
        Context context = v.getContext();
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x36f7ae72),
            shape(context, color, radius), shape(context, Color.WHITE, radius)));
        v.setFocusable(true);
        v.setOnClickListener(ignored -> action.run());
    }

    static void touchOverlay(View v, int radius, Runnable action) {
        v.setForeground(new RippleDrawable(ColorStateList.valueOf(0x32f7ae72), null,
            shape(v.getContext(), Color.WHITE, radius)));
        v.setFocusable(true);
        v.setOnClickListener(ignored -> action.run());
    }

    static TextView button(Context c, String text, boolean primary, Runnable action) {
        TextView v = strong(c, text, 15, primary ? ON_ACCENT : TEXT);
        v.setGravity(Gravity.CENTER);
        v.setMinHeight(dp(c, 49));
        pad(v, 15, 11, 15, 11);
        clickable(v, primary ? ACCENT : RAISED, 8, action);
        return v;
    }

    static TextView chip(Context c, String label, int fill, int ink) {
        TextView view = strong(c, label, 12, ink);
        view.setGravity(Gravity.CENTER);
        pad(view, 9, 6, 9, 6);
        view.setBackground(shape(c, fill, 20));
        return view;
    }

    static View iconButton(Context c, String name, String description, int background, int ink, Runnable action) {
        Icon icon = new Icon(c, name, ink, 22);
        icon.setContentDescription(description);
        clickable(icon, background, 8, action);
        return icon;
    }

    static ImageView mark(Context c, int radius) {
        ImageView icon = new ImageView(c);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImageResource(R.drawable.panelume_mark);
        icon.setBackground(shape(c, RAISED, radius));
        icon.setClipToOutline(true);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return icon;
    }

    static ImageView cover(Context c, File file, int radius) {
        ImageView image = new ImageView(c);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackground(shape(c, RAISED, radius));
        image.setClipToOutline(true);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        image.setImageResource(R.drawable.panelume_mark);
        String key = file.getAbsolutePath();
        Bitmap cached = COVERS.get(key);
        if (cached != null) {
            image.setImageBitmap(cached);
        } else {
            COVER_WORKER.execute(() -> {
                Bitmap bitmap = BitmapFactory.decodeFile(key);
                if (bitmap == null) return;
                COVERS.put(key, bitmap);
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> image.setImageBitmap(bitmap));
            });
        }
        return image;
    }

    static FrameLayout progress(Context c, float fraction, int ink) {
        FrameLayout box = new FrameLayout(c);
        box.setBackground(shape(c, 0xff41413a, 8));
        box.setClipToOutline(true);
        View bar = new View(c);
        bar.setBackground(shape(c, ink, 8));
        box.addView(bar, new FrameLayout.LayoutParams(0, FILL));
        box.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            int width = Math.round((r - l) * Math.max(0, Math.min(1, fraction)));
            if (bar.getLayoutParams().width != width) {
                bar.getLayoutParams().width = width;
                bar.requestLayout();
            }
        });
        return box;
    }

    static void enter(View v, int offset, long delay) {
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        v.setAlpha(0f);
        v.setTranslationY(dp(v.getContext(), offset));
        v.animate().alpha(1f).translationY(0).setStartDelay(delay).setDuration(270).setInterpolator(EASE);
    }

    static void animateChrome(View view, boolean visible, boolean top) {
        view.animate().cancel();
        if (!ValueAnimator.areAnimatorsEnabled()) {
            view.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
            view.setAlpha(visible ? 1 : 0);
            return;
        }
        if (visible) view.setVisibility(View.VISIBLE);
        float shift = visible ? 0f : dp(view.getContext(), top ? -14 : 14);
        view.animate().alpha(visible ? 1f : 0f).translationY(shift).setDuration(190)
            .setInterpolator(EASE).withEndAction(() -> {
                if (!visible && view.getAlpha() == 0f) view.setVisibility(View.INVISIBLE);
            });
    }

    static String duration(Context c, long milliseconds) {
        long seconds = Math.max(0, milliseconds / 1000);
        if (seconds >= 3600) return c.getString(R.string.duration_hours, seconds / 3600, seconds / 60 % 60);
        if (seconds >= 60) return c.getString(R.string.duration_minutes, seconds / 60, seconds % 60);
        return c.getString(R.string.duration_seconds, seconds);
    }

    static String chapters(Context c, int count) {
        return c.getResources().getQuantityString(R.plurals.chapter_count, count, count);
    }

    static void rule(LinearLayout parent) {
        View line = new View(parent.getContext());
        line.setBackgroundColor(BORDER);
        add(parent, line, FILL, 1);
    }

    static LinearLayout toolbar(Context context, String title, Runnable back) {
        LinearLayout bar = row(context);
        pad(bar, 12, 5, 12, 5);
        if (back != null) {
            add(bar, iconButton(context, "back", context.getString(R.string.back), BG, TEXT, back), 48, 48);
            spacer(bar, 8);
        }
        TextView label = strong(context, title, 17, TEXT);
        ellipsize(label, 1);
        weighted(bar, label);
        return bar;
    }

    static LinearLayout screen(Activity activity) {
        edgeWindow(activity, BG);
        LinearLayout root = column(activity);
        root.setBackgroundColor(BG);
        fitInsets(root);
        activity.setContentView(root);
        return root;
    }

    static final class StripArt extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        StripArt(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float width = getWidth();
            float height = getHeight();
            if (width <= 0 || height <= 0) return;
            canvas.save();
            canvas.scale(width / 124, height / 116);
            paint.setStrokeWidth(1.4f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(0xff5b5247);
            canvas.drawRect(26, 13, 110, 106, paint);
            canvas.drawRect(15, 5, 98, 97, paint);
            paint.setColor(ACCENT);
            canvas.drawRect(5, 0, 88, 88, paint);
            canvas.drawRect(13, 8, 80, 43, paint);
            canvas.drawRect(13, 50, 47, 80, paint);
            canvas.drawRect(54, 50, 80, 80, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x39f7ae72);
            canvas.drawCircle(61, 24, 9, paint);
            Path ridge = new Path();
            ridge.moveTo(14, 41);
            ridge.lineTo(33, 24);
            ridge.lineTo(48, 41);
            ridge.close();
            canvas.drawPath(ridge, paint);
            canvas.restore();
        }
    }

    static final class Icon extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String name;
        private final int size;
        private final int ink;

        Icon(Context context, String name, int ink, int size) {
            super(context);
            this.name = name;
            this.size = size;
            this.ink = ink;
        }

        @Override
        protected void onDraw(Canvas c) {
            float pixels = dp(getContext(), size);
            c.save();
            c.translate((getWidth() - pixels) / 2, (getHeight() - pixels) / 2);
            c.scale(pixels / 24, pixels / 24);
            paint.setColor(ink);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.8f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            switch (name) {
                case "home":
                    line(c, 3, 10, 12, 3, 21, 10, 21, 20, 15, 20, 15, 14, 9, 14, 9, 20, 3, 20, 3, 10);
                    break;
                case "shelf":
                    c.drawRoundRect(3, 4, 8, 21, 1, 1, paint);
                    c.drawRoundRect(10, 4, 14, 21, 1, 1, paint);
                    line(c, 16, 5, 19, 4, 22, 20, 19, 21, 16, 5);
                    break;
                case "stats":
                    line(c, 4, 4, 4, 20, 21, 20);
                    line(c, 9, 15, 9, 11);
                    line(c, 14, 15, 14, 6);
                    line(c, 19, 15, 19, 9);
                    break;
                case "clock":
                    c.drawCircle(12, 12, 8.6f, paint);
                    line(c, 12, 7, 12, 12, 15.4f, 14);
                    break;
                case "add":
                    line(c, 12, 5, 12, 19);
                    line(c, 5, 12, 19, 12);
                    break;
                case "back":
                    line(c, 14.8f, 5, 7.8f, 12, 14.8f, 19);
                    break;
                case "next":
                    line(c, 9, 5, 16, 12, 9, 19);
                    break;
                case "link":
                    line(c, 9, 15, 15, 9);
                    c.drawArc(4, 9, 14, 19, 25, 260, false, paint);
                    c.drawArc(10, 4, 20, 14, 205, 260, false, paint);
                    break;
                case "file":
                    line(c, 6, 3, 14, 3, 19, 8, 19, 21, 6, 21, 6, 3);
                    line(c, 14, 3, 14, 8, 19, 8);
                    line(c, 9, 13, 16, 13);
                    line(c, 9, 17, 15, 17);
                    break;
                case "download":
                    line(c, 12, 4, 12, 15);
                    line(c, 7, 10, 12, 15, 17, 10);
                    line(c, 5, 17, 5, 20, 19, 20, 19, 17);
                    break;
                case "external":
                    line(c, 14, 4, 20, 4, 20, 10);
                    line(c, 12, 12, 20, 4);
                    line(c, 11, 5, 5, 5, 5, 19, 19, 19, 19, 13);
                    break;
                case "settings":
                    c.drawCircle(12, 12, 3, paint);
                    c.drawCircle(12, 12, 7, paint);
                    line(c, 12, 2, 12, 5);
                    line(c, 12, 19, 12, 22);
                    line(c, 2, 12, 5, 12);
                    line(c, 19, 12, 22, 12);
                    break;
                case "search":
                    c.drawCircle(10.5f, 10.5f, 5.8f, paint);
                    line(c, 15, 15, 20, 20);
                    break;
                case "done":
                    line(c, 5, 12.5f, 10, 17, 19, 7);
                    break;
                case "restart":
                    c.drawArc(5, 5, 20, 20, -128, 292, false, paint);
                    line(c, 4, 6, 4, 12, 10, 12);
                    break;
                case "more":
                    paint.setStyle(Paint.Style.FILL);
                    c.drawCircle(12, 5, 1.45f, paint);
                    c.drawCircle(12, 12, 1.45f, paint);
                    c.drawCircle(12, 19, 1.45f, paint);
                    break;
                default:
                    c.drawCircle(12, 12, 2, paint);
            }
            c.restore();
        }

        private void line(Canvas c, float... points) {
            Path path = new Path();
            path.moveTo(points[0], points[1]);
            for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
            c.drawPath(path, paint);
        }
    }
}
